package com.medassist.clinical.repo;

import com.medassist.clinical.domain.Hl7LabModel.Case;
import com.medassist.clinical.domain.Hl7LabModel.Endpoint;
import com.medassist.clinical.domain.Hl7LabModel.Run;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.ObjectMapper;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * HL7 联调仓储（clinical_svc.hl7_*）。
 *
 * <p>执行记录只追加；端点/用例按 code 幂等 upsert，停用后仍保留历史。
 */
@Repository
public class Hl7LabRepository {

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final NamedParameterJdbcTemplate jdbc;
    private final JdbcTemplate plain;
    private final ObjectMapper objectMapper;

    public Hl7LabRepository(NamedParameterJdbcTemplate jdbc, JdbcTemplate plain,
                            ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.plain = plain;
        this.objectMapper = objectMapper;
    }

    // ------------------------------------------------------------------ 端点

    private static final RowMapper<Endpoint> ENDPOINT_MAPPER = (rs, n) -> new Endpoint(
            rs.getObject("id", UUID.class),
            rs.getString("code"),
            rs.getString("name"),
            rs.getString("host"),
            rs.getInt("port"),
            rs.getInt("timeout_ms"),
            rs.getString("description"),
            rs.getString("status"),
            rs.getLong("version"),
            rs.getString("created_by"),
            instant(rs, "created_at"),
            instant(rs, "updated_at"));

    private static final String ENDPOINT_COLUMNS = """
            id, code, name, host, port, timeout_ms, description, status, version, created_by,
            created_at, updated_at
            """;

    public List<Endpoint> listEndpoints(boolean onlyActive) {
        return plain.query("SELECT " + ENDPOINT_COLUMNS + " FROM clinical_svc.hl7_endpoint"
                + (onlyActive ? " WHERE status = 'ACTIVE'" : "") + " ORDER BY code",
                ENDPOINT_MAPPER);
    }

    public Optional<Endpoint> findEndpoint(UUID id) {
        return plain.query("SELECT " + ENDPOINT_COLUMNS
                + " FROM clinical_svc.hl7_endpoint WHERE id = ?", ENDPOINT_MAPPER, id)
                .stream().findFirst();
    }

    public Optional<Endpoint> findEndpointByCode(String code) {
        return plain.query("SELECT " + ENDPOINT_COLUMNS
                + " FROM clinical_svc.hl7_endpoint WHERE code = ?", ENDPOINT_MAPPER, code)
                .stream().findFirst();
    }

    public void upsertEndpoint(Endpoint endpoint) {
        jdbc.update("""
                INSERT INTO clinical_svc.hl7_endpoint
                    (id, code, name, host, port, timeout_ms, description, status, created_by)
                VALUES (:id, :code, :name, :host, :port, :timeout, :description, :status, :createdBy)
                ON CONFLICT (code) DO UPDATE SET
                    name = EXCLUDED.name, host = EXCLUDED.host, port = EXCLUDED.port,
                    timeout_ms = EXCLUDED.timeout_ms, description = EXCLUDED.description,
                    status = EXCLUDED.status,
                    version = clinical_svc.hl7_endpoint.version + 1, updated_at = now()
                """, new MapSqlParameterSource()
                .addValue("id", endpoint.id())
                .addValue("code", endpoint.code())
                .addValue("name", endpoint.name())
                .addValue("host", endpoint.host())
                .addValue("port", endpoint.port())
                .addValue("timeout", endpoint.timeoutMs())
                .addValue("description", endpoint.description())
                .addValue("status", endpoint.status())
                .addValue("createdBy", endpoint.createdBy()));
    }

    public boolean updateEndpointStatus(UUID id, String status) {
        return plain.update("""
                UPDATE clinical_svc.hl7_endpoint
                   SET status = ?, version = version + 1, updated_at = now()
                 WHERE id = ?
                """, status, id) > 0;
    }

    // ------------------------------------------------------------------ 用例

    private static final RowMapper<Case> CASE_MAPPER = (rs, n) -> new Case(
            rs.getObject("id", UUID.class),
            rs.getString("code"),
            rs.getString("name"),
            rs.getString("message_type"),
            rs.getString("trigger_event"),
            rs.getString("sample_message"),
            rs.getString("expected_ack_code"),
            stringList(rs, "required_segments"),
            rs.getString("status"),
            rs.getLong("version"),
            rs.getString("created_by"),
            instant(rs, "created_at"),
            instant(rs, "updated_at"));

    private static final String CASE_COLUMNS = """
            id, code, name, message_type, trigger_event, sample_message, expected_ack_code,
            required_segments, status, version, created_by, created_at, updated_at
            """;

