package com.medassist.auth.service;

import com.medassist.auth.domain.Provisioning;
import com.medassist.auth.domain.Tenant;
import com.medassist.auth.repo.CellMigrationRepository;
import com.medassist.auth.repo.ProvisionRepository;
import com.medassist.auth.repo.TenantRepository;
import com.medassist.auth.web.dto.TenantHealthDtos;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 租户健康度（第 284 批 / ADR-001 P3）。
 *
 * <p>平台运营每天要回答的问题不是"台账里有几家医院"，而是"哪家现在需要人过去"。
 * 这里把四类事实合成一个结论：
 * <ol>
 *   <li>Cell 数据库是否可达、auth 域是否就绪（开通是否真的完成）；</li>
 *   <li>迁移台账是否有失败记录（版本是否健康）；</li>
 *   <li>最近一次开通任务是否失败（卡在哪一步）；</li>
 *   <li>租户自身状态（待开通/停用/注销）。</li>
 * </ol>
 *
 * <p>性能与隔离：每院独立探测、并行执行、单院 4 秒超时——
 * 一台离线医院只能让"它自己"显示 DOWN，不能让整个健康页变成 30 秒白屏。
 */
@Service
public class TenantHealthService {

    private static final Logger log = LoggerFactory.getLogger(TenantHealthService.class);

    private static final long PER_TENANT_TIMEOUT_SECONDS = 4;

    private final TenantRepository tenants;
    private final ProvisionRepository provisions;
    private final CellMigrationRepository migrations;
    private final CellDatabaseGateway cell;
    private final ExecutorService pool = Executors.newFixedThreadPool(4, runnable -> {
        Thread thread = new Thread(runnable, "tenant-health");
        thread.setDaemon(true);
        return thread;
    });

    public TenantHealthService(TenantRepository tenants, ProvisionRepository provisions,
                               CellMigrationRepository migrations, CellDatabaseGateway cell) {
        this.tenants = tenants;
        this.provisions = provisions;
        this.migrations = migrations;
        this.cell = cell;
    }

    public TenantHealthDtos.HealthList list() {
        List<Tenant> all = tenants.list(null);
        List<Future<TenantHealthDtos.HealthView>> futures = new ArrayList<>();
        for (Tenant tenant : all) {
            futures.add(pool.submit(() -> build(tenant)));
        }
        List<TenantHealthDtos.HealthView> items = new ArrayList<>();
        for (int i = 0; i < futures.size(); i++) {
            Tenant tenant = all.get(i);
            try {
                items.add(futures.get(i).get(PER_TENANT_TIMEOUT_SECONDS, TimeUnit.SECONDS));
            } catch (TimeoutException e) {
                futures.get(i).cancel(true);
                items.add(timeoutView(tenant));
            } catch (Exception e) {
                log.warn("tenant.health.failed tenant={} reason={}", tenant.code(),
                        e.getClass().getSimpleName());
                items.add(failedView(tenant));
            }
        }
        items.sort(Comparator.comparing(TenantHealthDtos.HealthView::tenantCode));
        return new TenantHealthDtos.HealthList(items, items.size(), Instant.now());
    }

    public TenantHealthDtos.HealthView one(String tenantCode) {
        Tenant tenant = tenants.findByCode(tenantCode)
                .orElseThrow(() -> new com.medassist.common.exception.BusinessException(
                        com.medassist.common.api.ErrorCode.TENANT_NOT_FOUND));
        return build(tenant);
    }

    // ---------------------------------------------------------------- 内部

