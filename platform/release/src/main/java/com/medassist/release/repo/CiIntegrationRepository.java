package com.medassist.release.repo;

import com.medassist.release.domain.ReleaseIntegrationModel.CiEvent;
import com.medassist.release.domain.ReleaseIntegrationModel.CiSource;
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
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * CI 接入仓储（release_svc，§276）。
 */
@Repository
public class CiIntegrationRepository {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };
    private static final ObjectMapper STATIC_JSON = new ObjectMapper();

    private final NamedParameterJdbcTemplate jdbc;
    private final JdbcTemplate plain;
    private final ObjectMapper objectMapper;

    public CiIntegrationRepository(NamedParameterJdbcTemplate jdbc, JdbcTemplate plain,
                                   ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.plain = plain;
        this.objectMapper = objectMapper;
    }

    public record StoredSource(CiSource source, String tokenHash) {
    }

    private static final RowMapper<StoredSource> SOURCE_MAPPER = (rs, n) ->
            new StoredSource(source(rs), rs.getString("token_hash"));

    public List<CiSource> listSources() {
        return plain.query("""
                SELECT * FROM release_svc.rel_ci_source
                ORDER BY status, created_at DESC
                """, (rs, n) -> source(rs));
    }

    public Optional<StoredSource> findSource(String code) {
        return jdbc.query("SELECT * FROM release_svc.rel_ci_source WHERE code = :code",
                new MapSqlParameterSource("code", code), SOURCE_MAPPER).stream().findFirst();
    }

    public Optional<CiSource> findSourceMeta(UUID id) {
        return jdbc.query("SELECT * FROM release_svc.rel_ci_source WHERE id = :id",
                new MapSqlParameterSource("id", id), (rs, n) -> source(rs)).stream().findFirst();
    }

    public void insertSource(CiSource source, String tokenHash) {
        jdbc.update("""
                INSERT INTO release_svc.rel_ci_source
                    (id, code, name, kind, token_hash, token_hint, allowed_components,
                     status, created_by)
                VALUES
                    (:id, :code, :name, :kind, :hash, :hint,
                     CAST(:components AS text[]), :status, :createdBy)
                """, new MapSqlParameterSource()
                .addValue("id", source.id())
                .addValue("code", source.code())
                .addValue("name", source.name())
                .addValue("kind", source.kind())
                .addValue("hash", tokenHash)
                .addValue("hint", source.tokenHint())
                .addValue("components", source.allowedComponents().toArray(String[]::new))
                .addValue("status", source.status())
                .addValue("createdBy", source.createdBy()));
    }

    public int updateToken(UUID id, String tokenHash, String tokenHint) {
        return jdbc.update("""
                UPDATE release_svc.rel_ci_source
                   SET token_hash = :hash, token_hint = :hint,
                       updated_at = now(), version = version + 1
                 WHERE id = :id
                """, new MapSqlParameterSource()
                .addValue("id", id).addValue("hash", tokenHash).addValue("hint", tokenHint));
    }

    public int disable(UUID id) {
        return jdbc.update("""
                UPDATE release_svc.rel_ci_source
                   SET status = 'INACTIVE', updated_at = now(), version = version + 1
                 WHERE id = :id AND status = 'ACTIVE'
                """, new MapSqlParameterSource("id", id));
    }

    public void touchUsed(String code) {
        jdbc.update("UPDATE release_svc.rel_ci_source SET last_used_at = now() WHERE code = :code",
                new MapSqlParameterSource("code", code));
    }

    public Optional<CiEvent> findEvent(String sourceCode, String eventId) {
        return jdbc.query("""
                SELECT * FROM release_svc.rel_ci_event
                 WHERE source_code = :source AND event_id = :eventId
                """, new MapSqlParameterSource()
                .addValue("source", sourceCode).addValue("eventId", eventId),
                (rs, n) -> event(rs)).stream().findFirst();
    }

    /** 幂等插入：已存在返回 false（由调用方读取首次结果）。 */
    public boolean tryInsertEvent(CiEvent event) {
        int rows = jdbc.update("""
                INSERT INTO release_svc.rel_ci_event
                    (source_code, event_id, kind, component_code, version_no, environment,
                     action, result, status, message, payload)
                VALUES
                    (:source, :eventId, :kind, :component, :version, :environment,
                     :action, :result, :status, :message, CAST(:payload AS jsonb))
                ON CONFLICT (source_code, event_id) DO NOTHING
                """, new MapSqlParameterSource()
                .addValue("source", event.sourceCode())
                .addValue("eventId", event.eventId())
                .addValue("kind", event.kind())
                .addValue("component", event.componentCode())
                .addValue("version", event.versionNo())
                .addValue("environment", event.environment())
                .addValue("action", event.action())
                .addValue("result", event.result())
                .addValue("status", event.status())
                .addValue("message", event.message())
                .addValue("payload", write(event.payload())));
        return rows > 0;
    }

    public void finishEvent(String sourceCode, String eventId, String status, UUID deploymentId,
                            String message) {
        jdbc.update("""
                UPDATE release_svc.rel_ci_event
                   SET status = :status, deployment_id = :deploymentId, message = :message
                 WHERE source_code = :source AND event_id = :eventId
                """, new MapSqlParameterSource()
                .addValue("status", status)
                .addValue("deploymentId", deploymentId)
                .addValue("message", message)
                .addValue("source", sourceCode)
                .addValue("eventId", eventId));
    }

    public List<CiEvent> listEvents(String sourceCode, String kind, int limit) {
        return jdbc.query("""
                SELECT * FROM release_svc.rel_ci_event
                 WHERE (CAST(:source AS text) IS NULL OR source_code = CAST(:source AS text))
                   AND (CAST(:kind AS text) IS NULL OR kind = CAST(:kind AS text))
                 ORDER BY ts DESC LIMIT :limit
                """, new MapSqlParameterSource()
                .addValue("source", sourceCode)
                .addValue("kind", kind)
                .addValue("limit", limit), (rs, n) -> event(rs));
    }

    // ------------------------------------------------------------------ 映射

    private static CiSource source(ResultSet rs) throws SQLException {
        return new CiSource(
                rs.getObject("id", UUID.class),
                rs.getString("code"),
                rs.getString("name"),
                rs.getString("kind"),
                rs.getString("token_hint"),
                stringList(rs),
                rs.getString("status"),
                instant(rs, "last_used_at"),
                rs.getString("created_by"),
                rs.getLong("version"),
                instant(rs, "created_at"),
                instant(rs, "updated_at"));
    }

    private static CiEvent event(ResultSet rs) throws SQLException {
        return new CiEvent(
                rs.getLong("id"),
                instant(rs, "ts"),
                rs.getString("source_code"),
                rs.getString("event_id"),
                rs.getString("kind"),
                rs.getString("component_code"),
                rs.getString("version_no"),
                rs.getString("environment"),
                rs.getString("action"),
                rs.getString("result"),
                rs.getObject("deployment_id", UUID.class),
                rs.getString("status"),
                rs.getString("message"),
                readMap(rs.getString("payload")));
    }

    private static List<String> stringList(ResultSet rs) throws SQLException {
        // PostgreSQL text[] 不能直接 getObject(String[].class)，需先取 java.sql.Array
        java.sql.Array array = rs.getArray("allowed_components");
        if (array == null) {
            return List.of();
        }
        Object raw = array.getArray();
        if (raw instanceof String[] values) {
            return List.of(values);
        }
        if (raw instanceof Object[] values) {
            return java.util.Arrays.stream(values).map(String::valueOf).toList();
        }
        return List.of();
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
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
}