    public List<Case> listCases(boolean onlyActive) {
        return plain.query("SELECT " + CASE_COLUMNS + " FROM clinical_svc.hl7_case"
                + (onlyActive ? " WHERE status = 'ACTIVE'" : "") + " ORDER BY code", CASE_MAPPER);
    }

    public Optional<Case> findCase(UUID id) {
        return plain.query("SELECT " + CASE_COLUMNS
                + " FROM clinical_svc.hl7_case WHERE id = ?", CASE_MAPPER, id).stream().findFirst();
    }

    public Optional<Case> findCaseByCode(String code) {
        return plain.query("SELECT " + CASE_COLUMNS
                + " FROM clinical_svc.hl7_case WHERE code = ?", CASE_MAPPER, code).stream().findFirst();
    }

    public void upsertCase(Case hl7Case) {
        jdbc.update("""
                INSERT INTO clinical_svc.hl7_case
                    (id, code, name, message_type, trigger_event, sample_message,
                     expected_ack_code, required_segments, status, created_by)
                VALUES
                    (:id, :code, :name, :type, :trigger, :message, :expectedAck,
                     CAST(:segments AS text[]), :status, :createdBy)
                ON CONFLICT (code) DO UPDATE SET
                    name = EXCLUDED.name, message_type = EXCLUDED.message_type,
                    trigger_event = EXCLUDED.trigger_event,
                    sample_message = EXCLUDED.sample_message,
                    expected_ack_code = EXCLUDED.expected_ack_code,
                    required_segments = EXCLUDED.required_segments,
                    status = EXCLUDED.status,
                    version = clinical_svc.hl7_case.version + 1, updated_at = now()
                """, new MapSqlParameterSource()
                .addValue("id", hl7Case.id())
                .addValue("code", hl7Case.code())
                .addValue("name", hl7Case.name())
                .addValue("type", hl7Case.messageType())
                .addValue("trigger", hl7Case.triggerEvent())
                .addValue("message", hl7Case.sampleMessage())
                .addValue("expectedAck", hl7Case.expectedAckCode())
                .addValue("segments", arrayLiteral(hl7Case.requiredSegments()))
                .addValue("status", hl7Case.status())
                .addValue("createdBy", hl7Case.createdBy()));
    }

    public boolean updateCaseStatus(UUID id, String status) {
        return plain.update("""
                UPDATE clinical_svc.hl7_case
                   SET status = ?, version = version + 1, updated_at = now()
                 WHERE id = ?
                """, status, id) > 0;
    }

    // ------------------------------------------------------------------ 执行记录

    private static final RowMapper<Run> RUN_MAPPER = (rs, n) -> new Run(
            rs.getObject("id", UUID.class),
            rs.getString("run_no"),
            rs.getObject("case_id", UUID.class),
            rs.getString("case_code"),
            rs.getObject("endpoint_id", UUID.class),
            rs.getString("endpoint_code"),
            rs.getString("status"),
            rs.getString("ack_code"),
            rs.getString("ack_control_id"),
            rs.getString("ack_message"),
            rs.getString("request_excerpt"),
            rs.getString("response_excerpt"),
            rs.getString("error_message"),
            rs.getString("validation"),
            rs.getInt("duration_ms"),
            rs.getString("triggered_by"),
            instant(rs, "started_at"),
            instant(rs, "finished_at"));

    private static final String RUN_COLUMNS = """
            id, run_no, case_id, case_code, endpoint_id, endpoint_code, status, ack_code,
            ack_control_id, ack_message, request_excerpt, response_excerpt, error_message,
            validation, duration_ms, triggered_by, started_at, finished_at
            """;

    public String nextRunNo() {
        Long seq = plain.queryForObject("SELECT nextval('clinical_svc.hl7_run_no_seq')",
                Long.class);
        return "HL7" + LocalDate.now().format(DAY) + String.format("%05d", seq == null ? 1 : seq);
    }

    public void insertRun(Run run) {
        jdbc.update("""
                INSERT INTO clinical_svc.hl7_run
                    (id, run_no, case_id, case_code, endpoint_id, endpoint_code, status, ack_code,
                     ack_control_id, ack_message, request_excerpt, response_excerpt, error_message,
                     validation, duration_ms, triggered_by, started_at, finished_at)
                VALUES
                    (:id, :no, :caseId, :caseCode, :endpointId, :endpointCode, :status, :ackCode,
                     :ackControl, :ackMessage, :request, :response, :error, :validation,
                     :duration, :actor, :started, :finished)
                """, new MapSqlParameterSource()
                .addValue("id", run.id())
                .addValue("no", run.runNo())
                .addValue("caseId", run.caseId())
                .addValue("caseCode", run.caseCode())
                .addValue("endpointId", run.endpointId())
                .addValue("endpointCode", run.endpointCode())
                .addValue("status", run.status())
                .addValue("ackCode", run.ackCode())
                .addValue("ackControl", run.ackControlId())
                .addValue("ackMessage", run.ackMessage())
                .addValue("request", run.requestExcerpt())
                .addValue("response", run.responseExcerpt())
                .addValue("error", run.errorMessage())
                .addValue("validation", run.validation())
                .addValue("duration", run.durationMs())
                .addValue("actor", run.triggeredBy())
                .addValue("started", timestamp(run.startedAt()))
                .addValue("finished", timestamp(run.finishedAt())));
    }

