package com.medassist.ops.repo;

import com.medassist.ops.domain.OpsIntegrationModel.AlertSource;
import com.medassist.ops.domain.OpsIntegrationModel.AlertState;
import com.medassist.ops.domain.OpsIntegrationModel.HookEvent;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * 告警接入仓储（ops_svc，§276）。
 *
 * <p>纪律：
 * <ul>
 *   <li>令牌哈希只在仓储层出现，不进入 API 响应；</li>
 *   <li>告警状态按 {@code (source_code, fingerprint)} 唯一，重复 firing 只更新 last_seen；</li>
 *   <li>入站事件只 INSERT（数据库触发器兜底）。</li>
 * </ul>
 */
@Repository
public class AlertIntegrationRepository {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };
    private static final TypeReference<LinkedHashMap<String, String>> STRING_MAP_TYPE =
            new TypeReference<>() {
            };
    private static final ObjectMapper STATIC_JSON = new ObjectMapper();

    private final NamedParameterJdbcTemplate jdbc;
    private final JdbcTemplate plain;
    private final ObjectMapper objectMapper;

    public AlertIntegrationRepository(NamedParameterJdbcTemplate jdbc, JdbcTemplate plain,
                                      ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.plain = plain;
        this.objectMapper = objectMapper;
    }

    /** 仓储内部使用：带上哈希供校验，但绝不返回给控制器。 */
    public record StoredSource(AlertSource source, String tokenHash) {
    }

    private static final RowMapper<StoredSource> SOURCE_MAPPER = (rs, n) ->
            new StoredSource(source(rs), rs.getString("token_hash"));

    public List<AlertSource> listSources() {
        return plain.query("""
                SELECT * FROM ops_svc.ops_alert_source
                ORDER BY status, created_at DESC
                """, (rs, n) -> source(rs));
    }

    public Optional<StoredSource> findSource(String code) {
        List<StoredSource> rows = jdbc.query("""
                SELECT * FROM ops_svc.ops_alert_source WHERE code = :code
                """, new MapSqlParameterSource("code", code), SOURCE_MAPPER);
        return rows.stream().findFirst();
    }

    public Optional<AlertSource> findSourceMeta(UUID id) {
        List<AlertSource> rows = jdbc.query("""
                SELECT * FROM ops_svc.ops_alert_source WHERE id = :id
                """, new MapSqlParameterSource("id", id), (rs, n) -> source(rs));
        return rows.stream().findFirst();
    }

    public void insertSource(AlertSource source, String tokenHash) {
        jdbc.update("""
                INSERT INTO ops_svc.ops_alert_source
                    (id, code, name, kind, token_hash, token_hint, default_service,
                     severity_map, auto_resolve, repeat_comment_minutes, status, created_by)
                VALUES
                    (:id, :code, :name, :kind, :hash, :hint, :defaultService,
                     CAST(:severityMap AS jsonb), :autoResolve, :repeat, :status, :createdBy)
                """, new MapSqlParameterSource()
                .addValue("id", source.id())
                .addValue("code", source.code())
                .addValue("name", source.name())
                .addValue("kind", source.kind())
                .addValue("hash", tokenHash)
                .addValue("hint", source.tokenHint())
                .addValue("defaultService", source.defaultService())
                .addValue("severityMap", write(source.severityMap()))
                .addValue("autoResolve", source.autoResolve())
                .addValue("repeat", source.repeatCommentMinutes())
                .addValue("status", source.status())
                .addValue("createdBy", source.createdBy()));
    }

    public int updateToken(UUID id, String tokenHash, String tokenHint) {
        return jdbc.update("""
                UPDATE ops_svc.ops_alert_source
                   SET token_hash = :hash, token_hint = :hint,
                       updated_at = now(), version = version + 1
                 WHERE id = :id
                """, new MapSqlParameterSource()
                .addValue("id", id)
                .addValue("hash", tokenHash)
                .addValue("hint", tokenHint));
    }

    public int disable(UUID id) {
        return jdbc.update("""
                UPDATE ops_svc.ops_alert_source
                   SET status = 'INACTIVE', updated_at = now(), version = version + 1
                 WHERE id = :id AND status = 'ACTIVE'
                """, new MapSqlParameterSource("id", id));
    }

    public void touchUsed(String code) {
        jdbc.update("""
                UPDATE ops_svc.ops_alert_source SET last_used_at = now() WHERE code = :code
                """, new MapSqlParameterSource("code", code));
    }

    public Optional<AlertState> findState(String sourceCode, String fingerprint) {
        List<AlertState> rows = jdbc.query("""
                SELECT * FROM ops_svc.ops_alert_state
                 WHERE source_code = :source AND fingerprint = :fingerprint
                """, new MapSqlParameterSource()
                .addValue("source", sourceCode)
                .addValue("fingerprint", fingerprint), (rs, n) -> state(rs));
        return rows.stream().findFirst();
    }

    public void upsertState(AlertState state) {
        jdbc.update("""
                INSERT INTO ops_svc.ops_alert_state
                    (source_code, fingerprint, status, alertname, severity, priority,
                     service_code, title, ticket_id, starts_at, last_seen_at, resolved_at,
                     labels, annotations)
                VALUES
                    (:source, :fingerprint, :status, :alertname, :severity, :priority,
                     :service, :title, :ticketId, :startsAt, :lastSeenAt, :resolvedAt,
                     CAST(:labels AS jsonb), CAST(:annotations AS jsonb))
                ON CONFLICT (source_code, fingerprint) DO UPDATE SET
                    status = EXCLUDED.status,
                    alertname = EXCLUDED.alertname,
                    severity = EXCLUDED.severity,
                    priority = EXCLUDED.priority,
                    service_code = EXCLUDED.service_code,
                    title = EXCLUDED.title,
                    ticket_id = COALESCE(EXCLUDED.ticket_id, ops_svc.ops_alert_state.ticket_id),
                    starts_at = EXCLUDED.starts_at,
                    last_seen_at = EXCLUDED.last_seen_at,
                    resolved_at = EXCLUDED.resolved_at,
                    labels = EXCLUDED.labels,
                    annotations = EXCLUDED.annotations,
                    version = ops_svc.ops_alert_state.version + 1
                """, new MapSqlParameterSource()
                .addValue("source", state.sourceCode())
                .addValue("fingerprint", state.fingerprint())
                .addValue("status", state.status())
                .addValue("alertname", state.alertname())
                .addValue("severity", state.severity())
                .addValue("priority", state.priority())
                .addValue("service", state.serviceCode())
                .addValue("title", state.title())
                .addValue("ticketId", state.ticketId())
                .addValue("startsAt", timestamp(state.startsAt()))
                .addValue("lastSeenAt", timestamp(state.lastSeenAt()))
                .addValue("resolvedAt", timestamp(state.resolvedAt()))
                .addValue("labels", write(state.labels()))
                .addValue("annotations", write(state.annotations())));
    }

    public void insertEvent(HookEvent event, String traceId) {
        jdbc.update("""
                INSERT INTO ops_svc.ops_hook_event
                    (source_code, fingerprint, action, ticket_id, note, payload, trace_id)
                VALUES
                    (:source, :fingerprint, :action, :ticketId, :note,
                     CAST(:payload AS jsonb), :trace)
                """, new MapSqlParameterSource()
                .addValue("source", event.sourceCode())
                .addValue("fingerprint", event.fingerprint())
                .addValue("action", event.action())
                .addValue("ticketId", event.ticketId())
                .addValue("note", event.note())
                .addValue("payload", write(event.payload()))
                .addValue("trace", traceId));
    }

    public List<HookEvent> listEvents(String sourceCode, int limit) {
        String sql = """
                SELECT * FROM ops_svc.ops_hook_event
                 WHERE (CAST(:source AS text) IS NULL OR source_code = CAST(:source AS text))
                 ORDER BY ts DESC LIMIT :limit
                """;
        return jdbc.query(sql, new MapSqlParameterSource()
                .addValue("source", sourceCode)
                .addValue("limit", limit), (rs, n) -> new HookEvent(
                rs.getLong("id"),
                instant(rs, "ts"),
                rs.getString("source_code"),
                rs.getString("fingerprint"),
                rs.getString("action"),
                rs.getObject("ticket_id", UUID.class),
                rs.getString("note"),
                readMap(rs.getString("payload")),
                rs.getString("trace_id")));
    }

    // ------------------------------------------------------------------ 映射

    private static AlertSource source(ResultSet rs) throws SQLException {
        return new AlertSource(
                rs.getObject("id", UUID.class),
                rs.getString("code"),
                rs.getString("name"),
                rs.getString("kind"),
                rs.getString("token_hint"),
                rs.getString("default_service"),
                readStringMap(rs.getString("severity_map")),
                rs.getBoolean("auto_resolve"),
                rs.getInt("repeat_comment_minutes"),
                rs.getString("status"),
                instant(rs, "last_used_at"),
                rs.getString("created_by"),
                rs.getLong("version"),
                instant(rs, "created_at"),
                instant(rs, "updated_at"));
    }

    private static AlertState state(ResultSet rs) throws SQLException {
        return new AlertState(
                rs.getString("source_code"),
                rs.getString("fingerprint"),
                rs.getString("status"),
                rs.getString("alertname"),
                rs.getString("severity"),
                rs.getString("priority"),
                rs.getString("service_code"),
                rs.getString("title"),
                rs.getObject("ticket_id", UUID.class),
                instant(rs, "starts_at"),
                instant(rs, "last_seen_at"),
                instant(rs, "resolved_at"),
                readMap(rs.getString("labels")),
                readMap(rs.getString("annotations")),
                rs.getLong("version"));
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    private static Timestamp timestamp(Instant value) {
        return value == null ? null : Timestamp.from(value);
    }

    private String write(Object value) {
        return objectMapper.writeValueAsString(value == null ? Map.of() : value);
    }

    private static Map<String, Object> readMap(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        return STATIC_JSON.readValue(json, MAP_TYPE);
    }

    private static Map<String, String> readStringMap(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        return STATIC_JSON.readValue(json, STRING_MAP_TYPE);
    }
}
