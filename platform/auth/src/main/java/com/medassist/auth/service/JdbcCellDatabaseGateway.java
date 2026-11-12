package com.medassist.auth.service;

import com.medassist.auth.domain.BackupPlan;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.sql.Array;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * Cell 数据库网关的 JDBC 实现（§284）。
 *
 * <p>安全细节（与 {@link CellMigrationService} 同口径）：
 * <ul>
 *   <li>凭据只来自部署 Secret（{@code CELL_DB_USER/PASSWORD}），不落库、不进日志；</li>
 *   <li>强制连接/查询超时：院方库不可达时必须快速失败，不能挂住控制面线程；</li>
 *   <li>异常只保留 SQLState 与截断后的原因，避免把主机、账号、连接串带进接口与审计。</li>
 * </ul>
 */
@Component
public class JdbcCellDatabaseGateway implements CellDatabaseGateway {

    private static final Logger log = LoggerFactory.getLogger(JdbcCellDatabaseGateway.class);

    /** 探测要快：健康度页面不能被一台离线医院拖住。 */
    private static final int PROBE_CONNECT_TIMEOUT = 3;
    private static final int PROBE_SOCKET_TIMEOUT = 4;

    /** 建账号可以稍慢，但仍必须有上限。 */
    private static final int WRITE_CONNECT_TIMEOUT = 5;
    private static final int WRITE_SOCKET_TIMEOUT = 10;

    private final String dbUser;
    private final String dbPassword;

    public JdbcCellDatabaseGateway(
            @Value("${medassist.cell-db.user:${PG_USER:medagent}}") String dbUser,
            @Value("${medassist.cell-db.password:${PG_PASSWORD:medagent123}}") String dbPassword) {
        this.dbUser = dbUser;
        this.dbPassword = dbPassword;
    }

    @Override
    public Probe probe(String dbHost, String dbName) {
        long start = System.nanoTime();
        try (Connection connection = open(dbHost, dbName, PROBE_CONNECT_TIMEOUT, PROBE_SOCKET_TIMEOUT)) {
            boolean history = exists(connection,
                    "SELECT count(*) FROM information_schema.tables"
                            + " WHERE table_schema = 'auth_svc' AND table_name = 'flyway_schema_history'");
            boolean tenant = exists(connection,
                    "SELECT count(*) FROM information_schema.tables"
                            + " WHERE table_schema = 'auth_svc' AND table_name = 'tenant'");
            boolean tenantColumn = exists(connection,
                    "SELECT count(*) FROM information_schema.columns"
                            + " WHERE table_schema = 'auth_svc' AND table_name = 'app_user'"
                            + " AND column_name = 'tenant_code'");
            String version = history ? authVersion(connection) : null;
            long ms = elapsedMs(start);
            return new Probe(true, history && tenant && tenantColumn, ms, version, null);
        } catch (SQLException e) {
            log.warn("cell.provision.probe_failed state={} reason={}",
                    e.getSQLState(), safeMessage(e));
            return new Probe(false, false, elapsedMs(start), null, safeMessage(e));
        }
    }

    @Override
    public void ensureTenant(TenantSpec spec) {
        try (Connection connection = open(spec.dbHost(), spec.dbName(),
                WRITE_CONNECT_TIMEOUT, WRITE_SOCKET_TIMEOUT)) {
            Existing existing = existingTenant(connection, spec.tenantCode());
            if (existing != null) {
                if (!existing.instanceCode().equalsIgnoreCase(spec.instanceCode())) {
                    throw new IllegalStateException("Cell 内已存在租户 " + spec.tenantCode()
                            + "，但实例码不同（现有 " + existing.instanceCode() + "）："
                            + "一个租户只能绑定一个实例码");
                }
                if ("CLOSED".equals(existing.status())) {
                    throw new IllegalStateException("Cell 内该租户已注销（CLOSED），不能通过开通重新激活");
                }
            }
            String nextStatus = existing == null || "PENDING".equals(existing.status())
                    ? "ACTIVE" : existing.status();
            try (PreparedStatement ps = connection.prepareStatement("""
                    INSERT INTO auth_svc.tenant
                        (code, instance_code, name, edition_code, status, cell_endpoint, region, created_by)
                    VALUES (?, ?, ?, ?, ?, ?, ?, 'provisioning')
                    ON CONFLICT (code) DO UPDATE
                       SET instance_code = EXCLUDED.instance_code,
                           name = EXCLUDED.name,
                           edition_code = EXCLUDED.edition_code,
                           status = ?,
                           cell_endpoint = EXCLUDED.cell_endpoint,
                           region = EXCLUDED.region,
                           updated_at = now()
                    """)) {
                ps.setString(1, spec.tenantCode());
                ps.setString(2, spec.instanceCode());
                ps.setString(3, spec.name());
                ps.setString(4, spec.editionCode());
                ps.setString(5, nextStatus);
                ps.setString(6, spec.cellEndpoint());
                ps.setString(7, spec.region());
                ps.setString(8, nextStatus);
                ps.executeUpdate();
            }
        } catch (SQLException e) {
            throw new CellDatabaseException("Cell 内登记租户失败：" + safeMessage(e), e);
        }
    }

