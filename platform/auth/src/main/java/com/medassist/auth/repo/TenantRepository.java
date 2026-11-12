package com.medassist.auth.repo;

import com.medassist.auth.domain.Tenant;
import com.medassist.auth.domain.TenantQuotas;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.sql.ResultSet;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 租户台账仓储（JdbcClient 显式 SQL）。
 *
 * <p>写法约束与同域其他仓储一致：写操作走 version CAS，返回受影响行数由调用方判定冲突；
 * 审计写入 {@code auth_svc.change_log}（target_type = TENANT），由数据库触发器保证只追加。
 *
 * <p>jsonb 处理：配额以文本写入 {@code CAST(:quotas AS jsonb)}，读取时反序列化；
 * 反序列化失败按"配额未知"处理（不让一条脏元数据把整个列表打成 500），并保留原始文本供排障。
 */
@Repository
public class TenantRepository {

    private static final String COLS = """
            code, instance_code, name, edition_code, status, cell_endpoint, db_host, db_name, region,
            quotas::text AS quotas, contact_name, contact_phone, notes,
            closed_at, closed_reason, retention_until,
            version, created_by, created_at, updated_at
            """;

    private final JdbcClient jdbc;
    private final ObjectMapper objectMapper;

    public TenantRepository(JdbcClient jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    private final RowMapper<Tenant> mapper = (ResultSet rs, int rowNum) -> new Tenant(
            rs.getString("code"),
            rs.getString("instance_code"),
            rs.getString("name"),
            rs.getString("edition_code"),
            rs.getString("status"),
            rs.getString("cell_endpoint"),
            rs.getString("db_host"),
            rs.getString("db_name"),
            rs.getString("region"),
            parseQuotas(rs.getString("quotas")),
            rs.getString("contact_name"),
            rs.getString("contact_phone"),
            rs.getString("notes"),
            rs.getTimestamp("closed_at") == null ? null : rs.getTimestamp("closed_at").toInstant(),
            rs.getString("closed_reason"),
            rs.getDate("retention_until") == null ? null : rs.getDate("retention_until").toLocalDate(),
            rs.getLong("version"),
            rs.getString("created_by"),
            rs.getTimestamp("created_at").toInstant(),
            rs.getTimestamp("updated_at").toInstant()
    );

    public List<Tenant> list(String status) {
        StringBuilder sql = new StringBuilder("SELECT " + COLS + " FROM auth_svc.tenant");
        if (status != null && !status.isBlank()) {
            sql.append(" WHERE status = :status");
        }
        sql.append(" ORDER BY created_at, code");
        JdbcClient.StatementSpec spec = jdbc.sql(sql.toString());
        if (status != null && !status.isBlank()) {
            spec = spec.param("status", status);
        }
        return spec.query(mapper).list();
    }

    public Optional<Tenant> findByCode(String code) {
        return jdbc.sql("SELECT " + COLS + " FROM auth_svc.tenant WHERE code = ?")
                .param(code)
                .query(mapper)
                .optional();
    }

    public boolean existsByCode(String code) {
        return jdbc.sql("SELECT count(*) FROM auth_svc.tenant WHERE lower(code) = lower(?)")
                .param(code).query(Long.class).single() > 0;
    }

    public boolean existsByInstanceCode(String instanceCode) {
        return jdbc.sql("SELECT count(*) FROM auth_svc.tenant WHERE lower(instance_code) = lower(?)")
                .param(instanceCode).query(Long.class).single() > 0;
    }

    public void insert(Tenant t) {
        jdbc.sql("""
                        INSERT INTO auth_svc.tenant
                            (code, instance_code, name, edition_code, status, cell_endpoint, db_host,
                             db_name, region, quotas, contact_name, contact_phone, notes, version, created_by)
                        VALUES (:code, :instanceCode, :name, :editionCode, :status, :cellEndpoint,
                                :dbHost, COALESCE(:dbName, 'medagent'), :region,
                                CAST(:quotas AS jsonb), :contactName,
                                :contactPhone, :notes, 0, :createdBy)
                        """)
                .param("code", t.code())
                .param("instanceCode", t.instanceCode())
                .param("name", t.name())
                .param("editionCode", t.editionCode())
                .param("status", t.status())
                .param("cellEndpoint", t.cellEndpoint())
                .param("dbHost", t.dbHost())
                .param("dbName", t.dbName())
                .param("region", t.region())
                .param("quotas", writeQuotas(t.quotas()))
                .param("contactName", t.contactName())
                .param("contactPhone", t.contactPhone())
                .param("notes", t.notes())
                .param("createdBy", t.createdBy())
                .update();
    }

    /**
     * 元数据更新（CAS）。
     *
     * <p>{@code code} 与 {@code instance_code} <b>不在</b>可更新列里：前者是审计与令牌声明主键，
     * 后者与授权书绑定。需要改实例码只能新开租户并重发授权。
     */
    public boolean update(Tenant t, long expectedVersion) {
        return jdbc.sql("""
                        UPDATE auth_svc.tenant
                           SET name = :name, edition_code = :editionCode, cell_endpoint = :cellEndpoint,
                               db_host = :dbHost, db_name = COALESCE(:dbName, db_name),
                               region = :region, quotas = CAST(:quotas AS jsonb),
                               contact_name = :contactName, contact_phone = :contactPhone,
                               notes = :notes, version = version + 1, updated_at = now()
                         WHERE code = :code AND version = :version
                        """)
                .param("name", t.name())
                .param("editionCode", t.editionCode())
                .param("cellEndpoint", t.cellEndpoint())
                .param("dbHost", t.dbHost())
                .param("dbName", t.dbName())
                .param("region", t.region())
                .param("quotas", writeQuotas(t.quotas()))
                .param("contactName", t.contactName())
                .param("contactPhone", t.contactPhone())
                .param("notes", t.notes())
                .param("code", t.code())
                .param("version", expectedVersion)
                .update() > 0;
    }

    /** 状态流转（CAS）；注销时同时落保留策略。返回 false 表示版本冲突或租户不存在。 */
    public boolean updateStatus(String code, String status, long expectedVersion,
                                java.time.Instant closedAt, String closedReason,
                                java.time.LocalDate retentionUntil) {
        return jdbc.sql("""
                        UPDATE auth_svc.tenant
                           SET status = :status,
                               closed_at = COALESCE(:closedAt, closed_at),
                               closed_reason = COALESCE(:closedReason, closed_reason),
                               retention_until = COALESCE(:retentionUntil, retention_until),
                               version = version + 1, updated_at = now()
                         WHERE code = :code AND version = :version
                        """)
                .param("status", status)
                .param("closedAt", closedAt == null ? null : java.sql.Timestamp.from(closedAt))
                .param("closedReason", closedReason)
                .param("retentionUntil", retentionUntil == null ? null : java.sql.Date.valueOf(retentionUntil))
                .param("code", code)
                .param("version", expectedVersion)
                .update() > 0;
    }

    /** 该 Cell 数据库是否已被其他租户占用（一院一库的硬约束，跳过自身编码）。 */
    public boolean cellDatabaseTaken(String dbHost, String dbName, String excludeCode) {
        if (dbHost == null || dbHost.isBlank() || dbName == null || dbName.isBlank()) {
            return false;
        }
        return jdbc.sql("""
                        SELECT count(*) FROM auth_svc.tenant
                         WHERE lower(db_host) = lower(?) AND db_name = ? AND code <> ?
                        """)
                .param(dbHost.trim())
                .param(dbName.trim())
                .param(excludeCode == null ? "" : excludeCode)
                .query(Long.class).single() > 0;
    }

    /**
     * Cell 数据库坐标（仅控制面运维用：迁移台账读取、开通编排）。
     *
     * <p>刻意不放进 {@link Tenant} 与前端视图：库名/主机属于基础设施信息，
     * 医院管理员不需要也不应该看到"自己这台机器的内网地址"。
     */
    public Optional<CellDatabase> findCellDatabase(String tenantCode) {
        return jdbc.sql("SELECT db_host, db_name FROM auth_svc.tenant WHERE code = ?")
                .param(tenantCode)
                .query((rs, i) -> new CellDatabase(rs.getString("db_host"), rs.getString("db_name")))
                .optional();
    }

    public long countUsers(String tenantCode) {
        return jdbc.sql("SELECT count(*) FROM auth_svc.app_user WHERE tenant_code = ?")
                .param(tenantCode).query(Long.class).single();
    }

    public long countActiveUsers(String tenantCode) {
        return jdbc.sql("SELECT count(*) FROM auth_svc.app_user WHERE tenant_code = ? AND status = 'ACTIVE'")
                .param(tenantCode).query(Long.class).single();
    }

    /**
     * 停用/注销时撤销该租户全部刷新令牌。
     *
     * <p>访问令牌是无状态的，无法逐条作废；但续期入口必须立刻关掉，
     * 否则被停用医院的客户端可以在 7 天内无限续签新令牌（停用等于没停）。
     */
    public int revokeRefreshTokensOfTenant(String tenantCode) {
        return jdbc.sql("""
                        UPDATE auth_svc.refresh_token
                           SET revoked_at = now()
                         WHERE revoked_at IS NULL
                           AND user_id IN (SELECT id FROM auth_svc.app_user WHERE tenant_code = :code)
                        """)
                .param("code", tenantCode)
                .update();
    }

    public void logChange(String actor, String action, String targetCode, String detailJson) {
        jdbc.sql("""
                        INSERT INTO auth_svc.change_log (actor, action, target_type, target_id, detail)
                        VALUES (:actor, :action, 'TENANT', :targetId, CAST(:detail AS jsonb))
                        """)
                .param("actor", actor)
                .param("action", action)
                .param("targetId", targetCode)
                .param("detail", detailJson == null ? "{}" : detailJson)
                .update();
    }

    /** 某租户最近的变更记录（详情页用；列表页不查，避免 N+1）。 */
    public List<ChangeEntry> recentChanges(String tenantCode, int limit) {
        return jdbc.sql("""
                        SELECT ts, actor, action, detail::text AS detail
                          FROM auth_svc.change_log
                         WHERE target_type = 'TENANT' AND target_id = ?
                         ORDER BY id DESC
                         LIMIT ?
                        """)
                .param(tenantCode)
                .param(limit)
                .query((rs, i) -> new ChangeEntry(
                        rs.getTimestamp("ts").toInstant(),
                        rs.getString("actor"),
                        rs.getString("action"),
                        rs.getString("detail")))
                .list();
    }

    private TenantQuotas parseQuotas(String raw) {
        if (raw == null || raw.isBlank()) {
            return TenantQuotas.empty();
        }
        try {
            TenantQuotas parsed = objectMapper.readValue(raw, TenantQuotas.class);
            return parsed == null ? TenantQuotas.empty() : parsed;
        } catch (JacksonException e) {
            // 脏元数据不应把租户列表打成 500：按"未配置"展示，原文仍可从数据库排障
            return TenantQuotas.empty();
        }
    }

    private String writeQuotas(TenantQuotas quotas) {
        try {
            return objectMapper.writeValueAsString(quotas == null ? TenantQuotas.empty() : quotas);
        } catch (JacksonException e) {
            return "{}";
        }
    }

    /** 变更记录视图。 */
    public record ChangeEntry(Instant ts, String actor, String action, String detail) {
    }

    /** Cell 数据库坐标：{@code db_host} 允许写成 {@code host:port} 或只有 host。 */
    public record CellDatabase(String dbHost, String dbName) {
    }
}
