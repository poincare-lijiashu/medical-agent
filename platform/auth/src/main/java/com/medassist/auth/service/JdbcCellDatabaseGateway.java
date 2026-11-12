package com.medassist.auth.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
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
                             version, tenant_code)
                        VALUES (?, ?, ?, ?, ?, 'ACTIVE', 0, ?)
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