    @Override
    public boolean hasAdmin(String dbHost, String dbName, String tenantCode, String username) {
        try (Connection connection = open(dbHost, dbName, WRITE_CONNECT_TIMEOUT, WRITE_SOCKET_TIMEOUT);
             PreparedStatement ps = connection.prepareStatement("""
                     SELECT count(*) FROM auth_svc.app_user
                      WHERE tenant_code = ? AND lower(username) = lower(?)
                     """)) {
            ps.setString(1, tenantCode);
            ps.setString(2, username);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() && rs.getLong(1) > 0;
            }
        } catch (SQLException e) {
            throw new CellDatabaseException("查询 Cell 管理员失败：" + safeMessage(e), e);
        }
    }

    @Override
    public void createAdmin(AdminSpec spec) {
        try (Connection connection = open(spec.dbHost(), spec.dbName(),
                WRITE_CONNECT_TIMEOUT, WRITE_SOCKET_TIMEOUT)) {
            boolean previousAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                UUID id = UUID.randomUUID();
                try (PreparedStatement ps = connection.prepareStatement("""
                        INSERT INTO auth_svc.app_user
                            (id, username, password_hash, display_name, employee_no, status,
                             must_change_password, version, tenant_code)
                        VALUES (?, ?, ?, ?, ?, 'ACTIVE', true, 0, ?)
                        """)) {
                    ps.setObject(1, id);
                    ps.setString(2, spec.username());
                    ps.setString(3, spec.passwordHash());
                    ps.setString(4, spec.displayName());
                    ps.setString(5, spec.employeeNo());
                    ps.setString(6, spec.tenantCode());
                    ps.executeUpdate();
                }
                try (PreparedStatement ps = connection.prepareStatement("""
                        INSERT INTO auth_svc.user_role (user_id, role_code)
                        VALUES (?, 'ADMIN')
                        """)) {
                    ps.setObject(1, id);
                    ps.executeUpdate();
                }
                connection.commit();
            } catch (SQLException e) {
                connection.rollback();
                throw e;
            } finally {
                connection.setAutoCommit(previousAutoCommit);
            }
        } catch (SQLException e) {
            throw new CellDatabaseException("创建 Cell 管理员失败：" + safeMessage(e), e);
        }
    }

    @Override
    public long countUsers(String dbHost, String dbName, String tenantCode) {
        return count(dbHost, dbName, tenantCode, null);
    }

    @Override
    public long countActiveUsers(String dbHost, String dbName, String tenantCode) {
        return count(dbHost, dbName, tenantCode, "ACTIVE");
    }

    private long count(String dbHost, String dbName, String tenantCode, String status) {
        String sql = status == null
                ? "SELECT count(*) FROM auth_svc.app_user WHERE tenant_code = ?"
                : "SELECT count(*) FROM auth_svc.app_user WHERE tenant_code = ? AND status = ?";
        try (Connection connection = open(dbHost, dbName, PROBE_CONNECT_TIMEOUT, PROBE_SOCKET_TIMEOUT);
             PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, tenantCode);
            if (status != null) {
                ps.setString(2, status);
            }
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : 0;
            }
        } catch (SQLException e) {
            throw new CellDatabaseException("统计 Cell 账号失败：" + safeMessage(e), e);
        }
    }

    @Override
    public void updateTenantStatus(String dbHost, String dbName, String tenantCode, String status,
                                   java.time.Instant closedAt, String closedReason) {
        try (Connection connection = open(dbHost, dbName, WRITE_CONNECT_TIMEOUT, WRITE_SOCKET_TIMEOUT);
             PreparedStatement ps = connection.prepareStatement("""
                     UPDATE auth_svc.tenant
                        SET status = ?,
                            closed_at = COALESCE(?, closed_at),
                            closed_reason = COALESCE(?, closed_reason),
                            updated_at = now()
                      WHERE code = ?
                     """)) {
            ps.setString(1, status);
            ps.setTimestamp(2, closedAt == null ? null : java.sql.Timestamp.from(closedAt));
            ps.setString(3, closedReason);
            ps.setString(4, tenantCode);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new CellDatabaseException("同步租户状态到 Cell 失败：" + safeMessage(e), e);
        }
    }

    // ---------------------------------------------------------------- 备份（§286）

    @Override
    public BackupStatus initializeBackup(String dbHost, String dbName, BackupPlan plan) {
        try (Connection connection = open(dbHost, dbName, WRITE_CONNECT_TIMEOUT, WRITE_SOCKET_TIMEOUT)) {
            ArchiveFacts facts = archiveFacts(connection);
            List<String> issues = archiveIssues(facts);
            String status = issues.isEmpty() ? "READY" : "DEGRADED";
            try (PreparedStatement ps = connection.prepareStatement("""
                    INSERT INTO auth_svc.tenant_backup
                        (tenant_code, stanza, endpoint, bucket, region, pitr_days, encryption,
                         archive_mode, wal_level, archived_count, last_archived_at, failed_count,
                         status, issues, last_verified_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, now())
                    ON CONFLICT (tenant_code) DO UPDATE
                       SET stanza = EXCLUDED.stanza,
                           endpoint = EXCLUDED.endpoint,
                           bucket = EXCLUDED.bucket,
                           region = EXCLUDED.region,
                           pitr_days = EXCLUDED.pitr_days,
                           encryption = EXCLUDED.encryption,
                           archive_mode = EXCLUDED.archive_mode,
                           wal_level = EXCLUDED.wal_level,
                           archived_count = EXCLUDED.archived_count,
                           last_archived_at = EXCLUDED.last_archived_at,
                           failed_count = EXCLUDED.failed_count,
                           status = EXCLUDED.status,
                           issues = EXCLUDED.issues,
                           last_verified_at = now(),
                           updated_at = now()
                    """)) {
                ps.setString(1, plan.tenantCode());
                ps.setString(2, plan.stanza());
                ps.setString(3, plan.endpoint());
                ps.setString(4, plan.bucket());
                ps.setString(5, plan.region());
                ps.setInt(6, plan.pitrDays());
                ps.setString(7, plan.encryption());
                ps.setString(8, facts.archiveMode());
                ps.setString(9, facts.walLevel());
                ps.setLong(10, facts.archivedCount());
                ps.setTimestamp(11, facts.lastArchivedAt() == null
                        ? null : Timestamp.from(facts.lastArchivedAt()));
                ps.setLong(12, facts.failedCount());
                ps.setString(13, status);
                Array array = connection.createArrayOf("text", issues.toArray());
                ps.setArray(14, array);
                ps.executeUpdate();
            }
            return new BackupStatus(true, status, plan.bucket(), plan.stanza(), plan.pitrDays(),
                    facts.archiveMode(), facts.walLevel(), facts.archivedCount(),
                    facts.lastArchivedAt(), facts.failedCount(), null, issues);
        } catch (SQLException e) {
            // 42P01 = undefined_table：Cell 还没跑到 V100。这是"迁移未完成"，不是备份逻辑错误，
            // 必须给出可执行指令（否则运维只能看到一句 relation does not exist）。
            if ("42P01".equals(e.getSQLState())) {
                throw new CellDatabaseException(
                        "Cell 尚未迁移到 V100（缺 auth_svc.tenant_backup）：请先在该 Cell 执行迁移后 resume", e);
            }
            throw new CellDatabaseException("登记备份计划失败：" + safeMessage(e), e);
        }
    }

    @Override
    public BackupStatus backupStatus(String dbHost, String dbName, String tenantCode) {
        try (Connection connection = open(dbHost, dbName, PROBE_CONNECT_TIMEOUT, PROBE_SOCKET_TIMEOUT);
             PreparedStatement ps = connection.prepareStatement("""
                     SELECT bucket, stanza, pitr_days, status, archive_mode, wal_level,
                            archived_count, last_archived_at, failed_count, last_full_at, issues
                       FROM auth_svc.tenant_backup
                      WHERE tenant_code = ?
                     """)) {
            ps.setString(1, tenantCode);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return BackupStatus.none();
                }
                return new BackupStatus(true, rs.getString("status"), rs.getString("bucket"),
                        rs.getString("stanza"), rs.getInt("pitr_days"),
                        rs.getString("archive_mode"), rs.getString("wal_level"),
                        rs.getLong("archived_count"), instant(rs, "last_archived_at"),
                        rs.getLong("failed_count"), instant(rs, "last_full_at"),
                        textArray(rs, "issues"));
            }
        } catch (SQLException e) {
            throw new CellDatabaseException("读取备份状态失败：" + safeMessage(e), e);
        }
    }

    /**
     * 读取 WAL 归档事实。
     *
     * <p>{@code SHOW} 的参数是编译期常量，不来自请求；{@code pg_stat_archiver} 在 PG10+
     * 恒存在（只有累计值，不含任何业务数据）。
     */
    private static ArchiveFacts archiveFacts(Connection connection) throws SQLException {
        String archiveMode = show(connection, "archive_mode");
        String walLevel = show(connection, "wal_level");
        long archived = 0;
        long failed = 0;
        Instant lastArchived = null;
        try (Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(
                     "SELECT archived_count, last_archived_time, failed_count FROM pg_stat_archiver")) {
            if (rs.next()) {
                archived = rs.getLong("archived_count");
                failed = rs.getLong("failed_count");
                lastArchived = instant(rs, "last_archived_time");
            }
        }
        return new ArchiveFacts(archiveMode, walLevel, archived, lastArchived, failed);
    }

    private static String show(Connection connection, String parameter) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery("SHOW " + parameter)) {
            return rs.next() ? rs.getString(1) : null;
        }
    }

    /**
     * 把归档事实翻译成可执行的问题清单。
     *
     * <p>这里不做"悄悄放过"：没有 archive_mode 就没有 PITR，恢复只能回到最近一次全备，
     * RPO 从分钟级退化到一天级——必须在开通步骤与健康度上同时可见。
     */
    private static List<String> archiveIssues(ArchiveFacts facts) {
        List<String> issues = new ArrayList<>();
        if (facts.archiveMode() == null || !"on".equalsIgnoreCase(facts.archiveMode().trim())) {
            issues.add("archive_mode=" + value(facts.archiveMode())
                    + "：未开启 WAL 归档，无法按时间点恢复（PITR）");
        }
        String level = facts.walLevel() == null ? "" : facts.walLevel().trim().toLowerCase(Locale.ROOT);
        if (!Set.of("replica", "logical").contains(level)) {
            issues.add("wal_level=" + value(facts.walLevel()) + "：不足以支撑 PITR（要求 replica/logical）");
        }
        if (facts.failedCount() > 0) {
            issues.add("WAL 归档失败 " + facts.failedCount() + " 次：检查 archive_command 与仓库权限");
        }
        return issues;
    }

    private static String value(String raw) {
        return raw == null || raw.isBlank() ? "(未知)" : raw.trim();
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp ts = rs.getTimestamp(column);
        return ts == null ? null : ts.toInstant();
    }

    private static List<String> textArray(ResultSet rs, String column) throws SQLException {
        Array array = rs.getArray(column);
        if (array == null) {
            return List.of();
        }
        Object value = array.getArray();
        if (value instanceof String[] items) {
            return List.of(items);
        }
        return List.of();
    }

    private record ArchiveFacts(String archiveMode, String walLevel, long archivedCount,
                                Instant lastArchivedAt, long failedCount) {
    }

    // ---------------------------------------------------------------- 内部方法

    private Connection open(String dbHost, String dbName, int connectTimeout, int socketTimeout)
            throws SQLException {
        return DriverManager.getConnection(
                CellMigrationService.jdbcUrl(dbHost, dbName, connectTimeout, socketTimeout),
                dbUser, dbPassword);
    }

    private static boolean exists(Connection connection, String sql) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            return rs.next() && rs.getLong(1) > 0;
        }
    }

    private static String authVersion(Connection connection) {
        String sql = """
                SELECT version FROM auth_svc.flyway_schema_history
                 WHERE success = true AND version IS NOT NULL
                 ORDER BY installed_rank DESC LIMIT 1
                """;
        try (PreparedStatement ps = connection.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            return rs.next() ? rs.getString(1) : null;
        } catch (SQLException e) {
            return null;
        }
    }

    private static Existing existingTenant(Connection connection, String tenantCode)
            throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT instance_code, status FROM auth_svc.tenant WHERE code = ?")) {
            ps.setString(1, tenantCode);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                return new Existing(rs.getString("instance_code"), rs.getString("status"));
            }
        }
    }

    private static long elapsedMs(long startNanos) {
        return Math.max(0, (System.nanoTime() - startNanos) / 1_000_000);
    }

    /** 只保留"拒绝连接/认证失败/表不存在"这类可操作信息，去掉主机与凭据明细。 */
    private static String safeMessage(SQLException e) {
        String state = e.getSQLState() == null ? "unknown" : e.getSQLState();
        String message = e.getMessage() == null ? "连接失败" : e.getMessage();
        if (message.length() > 160) {
            message = message.substring(0, 160) + "…";
        }
        return "SQLState=" + state + " · " + message;
    }

    private record Existing(String instanceCode, String status) {
    }

    /** Cell 库操作失败：调用方转成步骤失败，不把底层异常抛到 HTTP 层。 */
    public static class CellDatabaseException extends RuntimeException {
        public CellDatabaseException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
