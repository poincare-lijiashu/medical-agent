package com.medassist.license.repo;

import com.medassist.license.domain.LicenseModel.ChangeLog;
import com.medassist.license.domain.LicenseModel.Edition;
import com.medassist.license.domain.LicenseModel.License;
import com.medassist.license.domain.LicenseModel.Usage;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 授权域仓储（只读写 license_svc）。
 *
 * <p>不用 JPA：授权写入是低频管理动作，SQL 显式可读；审计为追加式，
 * 由数据库触发器兜底禁止 UPDATE/DELETE。
 *
 * <p>text[] 参数统一走 {@code CAST(? AS text[])} + PG 数组字面量：
 * 之前在其他服务踩过 {@code getObject(String[].class)} 与 pgjdbc Array 的两类坑，
 * 字面量最简单且类型显式。
 */
@Repository
public class LicenseRepository {

    private final JdbcTemplate jdbc;

    public LicenseRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /* ------------------------------------------------------------------ 版本矩阵 */

    public List<Edition> listEditions() {
        return jdbc.query("""
                SELECT code, name, description, modules, max_beds, max_users, max_ai_calls_per_day,
                       sort_no, status
                  FROM license_svc.lic_edition
                 WHERE status = 'ACTIVE'
                 ORDER BY sort_no, code
                """, EDITION);
    }

    /* ------------------------------------------------------------------ 授权书 */

    public Optional<License> findActive(String instanceCode) {
        return jdbc.query("""
                SELECT * FROM license_svc.lic_license
                 WHERE instance_code = ? AND status = 'ACTIVE'
                """, LICENSE, instanceCode).stream().findFirst();
    }

    public Optional<License> findById(UUID id) {
        return jdbc.query("SELECT * FROM license_svc.lic_license WHERE id = ?", LICENSE, id).stream().findFirst();
    }

    /** 该实例最近一次授权的任意状态（用于"已被吊销"时仍能展示原因，而不是退回"未授权"）。 */
    public Optional<License> findLatest(String instanceCode) {
        return jdbc.query("""
                SELECT * FROM license_svc.lic_license
                 WHERE instance_code = ?
                 ORDER BY imported_at DESC
                 LIMIT 1
                """, LICENSE, instanceCode).stream().findFirst();
    }

    public Optional<License> findByNo(String licenseNo) {
        return jdbc.query("SELECT * FROM license_svc.lic_license WHERE license_no = ?", LICENSE, licenseNo)
                .stream().findFirst();
    }

    public List<License> list(int limit) {
        return jdbc.query("""
                SELECT * FROM license_svc.lic_license
                 ORDER BY imported_at DESC
                 LIMIT ?
                """, LICENSE, Math.max(1, Math.min(limit, 200)));
    }

    /** 插入授权书；返回 false 表示授权编号重复（并发下由唯一约束兜底）。 */
    public boolean insert(License l) {
        try {
            jdbc.update("""
                    INSERT INTO license_svc.lic_license
                        (id, license_no, instance_code, hospital_name, edition_code, modules,
                         max_beds, max_users, max_ai_calls_per_day, not_before, expires_at,
                         grace_days, status, payload, payload_hash, signature, key_id, imported_by, issued_at)
                    VALUES (?, ?, ?, ?, ?, CAST(? AS text[]), ?, ?, ?, ?, ?, ?, 'ACTIVE', ?, ?, ?, ?, ?, ?)
                    """,
                    l.id(), l.licenseNo(), l.instanceCode(), l.hospitalName(), l.editionCode(),
                    pgTextArray(l.modules()), l.maxBeds(), l.maxUsers(), l.maxAiCallsPerDay(),
                    Timestamp.from(l.notBefore()), Timestamp.from(l.expiresAt()), l.graceDays(),
                    l.payload(), l.payloadHash(), l.signature(), l.keyId(), l.importedBy(),
                    Timestamp.from(l.issuedAt()));
            return true;
        } catch (DuplicateKeyException e) {
            return false;
        }
    }

    /** 把同实例上仍在生效的旧授权置为 SUPERSEDED（保留历史，不删除）。 */
    public int supersedeActive(String instanceCode, UUID keepId) {
        return jdbc.update("""
                UPDATE license_svc.lic_license
                   SET status = 'SUPERSEDED'
                 WHERE instance_code = ? AND status = 'ACTIVE' AND id <> ?
                """, instanceCode, keepId);
    }

    public int revoke(UUID id, String actor, String reason) {
        return jdbc.update("""
                UPDATE license_svc.lic_license
                   SET status = 'REVOKED', revoked_by = ?, revoked_at = now(), revoke_reason = ?
                 WHERE id = ? AND status <> 'REVOKED'
                """, actor, reason, id);
    }

    /* ------------------------------------------------------------------ 用量 */

