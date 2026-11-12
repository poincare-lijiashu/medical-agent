package com.medassist.license.service;

import com.medassist.license.domain.LicenseModel.ModuleEntitlement;
import com.medassist.license.domain.LicenseModel.Quota;
import com.medassist.license.web.LicenseDtos.StatusView;
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
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 授权状态 → Prometheus 指标（第 288 批）。
 *
 * <p>授权到期不是"页面上的一个日期"：它必须能触发告警，否则等到模块被门禁拦截
 * 才发现合同过期。指标按部署实例（{@code instance}）打标签——一个 Cell 一套实例。
 *
 * <p>刷新频率低（默认 5 分钟）且单飞：授权状态变化很慢，没必要每次抓取都查库；
 * 抓取只读缓存值，避免 Prometheus 轮询把授权服务的连接池打满。
 *
 * <p>指标：
 * <ul>
 *   <li>{@code medassist_license_phase{instance,phase}} 当前阶段（一条为 1）；</li>
 *   <li>{@code medassist_license_days_left{instance}} 距到期/宽限结束天数（无授权 -1）；</li>
 *   <li>{@code medassist_license_module_entitled{instance,module}} 模块是否已授权；</li>
 *   <li>{@code medassist_license_quota_utilization{instance,quota}} 配额使用率；</li>
 *   <li>{@code medassist_license_quota_exceeded{instance,quota}} 是否超限；</li>
 *   <li>{@code medassist_license_refresh_timestamp_seconds} / {@code ..._errors_total}。</li>
 * </ul>
 */
@Component
public class LicenseMetrics {

    private static final Logger log = LoggerFactory.getLogger(LicenseMetrics.class);

    private final LicenseService service;

    private final MultiGauge phase;
    private final MultiGauge daysLeft;
    private final MultiGauge modules;
    private final MultiGauge quotaUtilization;
    private final MultiGauge quotaExceeded;
    private final Gauge refreshTimestamp;
    private final Counter refreshErrors;

    private final AtomicBoolean refreshing = new AtomicBoolean(false);
    private final AtomicLong lastRefresh = new AtomicLong(0);

    public LicenseMetrics(LicenseService service, MeterRegistry registry) {
        this.service = service;
        this.phase = MultiGauge.builder("medassist.license.phase")
                .description("授权阶段（ACTIVE/PENDING/GRACE/EXPIRED/REVOKED/NONE，每个实例一条为 1）")
                .register(registry);
        this.daysLeft = MultiGauge.builder("medassist.license.days.left")
                .description("距到期（或宽限结束）天数；无授权为 -1")
                .register(registry);
        this.modules = MultiGauge.builder("medassist.license.module.entitled")
                .description("模块是否已授权（1/0）；模块门禁与商务对账共用")
                .register(registry);
        this.quotaUtilization = MultiGauge.builder("medassist.license.quota.utilization")
                .description("配额使用率（0-1+）；无用量快照时为 0")
                .register(registry);
        this.quotaExceeded = MultiGauge.builder("medassist.license.quota.exceeded")
                .description("配额是否超限（1/0）")
                .register(registry);
        this.refreshTimestamp = Gauge.builder("medassist.license.refresh.timestamp.seconds",
                        lastRefresh, AtomicLong::get)
                .description("授权指标刷新时刻（Unix 秒）")
                .register(registry);
        this.refreshErrors = Counter.builder("medassist.license.refresh.errors")
                .description("授权指标刷新失败次数")
                .register(registry);
    }

    @Scheduled(fixedDelayString = "${medassist.license.metrics-interval:PT5M}",
            initialDelayString = "${medassist.license.metrics-initial-delay:PT30S}")
    public void refresh() {
        if (!refreshing.compareAndSet(false, true)) {
            return;
        }
        try {
            publish(service.status());
            lastRefresh.set(Instant.now().getEpochSecond());
        } catch (RuntimeException e) {
            refreshErrors.increment();
            log.warn("license.metrics.refresh.failed reason={}", e.getClass().getSimpleName());
        } finally {
            refreshing.set(false);
        }
    }

    private void publish(StatusView status) {
        String instance = status.instanceCode();
        String current = status.phase() == null ? "NONE" : status.phase();
        long days = status.state() == null || status.state().daysLeft() == null
                ? -1L : status.state().daysLeft();

        phase.register(List.of(MultiGauge.Row.of(
                Tags.of("instance", instance, "phase", current), 1)), true);
        daysLeft.register(List.of(MultiGauge.Row.of(Tags.of("instance", instance), days)), true);

        List<MultiGauge.Row<?>> moduleRows = new ArrayList<>();
        for (ModuleEntitlement item : status.entitlements()) {
            moduleRows.add(MultiGauge.Row.of(
                    Tags.of("instance", instance, "module", item.code()),
                    item.entitled() ? 1 : 0));
        }
        modules.register(moduleRows, true);

        List<MultiGauge.Row<?>> utilization = new ArrayList<>();
        List<MultiGauge.Row<?>> exceeded = new ArrayList<>();
        for (Quota quota : status.quotas()) {
            utilization.add(MultiGauge.Row.of(
                    Tags.of("instance", instance, "quota", quota.code()),
                    quota.utilization() == null ? 0.0 : quota.utilization()));
            exceeded.add(MultiGauge.Row.of(
                    Tags.of("instance", instance, "quota", quota.code()),
                    quota.exceeded() ? 1 : 0));
        }
        quotaUtilization.register(utilization, true);
        quotaExceeded.register(exceeded, true);
    }
}
