package com.medassist.auth.service;

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
}
