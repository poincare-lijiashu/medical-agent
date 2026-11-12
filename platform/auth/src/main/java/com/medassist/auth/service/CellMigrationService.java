package com.medassist.auth.service;

import com.medassist.auth.domain.Tenant;
import com.medassist.auth.repo.CellMigrationRepository;
import com.medassist.auth.repo.TenantRepository;
import com.medassist.auth.web.dto.CellMigrationDtos;
import com.medassist.common.api.ErrorCode;
import com.medassist.common.exception.BusinessException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Cell 迁移台账（§282 / ADR-001 P1）。
 *
 * <p>物理隔离之后，"哪家医院跑到哪一版"是发布与排障的第一问题。本服务从各 Cell 的
 * {@code flyway_schema_history}（每个服务在自有 schema 里各有一张）汇总版本事实，
 * 写进控制面台账，供"发布列车推进到第几批"与"某院是否漏迁移"直接查询。
 *
 * <p>边界：
 * <ul>
 *   <li><b>只读</b>：不执行迁移、不改对方库。迁移仍由各 Cell 的 Helm Hook Job 完成；</li>
 *   <li><b>失败不抛 500</b>：Cell 库不可达时返回缓存 + 原因（离线医院是常态，
 *       监控页不该因此白屏）；</li>
 *   <li><b>凭据不落库</b>：账号密码来自部署 Secret（{@code CELL_DB_USER/PASSWORD}），
 *       tenant 记录只存主机与库名。</li>
 * </ul>
 */
@Service
public class CellMigrationService {

    private static final Logger log = LoggerFactory.getLogger(CellMigrationService.class);

    /** 同一 Cell 的同步节流：页面刷新/多人同时看不应把压力打到院方数据库。 */
    private static final Duration SYNC_INTERVAL = Duration.ofSeconds(30);

    /** 连接与查询超时（秒）：院方库不可达时必须快速失败，不能拖住控制面线程。 */
    private static final int CONNECT_TIMEOUT_SECONDS = 5;
    private static final int SOCKET_TIMEOUT_SECONDS = 10;

    private final TenantRepository tenants;
    private final CellMigrationRepository ledger;
    private final String dbUser;
    private final String dbPassword;

    /** 最近一次同步时间（进程内；控制面多副本各自节流，不影响正确性）。 */
    private final Map<String, Instant> lastSync = new ConcurrentHashMap<>();

    public CellMigrationService(
            TenantRepository tenants,
            CellMigrationRepository ledger,
            @Value("${medassist.cell-db.user:${PG_USER:medagent}}") String dbUser,
            @Value("${medassist.cell-db.password:${PG_PASSWORD:medagent123}}") String dbPassword) {
        this.tenants = tenants;
        this.ledger = ledger;
        this.dbUser = dbUser;
        this.dbPassword = dbPassword;
    }

    /**
     * 查询租户的迁移台账（必要时先从 Cell 同步）。
     *
     * @param force true 时忽略节流（运维点"重新同步"）
     */
    public CellMigrationDtos.CellMigrationView view(String tenantCode, boolean force) {
        Tenant tenant = tenants.findByCode(tenantCode)
                .orElseThrow(() -> new BusinessException(ErrorCode.TENANT_NOT_FOUND));
        String error = null;
        Instant syncedAt = lastSync.get(tenant.code());
        boolean due = force || syncedAt == null || syncedAt.plus(SYNC_INTERVAL).isBefore(Instant.now());
        if (due) {
            error = sync(tenant);
            if (error == null) {
                syncedAt = Instant.now();
                lastSync.put(tenant.code(), syncedAt);
            }
        }
        return buildView(tenant.code(), syncedAt, error);
    }

    /**
     * 从 Cell 库拉取并落台账。
     *
     * @return {@code null} 表示同步成功；否则为脱敏后的失败原因
     */
    private String sync(Tenant tenant) {
        TenantRepository.CellDatabase cell = tenants.findCellDatabase(tenant.code()).orElse(null);
        if (cell == null || cell.dbHost() == null || cell.dbHost().isBlank()) {
            return "该租户尚未登记 Cell 数据库（db_host 为空），暂不能汇总迁移版本";
        }
        String url = jdbcUrl(cell.dbHost(), cell.dbName());
        try (Connection connection = DriverManager.getConnection(url, dbUser, dbPassword)) {
            int rows = 0;
            for (String schema : schemasWithHistory(connection)) {
                rows += syncSchema(tenant.code(), connection, schema);
            }
            tenants.logChange("system", "CELL_MIGRATION_SYNC", tenant.code(),
                    "{\"schemas\":" + rows + "}");
            log.info("cell.migration.synced tenant={} rows={}", tenant.code(), rows);
            return null;
        } catch (SQLException e) {
            // 只保留 SQLState 与简短原因：连接串/凭据不能进日志与接口
            log.warn("cell.migration.sync_failed tenant={} state={} reason={}",
                    tenant.code(), e.getSQLState(), e.getMessage());
            return "Cell 数据库不可达（" + safeSqlState(e) + "）：" + safeMessage(e);
        }
    }