    private TenantHealthDtos.HealthView build(Tenant tenant) {
        List<String> issues = new ArrayList<>();
        Provisioning.Job lastJob = provisions.latestForTenant(tenant.code()).orElse(null);
        List<CellMigrationRepository.CellMigration> latest = migrations.latestByService(tenant.code());
        boolean migrationFailure = migrations.hasFailure(tenant.code());
        Instant lastMigrationAt = latest.stream()
                .map(CellMigrationRepository.CellMigration::installedOn)
                .filter(java.util.Objects::nonNull)
                .max(Instant::compareTo)
                .orElse(null);

        boolean reachable = false;
        boolean authReady = false;
        Long latency = null;
        String authVersion = null;
        long users = 0;
        long activeUsers = 0;
        String backupState = "UNKNOWN";
        String backupBucket = null;
        Instant backupLastFullAt = null;
        int backupPitrDays = 0;
        long backupArchivedCount = 0;
        Instant backupLastArchivedAt = null;

        if (Tenant.CLOSED.equals(tenant.status())) {
            issues.add("已注销（终态）：数据按合同保留至 "
                    + (tenant.retentionUntil() == null ? "未登记" : tenant.retentionUntil()));
        } else if (tenant.dbHost() == null || tenant.dbHost().isBlank()) {
            issues.add("尚未登记 Cell 数据库坐标，无法探测");
        } else {
            CellDatabaseGateway.Probe probe = cell.probe(tenant.dbHost(), tenant.dbName());
            reachable = probe.reachable();
            authReady = probe.authReady();
            latency = probe.latencyMs();
            authVersion = probe.authVersion();
            if (!reachable) {
                issues.add("Cell 数据库不可达：" + probe.error());
            } else {
                if (!authReady) {
                    issues.add("auth 域尚未迁移完成（缺 tenant 表或 app_user.tenant_code）");
                }
                try {
                    users = cell.countUsers(tenant.dbHost(), tenant.dbName(), tenant.code());
                    activeUsers = cell.countActiveUsers(tenant.dbHost(), tenant.dbName(),
                            tenant.code());
                } catch (RuntimeException e) {
                    issues.add("账号统计失败：" + e.getMessage());
                }
                // §286：备份状态直接读 Cell——"有没有备份、能不能 PITR"是院自己的事实
                try {
                    CellDatabaseGateway.BackupStatus backup =
                            cell.backupStatus(tenant.dbHost(), tenant.dbName(), tenant.code());
                    if (!backup.present()) {
                        backupState = "NONE";
                        issues.add("尚未登记备份计划：该院没有独立备份仓库");
                    } else {
                        backupState = backup.status();
                        backupBucket = backup.bucket();
                        backupLastFullAt = backup.lastFullAt();
                        backupPitrDays = backup.pitrDays();
                        backupArchivedCount = backup.archivedCount();
                        backupLastArchivedAt = backup.lastArchivedAt();
                        if (!"READY".equals(backup.status())) {
                            issues.add("备份/PITR 未就绪（" + backup.status() + "）："
                                    + String.join("；", backup.issues()));
                        }
                    }
                } catch (RuntimeException e) {
                    backupState = "UNKNOWN";
                    issues.add("备份状态读取失败：" + e.getMessage());
                }
            }
        }

        if (migrationFailure) {
            issues.add("迁移台账存在失败记录：核对对应服务 schema 后再放量");
        }
        if (lastJob != null && Provisioning.FAILED.equals(lastJob.status())) {
            Object failedStep = lastJob.result().get("failedStep");
            issues.add("上次开通失败于步骤 " + (failedStep == null ? "UNKNOWN" : failedStep)
                    + "，可查看步骤详情后 resume");
        }
        if (Tenant.PENDING.equals(tenant.status())) {
            issues.add("尚未激活：等待开通完成");
        }
        if (Tenant.SUSPENDED.equals(tenant.status())) {
            issues.add("已停用：登录与续期被拒绝，数据完整保留，可恢复");
        }

        String state = resolveState(tenant, reachable, authReady, migrationFailure, lastJob,
                backupState);
        return new TenantHealthDtos.HealthView(tenant.code(), tenant.name(), tenant.status(),
                tenant.instanceCode(), reachable, latency, authVersion, authReady,
                migrationFailure, latest.size(), lastMigrationAt, users,
                activeUsers, lastJob == null ? null : lastJob.status(),
                lastJob == null ? null : lastJob.startedAt(),
                backupState, backupBucket, backupLastFullAt, backupPitrDays,
                backupArchivedCount, backupLastArchivedAt, state, issues, Instant.now());
    }

    private static String resolveState(Tenant tenant, boolean reachable, boolean authReady,
                                       boolean migrationFailure, Provisioning.Job lastJob,
                                       String backupState) {
        if (Tenant.CLOSED.equals(tenant.status())) {
            return "CLOSED";
        }
        if (Tenant.SUSPENDED.equals(tenant.status())) {
            return "SUSPENDED";
        }
        if (Tenant.PENDING.equals(tenant.status())) {
            return "PENDING";
        }
        if (tenant.dbHost() == null || tenant.dbHost().isBlank()) {
            return "DEGRADED";
        }
        if (!reachable) {
            return "DOWN";
        }
        if (!authReady || migrationFailure
                || (lastJob != null && Provisioning.FAILED.equals(lastJob.status()))) {
            return "DEGRADED";
        }
        // §286：备份退化也要进"需关注"——没有可恢复备份的院，不能显示为"一切正常"
        if ("DEGRADED".equals(backupState) || "FAILED".equals(backupState)) {
            return "DEGRADED";
        }
        return "OK";
    }

    private TenantHealthDtos.HealthView timeoutView(Tenant tenant) {
        return new TenantHealthDtos.HealthView(tenant.code(), tenant.name(), tenant.status(),
                tenant.instanceCode(), false, null, null, false, false, 0, null, 0, 0,
                null, null, "UNKNOWN", null, null, 0, 0, null, "DOWN",
                List.of("健康检查超时（>" + PER_TENANT_TIMEOUT_SECONDS + " 秒）：该院 Cell 可能离线"),
                Instant.now());
    }

    private TenantHealthDtos.HealthView failedView(Tenant tenant) {
        return new TenantHealthDtos.HealthView(tenant.code(), tenant.name(), tenant.status(),
                tenant.instanceCode(), false, null, null, false, false, 0, null, 0, 0,
                null, null, "UNKNOWN", null, null, 0, 0, null, "DEGRADED",
                List.of("健康检查异常：不影响其他院，请查看控制面日志"), Instant.now());
    }

    @PreDestroy
    void shutdown() {
        pool.shutdownNow();
    }
}
