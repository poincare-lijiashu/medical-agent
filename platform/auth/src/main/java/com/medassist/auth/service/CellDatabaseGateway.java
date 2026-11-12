package com.medassist.auth.service;

import com.medassist.auth.domain.BackupPlan;

import java.time.Instant;
import java.util.List;

/**
 * Cell 数据库操作端口（§284）。
 *
 * <p>把"连院方库"收在一个接口后面，原因有三：
 * <ol>
 *   <li>开通编排要能被测试：没有真实第二套数据库时也能验证步骤顺序、幂等与失败恢复；</li>
 *   <li>凭据来源只有一处（部署 Secret），不会散落在业务代码里；</li>
 *   <li>连接超时、SQL 脱敏等安全细节集中实现，避免"某个新步骤忘了设超时"。</li>
 * </ol>
 */
public interface CellDatabaseGateway {

    /** 探测 Cell 库：可达性、auth 域结构是否就绪、耗时。 */
    Probe probe(String dbHost, String dbName);

    /** 在 Cell 库内登记/更新本租户（账号外键依赖该行）。 */
    void ensureTenant(TenantSpec spec);

    boolean hasAdmin(String dbHost, String dbName, String tenantCode, String username);

    /** 建首个管理员并授予 ADMIN 角色（幂等：已存在则调用方不应再调用）。 */
    void createAdmin(AdminSpec spec);

    long countUsers(String dbHost, String dbName, String tenantCode);

    long countActiveUsers(String dbHost, String dbName, String tenantCode);

    /**
     * 把控制面的状态变更同步到 Cell 内的租户台账（best-effort）。
     *
     * <p>为什么需要：如果 Cell 运行本地 auth（离线/低延迟部署），登录校验读的是
     * Cell 库里的 {@code auth_svc.tenant}。只改控制面会让"停用"在院内不生效。
     * 生产由共享控制面 auth 签发时，这一步是幂等的兜底。
     */
    void updateTenantStatus(String dbHost, String dbName, String tenantCode, String status,
                            java.time.Instant closedAt, String closedReason);

    /**
     * 在 Cell 内登记备份计划，并用该库的 WAL 归档事实判定 PITR 是否就绪（§286）。
     *
     * <p>为什么把判定放在 Cell：{@code archive_mode}/{@code wal_level}/{@code pg_stat_archiver}
     * 是这家医院自己的数据库事实，控制面台账只能记录"我们以为"，不能替代实测。
     */
    BackupStatus initializeBackup(String dbHost, String dbName, BackupPlan plan);

    /** 读取该院备份状态；未登记时返回 {@code present=false}（不是错误）。 */
    BackupStatus backupStatus(String dbHost, String dbName, String tenantCode);

    /**
     * 探测结果。
     *
     * @param reachable  能否建立连接并执行查询
     * @param authReady  auth 域是否已迁移（tenant 表、app_user.tenant_code、flyway 历史齐备）
     * @param authVersion auth 域最新迁移版本（仅展示用；字符串排序不代表语义）
     * @param latencyMs  一次往返耗时
     * @param error      脱敏后的失败原因（不含主机、账号、口令）
     */
    record Probe(boolean reachable, boolean authReady, long latencyMs, String authVersion, String error) {
    }

    record TenantSpec(String dbHost, String dbName, String tenantCode, String instanceCode,
                      String name, String editionCode, String cellEndpoint, String region) {
    }

    record AdminSpec(String dbHost, String dbName, String tenantCode, String username,
                     String displayName, String employeeNo, String passwordHash) {
    }

    /**
     * 备份状态（§286）。
     *
     * @param present        是否已登记备份计划
     * @param status         PENDING / READY / DEGRADED / FAILED；未登记为 NONE
     * @param archiveMode    PostgreSQL {@code archive_mode}
     * @param walLevel       PostgreSQL {@code wal_level}
     * @param lastArchivedAt 最近一次成功归档时间（PITR 新鲜度的直接证据）
     * @param issues         可读问题列表（未开启归档、归档失败、wal_level 不足等）
     */
    record BackupStatus(boolean present, String status, String bucket, String stanza, int pitrDays,
                        String archiveMode, String walLevel, long archivedCount,
                        Instant lastArchivedAt, long failedCount, Instant lastFullAt,
                        List<String> issues) {

        public static BackupStatus none() {
            return new BackupStatus(false, "NONE", null, null, 0, null, null, 0, null, 0, null,
                    List.of());
        }
    }
}
