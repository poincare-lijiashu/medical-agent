package com.medassist.auth.service;

import com.medassist.auth.web.dto.TenantHealthDtos;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.MultiGauge;
import io.micrometer.core.instrument.Tags;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 租户健康度 → Prometheus 指标（第 287 批 / ADR-001 §3「观测」契约）。
 *
 * <p>为什么不是"抓取时现查"：健康度要连每家医院的库（单院 4 秒超时）。若在
 * {@code /actuator/prometheus} 抓取时同步执行，一次 30 秒的 scrape 会被最慢的医院拖住，
 * 甚至把 auth 线程池打满——监控反过来拖垮被监控系统。这里改为**定时刷新 + 抓取只读缓存值**。
 *
 * <p>指标全部带 {@code tenant} 标签，供 Prometheus 规则与 Alertmanager 按院路由：
 * <ul>
 *   <li>{@code medassist_tenant_health_state{tenant,state}} 当前结论（每个院只有一条为 1）；</li>
 *   <li>{@code medassist_tenant_cell_reachable{tenant}} 0/1；</li>
 *   <li>{@code medassist_tenant_backup_ready{tenant}} 1=READY，其余 0；</li>
 *   <li>{@code medassist_tenant_last_archived_timestamp_seconds{tenant}} 最近 WAL 归档时间；</li>
 *   <li>{@code medassist_tenant_active_users{tenant}} 启用账号数；</li>
 *   <li>{@code medassist_tenant_health_refresh_timestamp_seconds} 上次刷新时刻（陈旧即告警）；</li>
 *   <li>{@code medassist_tenant_health_refresh_duration_seconds} / {@code ..._errors_total}。</li>
 * </ul>
 *
 * <p>安全：只暴露租户编码、状态与计数，不含医院名称、联系人、患者或业务数据。
 */
@Component
public class TenantHealthMetrics {

    private static final Logger log = LoggerFactory.getLogger(TenantHealthMetrics.class);

    private final TenantHealthService health;

    private final MultiGauge healthState;
    private final MultiGauge backupState;
    private final MultiGauge cellReachable;
    private final MultiGauge backupReady;
    private final MultiGauge activeUsers;
    private final MultiGauge lastArchived;
    private final MultiGauge tenantCount;
    private final Gauge refreshTimestamp;
    private final Gauge refreshDuration;
    private final Counter refreshErrors;

    private final AtomicBoolean refreshing = new AtomicBoolean(false);
    private final AtomicLong lastRefreshEpoch = new AtomicLong(0);
    private final AtomicLong lastDurationMillis = new AtomicLong(0);

    public TenantHealthMetrics(TenantHealthService health, MeterRegistry registry) {
        this.health = health;
        this.healthState = MultiGauge.builder("medassist.tenant.health.state")
                .description("租户健康结论（每个院一条为 1）：OK/PENDING/SUSPENDED/CLOSED/DEGRADED/DOWN")
                .register(registry);
        this.backupState = MultiGauge.builder("medassist.tenant.backup.state")
                .description("租户备份状态（每个院一条为 1）：READY/PENDING/DEGRADED/FAILED/NONE/UNKNOWN")
                .register(registry);
        this.cellReachable = MultiGauge.builder("medassist.tenant.cell.reachable")
                .description("Cell 数据库是否可达（1=可达）")
                .register(registry);
        this.backupReady = MultiGauge.builder("medassist.tenant.backup.ready")
                .description("备份/PITR 是否就绪（1=READY）")
                .register(registry);
        this.activeUsers = MultiGauge.builder("medassist.tenant.active.users")
                .description("租户启用状态账号数")
                .register(registry);
        this.lastArchived = MultiGauge.builder("medassist.tenant.last.archived.timestamp.seconds")
                .description("最近一次成功 WAL 归档的 Unix 时间戳（0=从未归档）")
                .register(registry);
        this.tenantCount = MultiGauge.builder("medassist.tenant.count")
                .description("按健康结论统计的租户数量")
                .register(registry);
        this.refreshTimestamp = Gauge.builder("medassist.tenant.health.refresh.timestamp.seconds",
                        lastRefreshEpoch, AtomicLong::get)
                .description("上一次健康度指标刷新时刻（Unix 秒）；长时间不前进说明刷新卡住")
                .register(registry);
        this.refreshDuration = Gauge.builder("medassist.tenant.health.refresh.duration.seconds",
                        lastDurationMillis, v -> v.get() / 1000.0)
                .description("上一次健康度刷新耗时（秒）")
                .register(registry);
        this.refreshErrors = Counter.builder("medassist.tenant.health.refresh.errors")
                .description("健康度指标刷新失败次数")
                .register(registry);
    }