    public Optional<Run> findRun(UUID id) {
        return plain.query("SELECT " + RUN_COLUMNS
                + " FROM clinical_svc.hl7_run WHERE id = ?", RUN_MAPPER, id).stream().findFirst();
    }

    public List<Run> listRuns(String caseCode, String status, int limit, int offset) {
        StringBuilder sql = new StringBuilder("SELECT " + RUN_COLUMNS
                + " FROM clinical_svc.hl7_run WHERE 1 = 1");
        MapSqlParameterSource params = new MapSqlParameterSource();
        if (caseCode != null && !caseCode.isBlank()) {
            sql.append(" AND case_code = :code");
            params.addValue("code", caseCode);
        }
        if (status != null && !status.isBlank()) {
            sql.append(" AND status = :status");
            params.addValue("status", status);
        }
        sql.append(" ORDER BY started_at DESC LIMIT :limit OFFSET :offset");
        params.addValue("limit", limit).addValue("offset", offset);
        return jdbc.query(sql.toString(), params, RUN_MAPPER);
    }

    public long countRuns(String caseCode, String status) {
        StringBuilder sql = new StringBuilder(
                "SELECT count(*) FROM clinical_svc.hl7_run WHERE 1 = 1");
        MapSqlParameterSource params = new MapSqlParameterSource();
        if (caseCode != null && !caseCode.isBlank()) {
            sql.append(" AND case_code = :code");
            params.addValue("code", caseCode);
        }
        if (status != null && !status.isBlank()) {
            sql.append(" AND status = :status");
            params.addValue("status", status);
        }
        Long n = jdbc.queryForObject(sql.toString(), params, Long.class);
        return n == null ? 0 : n;
    }

    public long countEndpoints(boolean onlyActive) {
        Long n = plain.queryForObject("SELECT count(*) FROM clinical_svc.hl7_endpoint"
                + (onlyActive ? " WHERE status = 'ACTIVE'" : ""), Long.class);
        return n == null ? 0 : n;
    }

    public long countCases(boolean onlyActive) {
        Long n = plain.queryForObject("SELECT count(*) FROM clinical_svc.hl7_case"
                + (onlyActive ? " WHERE status = 'ACTIVE'" : ""), Long.class);
        return n == null ? 0 : n;
    }

    public long countRunsSince(Instant since, String status) {
        StringBuilder sql = new StringBuilder(
                "SELECT count(*) FROM clinical_svc.hl7_run WHERE started_at >= :since");
        MapSqlParameterSource params = new MapSqlParameterSource("since", timestamp(since));
        if (status != null && !status.isBlank()) {
            sql.append(" AND status = :status");
            params.addValue("status", status);
        }
        Long n = jdbc.queryForObject(sql.toString(), params, Long.class);
        return n == null ? 0 : n;
    }

    // ------------------------------------------------------------------ 审计/工具

    public void audit(String actor, String action, String targetType, String targetId,
                      Map<String, Object> detail) {
        jdbc.update("""
                INSERT INTO clinical_svc.clinical_change_log
                    (actor, action, target_type, target_id, detail)
                VALUES (:actor, :action, :type, :target, CAST(:detail AS jsonb))
                """, new MapSqlParameterSource()
                .addValue("actor", actor)
                .addValue("action", action)
                .addValue("type", targetType)
                .addValue("target", targetId)
                .addValue("detail", write(detail)));
    }

    private String write(Object value) {
        try {
            return objectMapper.writeValueAsString(value == null ? Map.of() : value);
        } catch (Exception e) {
            throw new IllegalStateException("JSON 序列化失败", e);
        }
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp ts = rs.getTimestamp(column);
        return ts == null ? null : ts.toInstant();
    }

    private static Timestamp timestamp(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }

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
            List<String> result = new ArrayList<>();
            for (Object value : values) {
                result.add(String.valueOf(value));
            }
            return result;
        }
        return List.of();
    }

    private static String arrayLiteral(List<String> values) {
        if (values == null || values.isEmpty()) {
            return "{}";
        }
        return "{" + String.join(",", values) + "}";
    }
}