    public void upsertUsage(String instanceCode, Usage u) {
        jdbc.update("""
                INSERT INTO license_svc.lic_usage
                    (instance_code, usage_date, active_beds, active_users, ai_calls, source, captured_at)
                VALUES (?, ?, ?, ?, ?, ?, now())
                ON CONFLICT (instance_code, usage_date) DO UPDATE
                   SET active_beds = EXCLUDED.active_beds,
                       active_users = EXCLUDED.active_users,
                       ai_calls = EXCLUDED.ai_calls,
                       source = EXCLUDED.source,
                       captured_at = now()
                """, instanceCode, u.date(), u.activeBeds(), u.activeUsers(), u.aiCalls(), u.source());
    }

    public Optional<Usage> latestUsage(String instanceCode) {
        return jdbc.query("""
                SELECT usage_date, active_beds, active_users, ai_calls, source, captured_at
                  FROM license_svc.lic_usage
                 WHERE instance_code = ?
                 ORDER BY usage_date DESC
                 LIMIT 1
                """, USAGE, instanceCode).stream().findFirst();
    }

    /* ------------------------------------------------------------------ 审计 */

    public void audit(String actor, String action, String target, String detailJson, String traceId) {
        jdbc.update("""
                INSERT INTO license_svc.lic_change_log (actor, action, target, detail, trace_id)
                VALUES (?, ?, ?, CAST(? AS jsonb), ?)
                """, actor, action, target, detailJson == null || detailJson.isBlank() ? "{}" : detailJson, traceId);
    }

    public List<ChangeLog> listAudit(int limit) {
        return jdbc.query("""
                SELECT id, ts, actor, action, target, detail::text AS detail, trace_id
                  FROM license_svc.lic_change_log
                 ORDER BY ts DESC, id DESC
                 LIMIT ?
                """, (rs, i) -> new ChangeLog(
                rs.getLong("id"),
                instant(rs, "ts"),
                rs.getString("actor"),
                rs.getString("action"),
                rs.getString("target"),
                rs.getString("detail"),
                rs.getString("trace_id")), Math.max(1, Math.min(limit, 200)));
    }

    /* ------------------------------------------------------------------ 映射 */

    private static final RowMapper<Edition> EDITION = (rs, i) -> new Edition(
            rs.getString("code"),
            rs.getString("name"),
            rs.getString("description"),
            stringList(rs, "modules"),
            intOrNull(rs, "max_beds"),
            intOrNull(rs, "max_users"),
            intOrNull(rs, "max_ai_calls_per_day"),
            rs.getInt("sort_no"),
            rs.getString("status"));

    private static final RowMapper<License> LICENSE = (rs, i) -> new License(
            rs.getObject("id", UUID.class),
            rs.getString("license_no"),
            rs.getString("instance_code"),
            rs.getString("hospital_name"),
            rs.getString("edition_code"),
            stringList(rs, "modules"),
            intOrNull(rs, "max_beds"),
            intOrNull(rs, "max_users"),
            intOrNull(rs, "max_ai_calls_per_day"),
            instant(rs, "not_before"),
            instant(rs, "expires_at"),
            rs.getInt("grace_days"),
            rs.getString("status"),
            rs.getString("payload"),
            rs.getString("payload_hash"),
            rs.getString("signature"),
            rs.getString("key_id"),
            rs.getString("imported_by"),
            instant(rs, "imported_at"),
            instant(rs, "issued_at"),
            rs.getString("revoked_by"),
            instant(rs, "revoked_at"),
            rs.getString("revoke_reason"));

    private static final RowMapper<Usage> USAGE = (rs, i) -> new Usage(
            rs.getObject("usage_date", LocalDate.class),
            rs.getInt("active_beds"),
            rs.getInt("active_users"),
            rs.getInt("ai_calls"),
            rs.getString("source"),
            instant(rs, "captured_at"));

    /** PG text[] 必须走 getArray().getArray()：getObject(String[].class) 在 pgjdbc 上不支持。 */
    private static List<String> stringList(ResultSet rs, String column) throws SQLException {
        java.sql.Array array = rs.getArray(column);
        if (array == null) {
            return List.of();
        }
        Object raw = array.getArray();
        if (raw instanceof String[] values) {
            return List.of(values);
        }
        if (raw instanceof Object[] values) {
            return Arrays.stream(values).map(String::valueOf).toList();
        }
        return List.of();
    }

    private static Integer intOrNull(ResultSet rs, String column) throws SQLException {
        int value = rs.getInt(column);
        return rs.wasNull() ? null : value;
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp ts = rs.getTimestamp(column);
        return ts == null ? null : ts.toInstant();
    }

    /** PG 数组字面量：模块码只含 [a-z0-9-]，无需转义。 */
    static String pgTextArray(List<String> values) {
        if (values == null || values.isEmpty()) {
            return "{}";
        }
        return "{" + String.join(",", values) + "}";
    }
}