    /**
     * 定时刷新指标。
     *
     * <p>单飞：上一轮还没跑完（慢院 / 连接超时）时直接跳过本轮，避免任务堆积。
     * 刷新失败只记计数与日志，不抛出——监控自身故障不能让 auth 的调度线程停摆。
     */
    @Scheduled(fixedDelayString = "${medassist.tenant-health.metrics-interval:PT60S}",
            initialDelayString = "${medassist.tenant-health.metrics-initial-delay:PT20S}")
    public void refresh() {
        if (!refreshing.compareAndSet(false, true)) {
            log.debug("tenant.metrics.refresh.skipped reason=previous_still_running");
            return;
        }
        long started = System.nanoTime();
        try {
            List<TenantHealthDtos.HealthView> views = health.list().items();
            publish(views);
            lastRefreshEpoch.set(Instant.now().getEpochSecond());
            log.debug("tenant.metrics.refreshed tenants={}", views.size());
        } catch (RuntimeException e) {
            refreshErrors.increment();
            log.warn("tenant.metrics.refresh.failed reason={}", e.getClass().getSimpleName());
        } finally {
            lastDurationMillis.set(Math.max(0, (System.nanoTime() - started) / 1_000_000));
            refreshing.set(false);
        }
    }

    private void publish(List<TenantHealthDtos.HealthView> views) {
        List<MultiGauge.Row<?>> states = new ArrayList<>();
        List<MultiGauge.Row<?>> backups = new ArrayList<>();
        List<MultiGauge.Row<?>> reachable = new ArrayList<>();
        List<MultiGauge.Row<?>> ready = new ArrayList<>();
        List<MultiGauge.Row<?>> users = new ArrayList<>();
        List<MultiGauge.Row<?>> archived = new ArrayList<>();
        Map<String, Integer> counts = new LinkedHashMap<>();

        for (TenantHealthDtos.HealthView view : views) {
            String tenant = view.tenantCode();
            String state = view.state() == null ? "UNKNOWN" : view.state();
            String backup = view.backupState() == null ? "UNKNOWN" : view.backupState();

            states.add(MultiGauge.Row.of(Tags.of("tenant", tenant, "state", state), 1));
            backups.add(MultiGauge.Row.of(Tags.of("tenant", tenant, "state", backup), 1));
            reachable.add(MultiGauge.Row.of(Tags.of("tenant", tenant),
                    view.cellReachable() ? 1 : 0));
            ready.add(MultiGauge.Row.of(Tags.of("tenant", tenant),
                    "READY".equals(backup) ? 1 : 0));
            users.add(MultiGauge.Row.of(Tags.of("tenant", tenant), view.activeUserCount()));
            archived.add(MultiGauge.Row.of(Tags.of("tenant", tenant),
                    view.backupLastArchivedAt() == null ? 0 : view.backupLastArchivedAt().getEpochSecond()));
            counts.merge(state, 1, Integer::sum);
        }

        healthState.register(states, true);
        backupState.register(backups, true);
        cellReachable.register(reachable, true);
        backupReady.register(ready, true);
        activeUsers.register(users, true);
        lastArchived.register(archived, true);

        List<MultiGauge.Row<?>> countRows = new ArrayList<>();
        counts.forEach((state, count) ->
                countRows.add(MultiGauge.Row.of(Tags.of("state", state), count)));
        tenantCount.register(countRows, true);
    }
}