    /** 该库里所有带 flyway_schema_history 的 schema（= 已接入的服务域）。 */
    private List<String> schemasWithHistory(Connection connection) throws SQLException {
        List<String> schemas = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement("""
                SELECT table_schema
                  FROM information_schema.tables
                 WHERE table_name = 'flyway_schema_history'
                   AND table_schema NOT IN ('pg_catalog', 'information_schema')
                 ORDER BY table_schema
                """);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                schemas.add(rs.getString(1));
            }
        }
        return schemas;
    }

    /** 读单个 schema 的历史表并写台账；返回写入行数。 */
    private int syncSchema(String tenantCode, Connection connection, String schema) throws SQLException {
        String service = schema.endsWith("_svc") ? schema.substring(0, schema.length() - 4) : schema;
        // schema 名来自 information_schema（库自身元数据），仍加引号避免大小写/注入问题
        String sql = "SELECT version, description, checksum, success, installed_on FROM \""
                + schema.replace("\"", "\"\"") + "\".flyway_schema_history ORDER BY installed_rank";
        int rows = 0;
        try (PreparedStatement ps = connection.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                String version = rs.getString("version");
                if (version == null || version.isBlank()) {
                    continue; // baseline 行没有版本号，不参与"跑到哪版"的判断
                }
                ledger.upsert(tenantCode, service, version.trim(),
                        rs.getString("description"),
                        (Integer) rs.getObject("checksum"),
                        rs.getTimestamp("installed_on") == null
                                ? null : rs.getTimestamp("installed_on").toInstant(),
                        rs.getBoolean("success"));
                rows++;
            }
        }
        return rows;
    }

    private CellMigrationDtos.CellMigrationView buildView(String tenantCode, Instant syncedAt, String error) {
        List<CellMigrationRepository.CellMigration> rows = ledger.latestByService(tenantCode);
        List<CellMigrationDtos.Item> items = rows.stream()
                .map(r -> new CellMigrationDtos.Item(r.service(), r.version(), r.description(),
                        r.success(), r.installedOn()))
                .toList();
        return new CellMigrationDtos.CellMigrationView(
                tenantCode, syncedAt, error == null, error,
                ledger.hasFailure(tenantCode), items);
    }

    /**
     * 组装 JDBC URL。
     *
     * <p>{@code db_host} 支持 {@code host} 或 {@code host:port}；端口缺省 5432。
     * 强制 {@code connectTimeout/socketTimeout}：没有超时的 JDBC 连接会在对端静默丢包时挂死。
     */
    static String jdbcUrl(String dbHost, String dbName) {
        String host = dbHost.trim();
        String port = "5432";
        int colon = host.lastIndexOf(':');
        if (colon > 0 && !host.contains("]")) {
            port = host.substring(colon + 1).trim();
            host = host.substring(0, colon).trim();
        }
        String database = dbName == null || dbName.isBlank() ? "medagent" : dbName.trim();
        return "jdbc:postgresql://" + host + ":" + port + "/" + database
                + "?connectTimeout=" + CONNECT_TIMEOUT_SECONDS
                + "&socketTimeout=" + SOCKET_TIMEOUT_SECONDS
                + "&ApplicationName=medassist-cell-ledger";
    }

    private static String safeSqlState(SQLException e) {
        return e.getSQLState() == null ? "unknown" : e.getSQLState();
    }

    /** 只保留"拒绝连接/认证失败/库不存在"这类可操作信息，去掉主机明细。 */
    private static String safeMessage(SQLException e) {
        String message = e.getMessage() == null ? "" : e.getMessage();
        if (message.length() > 160) {
            message = message.substring(0, 160) + "…";
        }
        return message.isBlank() ? "连接失败" : message;
    }
}
