package com.medassist.migration.repo;

import com.medassist.migration.catalog.TargetCatalog;
import com.medassist.migration.domain.MigrationModel;
import com.medassist.migration.domain.MigrationModel.AuditLog;
import com.medassist.migration.domain.MigrationModel.Batch;
import com.medassist.migration.domain.MigrationModel.FieldDef;
import com.medassist.migration.domain.MigrationModel.MappingProfile;
import com.medassist.migration.domain.MigrationModel.MappingRule;
import com.medassist.migration.domain.MigrationModel.Row;
import com.medassist.migration.domain.MigrationModel.Target;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.stereotype.Repository;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * 迁移台账仓储。
 *
 * <p>纪律：
 * <ul>
 *   <li>台账写入只走显式参数绑定，JSONB 用 {@code CAST(:x AS jsonb)}；</li>
 *   <li>状态推进用 {@code WHERE status = :expected} 做 CAS，避免并发双应用/双回滚；</li>
 *   <li>审计表只 INSERT（数据库触发器兜底）；</li>
 *   <li>目标表的统计 SQL 的表名来自 {@link TargetCatalog} 常量，不接受外部输入拼接。</li>
 * </ul>
 */
@Repository
public class MigrationRepository {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };
    private static final TypeReference<List<MappingRule>> RULE_TYPE = new TypeReference<>() {
    };

    /** 静态 RowMapper 只能依赖静态 mapper；写操作仍用注入的 ObjectMapper（与其他服务一致）。 */
    private static final ObjectMapper STATIC_JSON = new ObjectMapper();

    private final NamedParameterJdbcTemplate jdbc;
    private final JdbcTemplate plain;
    private final ObjectMapper objectMapper;

    public MigrationRepository(NamedParameterJdbcTemplate jdbc, JdbcTemplate plain,
                               ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.plain = plain;
        this.objectMapper = objectMapper;
    }

    // ------------------------------------------------------------------ 目标/字段

    private static final RowMapper<Target> TARGET_MAPPER = (rs, n) -> new Target(
            rs.getString("code"),
            rs.getString("name"),
            rs.getString("category"),
            rs.getString("target_schema"),
            rs.getString("target_table"),
            rs.getString("natural_key"),
            rs.getString("apply_mode"),
            rs.getInt("sort_no"),
            rs.getString("description"));

    public List<Target> listTargets(boolean onlyEnabled) {
        return plain.query("""
                SELECT code, name, category, target_schema, target_table, natural_key,
                       apply_mode, sort_no, description
                  FROM mig_svc.mig_target
                """ + (onlyEnabled ? " WHERE enabled = true" : "")
                + " ORDER BY sort_no", TARGET_MAPPER);
    }

    public Optional<Target> findTarget(String code) {
        return plain.query("""
                SELECT code, name, category, target_schema, target_table, natural_key,
                       apply_mode, sort_no, description
                  FROM mig_svc.mig_target WHERE code = ?
                """, TARGET_MAPPER, code).stream().findFirst();
    }

    private static final RowMapper<FieldDef> FIELD_MAPPER = (rs, n) -> new FieldDef(
            rs.getString("field_key"),
            rs.getString("label"),
            rs.getString("data_type"),
            rs.getBoolean("required"),
            (Integer) rs.getObject("max_length"),
            readStringArray(rs, "enum_values"),
            rs.getString("reference_target"),
            rs.getString("description"),
            rs.getInt("sort_no"));

    public List<FieldDef> listFields(String targetCode) {
        return plain.query("""
                SELECT field_key, label, data_type, required, max_length, enum_values,
                       reference_target, description, sort_no
                  FROM mig_svc.mig_field WHERE target_code = ?
                 ORDER BY sort_no, field_key
                """, FIELD_MAPPER, targetCode);
    }

    public List<String> listDependsOn(String targetCode) {
        return plain.queryForList("""
                SELECT depends_on FROM mig_svc.mig_target_dependency
                 WHERE target_code = ? ORDER BY depends_on
                """, String.class, targetCode);
    }

    public List<String> listRequiredBy(String targetCode) {
        return plain.queryForList("""
                SELECT target_code FROM mig_svc.mig_target_dependency
                 WHERE depends_on = ? ORDER BY target_code
                """, String.class, targetCode);
    }

    // ------------------------------------------------------------------ 映射档案

    private RowMapper<MappingProfile> profileMapper() {
        return (rs, n) -> new MappingProfile(
                rs.getObject("id", UUID.class),
                rs.getString("profile_code"),
                rs.getString("name"),
                rs.getString("source_system"),
                rs.getString("target_code"),
                readRules(rs.getString("mappings")),
                rs.getString("status"),
                rs.getLong("version"),
                rs.getString("created_by"),
                instant(rs, "created_at"),
                instant(rs, "updated_at"));
    }

    public List<MappingProfile> listProfiles(String targetCode, String sourceSystem, int limit) {
        StringBuilder sql = new StringBuilder("""
                SELECT id, profile_code, name, source_system, target_code, mappings, status,
                       version, created_by, created_at, updated_at
                  FROM mig_svc.mig_mapping_profile WHERE 1 = 1
                """);
        MapSqlParameterSource params = new MapSqlParameterSource();
        if (targetCode != null && !targetCode.isBlank()) {
            sql.append(" AND target_code = :target");
            params.addValue("target", targetCode);
        }
        if (sourceSystem != null && !sourceSystem.isBlank()) {
            sql.append(" AND source_system = :system");
            params.addValue("system", sourceSystem);
        }
        sql.append(" ORDER BY target_code, profile_code LIMIT :limit");
        params.addValue("limit", limit);
        return jdbc.query(sql.toString(), params, profileMapper());
    }

    public Optional<MappingProfile> findProfile(String profileCode) {
        return plain.query("""
                SELECT id, profile_code, name, source_system, target_code, mappings, status,
                       version, created_by, created_at, updated_at
                  FROM mig_svc.mig_mapping_profile WHERE profile_code = ?
                """, profileMapper(), profileCode).stream().findFirst();
    }

    public boolean insertProfile(MappingProfile profile) {
        int changed = jdbc.update("""
                INSERT INTO mig_svc.mig_mapping_profile
                    (id, profile_code, name, source_system, target_code, mappings, status,
                     version, created_by)
                VALUES (:id, :code, :name, :system, :target, CAST(:mappings AS jsonb), :status,
                        0, :actor)
                ON CONFLICT (profile_code) DO NOTHING
                """, new MapSqlParameterSource()
                .addValue("id", profile.id())
                .addValue("code", profile.profileCode())
                .addValue("name", profile.name())
                .addValue("system", profile.sourceSystem())
                .addValue("target", profile.targetCode())
                .addValue("mappings", write(profile.mappings()))
                .addValue("status", profile.status())
                .addValue("actor", profile.createdBy()));
        return changed > 0;
    }

    public boolean retireProfile(String profileCode, String actor) {
        return jdbc.update("""
                UPDATE mig_svc.mig_mapping_profile
                   SET status = 'INACTIVE', version = version + 1, updated_at = now()
                 WHERE profile_code = :code AND status = 'ACTIVE'
                """, new MapSqlParameterSource()
                .addValue("code", profileCode)
                .addValue("actor", actor)) > 0;
    }

    // ------------------------------------------------------------------ 批次

    private RowMapper<Batch> batchMapper(boolean withRows) {
        return (rs, n) -> new Batch(
                rs.getObject("id", UUID.class),
                rs.getString("batch_no"),
                rs.getString("target_code"),
                rs.getString("source_system"),
                rs.getString("file_name"),
                rs.getString("profile_code"),
                rs.getString("checksum"),
                rs.getInt("row_count"),
                rs.getInt("create_count"),
                rs.getInt("update_count"),
                rs.getInt("unchanged_count"),
                rs.getInt("conflict_count"),
                rs.getInt("error_count"),
                rs.getString("status"),
                readMap(rs.getString("summary")),
                rs.getString("created_by"),
                instant(rs, "created_at"),
                instant(rs, "applied_at"),
                instant(rs, "rolled_back_at"),
                rs.getString("rolled_back_by"),
                List.of());
    }

    private static final String BATCH_COLUMNS = """
            id, batch_no, target_code, source_system, file_name, profile_code, checksum,
            row_count, create_count, update_count, unchanged_count, conflict_count, error_count,
            status, summary, created_by, created_at, applied_at, rolled_back_at, rolled_back_by
            """;

    public Optional<Batch> findBatch(UUID id) {
        return plain.query("SELECT " + BATCH_COLUMNS + " FROM mig_svc.mig_batch WHERE id = ?",
                batchMapper(true), id).stream().findFirst();
    }

    public Optional<Batch> findBatchByNo(String batchNo) {
        return plain.query("SELECT " + BATCH_COLUMNS + " FROM mig_svc.mig_batch WHERE batch_no = ?",
                batchMapper(true), batchNo).stream().findFirst();
    }

    public Optional<Batch> findAppliedByChecksum(String targetCode, String checksum) {
        return plain.query("SELECT " + BATCH_COLUMNS + """
                  FROM mig_svc.mig_batch
                 WHERE target_code = ? AND checksum = ? AND status = 'APPLIED'
                 ORDER BY applied_at DESC LIMIT 1
                """, batchMapper(true), targetCode, checksum).stream().findFirst();
    }

    public List<Batch> listBatches(String targetCode, String status, int limit) {
        StringBuilder sql = new StringBuilder("SELECT " + BATCH_COLUMNS
                + " FROM mig_svc.mig_batch WHERE 1 = 1");
        MapSqlParameterSource params = new MapSqlParameterSource();
        if (targetCode != null && !targetCode.isBlank()) {
            sql.append(" AND target_code = :target");
            params.addValue("target", targetCode);
        }
        if (status != null && !status.isBlank()) {
            sql.append(" AND status = :status");
            params.addValue("status", status);
        }
        sql.append(" ORDER BY created_at DESC LIMIT :limit");
        params.addValue("limit", limit);
        return jdbc.query(sql.toString(), params, batchMapper(false));
    }

    public void insertBatch(Batch batch, List<Row> rows, Map<Integer, String> payloads) {
        jdbc.update("""
                INSERT INTO mig_svc.mig_batch
                    (id, batch_no, target_code, source_system, file_name, profile_code, checksum,
                     row_count, create_count, update_count, unchanged_count, conflict_count,
                     error_count, status, summary, created_by)
                VALUES
                    (:id, :batchNo, :target, :system, :fileName, :profile, :checksum,
                     :rowCount, :createCount, :updateCount, :unchangedCount, :conflictCount,
                     :errorCount, :status, CAST(:summary AS jsonb), :actor)
                """, new MapSqlParameterSource()
                .addValue("id", batch.id())
                .addValue("batchNo", batch.batchNo())
                .addValue("target", batch.targetCode())
                .addValue("system", batch.sourceSystem())
                .addValue("fileName", batch.fileName())
                .addValue("profile", batch.profileCode())
                .addValue("checksum", batch.checksum())
                .addValue("rowCount", batch.rowCount())
                .addValue("createCount", batch.createCount())
                .addValue("updateCount", batch.updateCount())
                .addValue("unchangedCount", batch.unchangedCount())
                .addValue("conflictCount", batch.conflictCount())
                .addValue("errorCount", batch.errorCount())
                .addValue("status", batch.status())
                .addValue("summary", write(batch.summary() == null ? Map.of() : batch.summary()))
                .addValue("actor", batch.createdBy()));

        if (rows.isEmpty()) {
            return;
        }
        SqlParameterSource[] params = rows.stream().map(row -> new MapSqlParameterSource()
                .addValue("batchId", row.batchId())
                .addValue("rowNo", row.rowNo())
                .addValue("raw", write(row.raw()))
                .addValue("normalized", row.normalized() == null ? null : write(row.normalized()))
                .addValue("payload", payloads.get(row.rowNo()))
                .addValue("action", row.action())
                .addValue("naturalKey", row.naturalKey())
                .addValue("beforeImage", row.beforeImage() == null ? null : write(row.beforeImage()))
                .addValue("message", row.message()))
                .toArray(SqlParameterSource[]::new);
        jdbc.batchUpdate("""
                INSERT INTO mig_svc.mig_row
                    (batch_id, row_no, raw, normalized, payload_enc, action, natural_key,
                     before_image, message)
                VALUES
                    (:batchId, :rowNo, CAST(:raw AS jsonb), CAST(:normalized AS jsonb), :payload,
                     :action, :naturalKey, CAST(:beforeImage AS jsonb), :message)
                """, params);
    }

    public Optional<String> findRowPayload(UUID batchId, int rowNo) {
        return plain.query("SELECT payload_enc FROM mig_svc.mig_row"
                        + " WHERE batch_id = ? AND row_no = ?",
                (rs, n) -> rs.getString(1), batchId, rowNo).stream().findFirst();
    }

    public List<Row> listRows(UUID batchId, int limit, int offset) {
        return plain.query("""
                SELECT id, batch_id, row_no, raw, normalized, action, target_pk, natural_key,
                       before_image, after_image, message, applied, rolled_back
                  FROM mig_svc.mig_row
                 WHERE batch_id = ?
                 ORDER BY row_no LIMIT ? OFFSET ?
                """, ROW_MAPPER, batchId, limit, offset);
    }

    /** 应用/回滚需要全部行（单批上限 5000，内存可控）。 */
    public List<Row> listAllRows(UUID batchId) {
        return plain.query("""
                SELECT id, batch_id, row_no, raw, normalized, action, target_pk, natural_key,
                       before_image, after_image, message, applied, rolled_back
                  FROM mig_svc.mig_row
                 WHERE batch_id = ?
                 ORDER BY row_no
                """, ROW_MAPPER, batchId);
    }

    private static final RowMapper<Row> ROW_MAPPER = (rs, n) -> new Row(
            rs.getLong("id"),
            rs.getObject("batch_id", UUID.class),
            rs.getInt("row_no"),
            readMap(rs.getString("raw")),
            readMap(rs.getString("normalized")),
            rs.getString("action"),
            rs.getObject("target_pk", UUID.class),
            rs.getString("natural_key"),
            readMap(rs.getString("before_image")),
            readMap(rs.getString("after_image")),
            rs.getString("message"),
            rs.getBoolean("applied"),
            rs.getBoolean("rolled_back"));

    public void updateRowApplied(long rowId, UUID targetPk, Map<String, Object> afterImage) {
        jdbc.update("""
                UPDATE mig_svc.mig_row
                   SET target_pk = :pk, after_image = CAST(:after AS jsonb), applied = true
                 WHERE id = :id
                """, new MapSqlParameterSource()
                .addValue("pk", targetPk)
                .addValue("after", afterImage == null ? null : write(afterImage))
                .addValue("id", rowId));
    }

    public void markBatchApplied(UUID batchId, Map<String, Object> summary) {
        jdbc.update("""
                UPDATE mig_svc.mig_batch
                   SET status = 'APPLIED', applied_at = now(), summary = CAST(:summary AS jsonb)
                 WHERE id = :id AND status = 'PREVIEWED'
                """, new MapSqlParameterSource()
                .addValue("summary", write(summary))
                .addValue("id", batchId));
    }

    public boolean markBatchRolledBack(UUID batchId, String actor, Map<String, Object> summary) {
        int changed = jdbc.update("""
                UPDATE mig_svc.mig_batch
                   SET status = 'ROLLED_BACK', rolled_back_at = now(), rolled_back_by = :actor,
                       summary = CAST(:summary AS jsonb)
                 WHERE id = :id AND status = 'APPLIED'
                """, new MapSqlParameterSource()
                .addValue("actor", actor)
                .addValue("summary", write(summary))
                .addValue("id", batchId));
        if (changed > 0) {
            jdbc.update("UPDATE mig_svc.mig_row SET rolled_back = true"
                    + " WHERE batch_id = :id AND applied = true", new MapSqlParameterSource("id", batchId));
        }
        return changed > 0;
    }

    public void markBatchFailed(UUID batchId, String message) {
        jdbc.update("""
                UPDATE mig_svc.mig_batch
                   SET status = 'FAILED',
                       summary = jsonb_set(coalesce(summary, '{}'::jsonb), '{failure}', to_jsonb(CAST(:msg AS text)))
                 WHERE id = :id AND status IN ('PREVIEWED', 'APPLIED')
                """, new MapSqlParameterSource()
                .addValue("msg", message)
                .addValue("id", batchId));
    }

    // ------------------------------------------------------------------ 审计

    public void audit(String actor, String action, String targetCode, String batchNo,
                      Map<String, Object> detail, String clientIp, String traceId) {
        jdbc.update("""
                INSERT INTO mig_svc.mig_change_log
                    (actor, action, target_code, batch_no, detail, client_ip, trace_id)
                VALUES (:actor, :action, :target, :batchNo, CAST(:detail AS jsonb), :ip, :trace)
                """, new MapSqlParameterSource()
                .addValue("actor", actor)
                .addValue("action", action)
                .addValue("target", targetCode)
                .addValue("batchNo", batchNo)
                .addValue("detail", write(detail == null ? Map.of() : detail))
                .addValue("ip", clientIp)
                .addValue("trace", traceId));
    }

    public List<AuditLog> listAudit(int limit) {
        return plain.query("""
                SELECT id, ts, actor, action, target_code, batch_no, detail, client_ip, trace_id
                  FROM mig_svc.mig_change_log
                 ORDER BY ts DESC LIMIT ?
                """, (rs, n) -> new AuditLog(
                rs.getLong("id"),
                instant(rs, "ts"),
                rs.getString("actor"),
                rs.getString("action"),
                rs.getString("target_code"),
                rs.getString("batch_no"),
                readMap(rs.getString("detail")),
                rs.getString("client_ip"),
                rs.getString("trace_id")), limit);
    }

    // ------------------------------------------------------------------ 对账

    public long countTarget(TargetCatalog.Spec spec) {
        Long value = plain.queryForObject(
                "SELECT count(*) FROM " + spec.schema() + "." + spec.table(), Long.class);
        return value == null ? 0L : value;
    }

    public BigDecimal sumTargetAmount(TargetCatalog.Spec spec) {
        if (spec.amountColumn() == null) {
            return BigDecimal.ZERO;
        }
        BigDecimal value = plain.queryForObject("SELECT coalesce(sum(" + spec.amountColumn()
                + "), 0) FROM " + spec.schema() + "." + spec.table(), BigDecimal.class);
        return value == null ? BigDecimal.ZERO : value;
    }

    // ------------------------------------------------------------------ JSON/类型

    private String write(Object value) {
        try {
            return objectMapper.writeValueAsString(value == null ? Map.of() : value);
        } catch (Exception e) {
            throw new IllegalStateException("JSON 序列化失败", e);
        }
    }

    private static Map<String, Object> readMap(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return STATIC_JSON.readValue(json, MAP_TYPE);
        } catch (Exception e) {
            throw new IllegalStateException("JSON 解析失败", e);
        }
    }

    private List<MappingRule> readRules(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, RULE_TYPE);
        } catch (Exception e) {
            throw new IllegalStateException("映射档案 JSON 解析失败", e);
        }
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp ts = rs.getTimestamp(column);
        return ts == null ? null : ts.toInstant();
    }

    private static List<String> readStringArray(ResultSet rs, String column) throws SQLException {
        java.sql.Array array = rs.getArray(column);
        if (array == null) {
            return List.of();
        }
        Object raw = array.getArray();
        if (raw instanceof String[] values) {
            return Arrays.asList(values);
        }
        if (raw instanceof Object[] values) {
            return Arrays.stream(values).map(String::valueOf).toList();
        }
        return List.of();
    }
}
