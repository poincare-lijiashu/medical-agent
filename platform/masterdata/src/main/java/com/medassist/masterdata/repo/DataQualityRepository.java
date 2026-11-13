package com.medassist.masterdata.repo;

import com.medassist.masterdata.domain.DataQualityModel.Dashboard;
import com.medassist.masterdata.domain.DataQualityModel.Issue;
import com.medassist.masterdata.domain.DataQualityModel.Rule;
import com.medassist.masterdata.domain.DataQualityModel.RuleFailure;
import com.medassist.masterdata.domain.DataQualityModel.Run;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import javax.sql.DataSource;
import java.math.BigDecimal;
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
 * 数据质量仓储（mdm_svc.md_dq_*）。
 *
 * <p>探针使用独立 JdbcTemplate：查询超时 5 秒，避免一条写错的规则把连接池拖死；
 * 写操作全部显式 SQL，状态推进带 version CAS。
 */
@Repository
public class DataQualityRepository {

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final NamedParameterJdbcTemplate jdbc;
    private final JdbcTemplate plain;
    private final JdbcTemplate probeTemplate;

    public DataQualityRepository(NamedParameterJdbcTemplate jdbc, JdbcTemplate plain,
                                 DataSource dataSource) {
        this.jdbc = jdbc;
        this.plain = plain;
        this.probeTemplate = new JdbcTemplate(dataSource);
        this.probeTemplate.setQueryTimeout(5);
        this.probeTemplate.setFetchSize(1);
    }

    // ------------------------------------------------------------------ 规则

    private static final RowMapper<Rule> RULE_MAPPER = (rs, n) -> new Rule(
            rs.getObject("id", UUID.class),
            rs.getString("code"),
            rs.getString("name"),
            rs.getString("domain"),
            rs.getString("dimension"),
            rs.getString("sql_text"),
            rs.getString("comparator"),
            rs.getBigDecimal("threshold"),
            rs.getBigDecimal("threshold2"),
            rs.getString("unit"),
            rs.getString("severity"),
            rs.getString("owner"),
            rs.getString("status"),
            rs.getLong("version"),
            rs.getString("created_by"),
            instant(rs, "created_at"),
            instant(rs, "updated_at"));

    private static final String RULE_COLUMNS = """
            id, code, name, domain, dimension, sql_text, comparator, threshold, threshold2,
            unit, severity, owner, status, version, created_by, created_at, updated_at
            """;

    public List<Rule> listRules(String domain, String status, int limit) {
        StringBuilder sql = new StringBuilder("SELECT " + RULE_COLUMNS
                + " FROM mdm_svc.md_dq_rule WHERE 1 = 1");
        MapSqlParameterSource params = new MapSqlParameterSource();
        if (domain != null && !domain.isBlank()) {
            sql.append(" AND domain = :domain");
            params.addValue("domain", domain);
        }
        if (status != null && !status.isBlank()) {
            sql.append(" AND status = :status");
            params.addValue("status", status);
        }
        sql.append(" ORDER BY severity, code LIMIT :limit");
        params.addValue("limit", limit);
        return jdbc.query(sql.toString(), params, RULE_MAPPER);
    }

    public Optional<Rule> findRule(UUID id) {
        return plain.query("SELECT " + RULE_COLUMNS
                + " FROM mdm_svc.md_dq_rule WHERE id = ?", RULE_MAPPER, id).stream().findFirst();
    }

    public Optional<Rule> findRuleByCode(String code) {
        return plain.query("SELECT " + RULE_COLUMNS
                + " FROM mdm_svc.md_dq_rule WHERE code = ?", RULE_MAPPER, code)
                .stream().findFirst();
    }

    public void insertRule(Rule rule) {
        jdbc.update("""
                INSERT INTO mdm_svc.md_dq_rule
                    (id, code, name, domain, dimension, sql_text, comparator, threshold,
                     threshold2, unit, severity, owner, status, created_by)
                VALUES
                    (:id, :code, :name, :domain, :dimension, :sql, :comparator, :threshold,
                     :threshold2, :unit, :severity, :owner, :status, :createdBy)
                """, new MapSqlParameterSource()
                .addValue("id", rule.id())
                .addValue("code", rule.code())
                .addValue("name", rule.name())
                .addValue("domain", rule.domain())
                .addValue("dimension", rule.dimension())
                .addValue("sql", rule.sqlText())
                .addValue("comparator", rule.comparator())
                .addValue("threshold", rule.threshold())
                .addValue("threshold2", rule.threshold2())
                .addValue("unit", rule.unit())
                .addValue("severity", rule.severity())
                .addValue("owner", rule.owner())
                .addValue("status", rule.status())
                .addValue("createdBy", rule.createdBy()));
    }

    private static final java.util.Set<String> MUTABLE_RULE_COLUMNS = java.util.Set.of(
            "name", "domain", "dimension", "sql_text", "comparator", "threshold", "threshold2",
            "unit", "severity", "owner", "status");

    public boolean updateRule(UUID id, long expectedVersion, Map<String, Object> fields) {
        if (fields == null || fields.isEmpty()) {
            return false;
        }
        List<String> sets = new ArrayList<>();
        MapSqlParameterSource params = new MapSqlParameterSource();
        for (Map.Entry<String, Object> entry : fields.entrySet()) {
            if (!MUTABLE_RULE_COLUMNS.contains(entry.getKey())) {
                throw new IllegalArgumentException("不允许修改的质量规则字段：" + entry.getKey());
            }
            sets.add(entry.getKey() + " = :" + entry.getKey());
            params.addValue(entry.getKey(), entry.getValue());
        }
        params.addValue("id", id).addValue("expected", expectedVersion);
        return jdbc.update("UPDATE mdm_svc.md_dq_rule SET " + String.join(", ", sets)
                + ", version = version + 1, updated_at = now()"
                + " WHERE id = :id AND version = :expected", params) > 0;
    }

    public long countActiveRules() {
        Long n = plain.queryForObject(
                "SELECT count(*) FROM mdm_svc.md_dq_rule WHERE status = 'ACTIVE'", Long.class);
        return n == null ? 0 : n;
    }

    public List<Rule> activeRules(int limit) {
        return plain.query("SELECT " + RULE_COLUMNS
                + " FROM mdm_svc.md_dq_rule WHERE status = 'ACTIVE' ORDER BY severity, code LIMIT ?",
                RULE_MAPPER, limit);
    }

    // ------------------------------------------------------------------ 执行记录

    private static final RowMapper<Run> RUN_MAPPER = (rs, n) -> new Run(
            rs.getObject("id", UUID.class),
            rs.getString("run_no"),
            rs.getObject("rule_id", UUID.class),
            rs.getString("rule_code"),
            rs.getString("status"),
            rs.getBigDecimal("observed_value"),
            rs.getBoolean("passed"),
            rs.getString("error_message"),
            rs.getString("run_mode"),
            rs.getString("triggered_by"),
            rs.getInt("duration_ms"),
            instant(rs, "started_at"),
            instant(rs, "finished_at"));

    private static final String RUN_COLUMNS = """
            id, run_no, rule_id, rule_code, status, observed_value, passed, error_message,
            run_mode, triggered_by, duration_ms, started_at, finished_at
            """;

    public String nextRunNo() {
        Long seq = plain.queryForObject("SELECT nextval('mdm_svc.md_dq_run_no_seq')", Long.class);
        return "DQR" + LocalDate.now().format(DAY) + String.format("%05d", seq == null ? 1 : seq);
    }

    public void insertRun(Run run) {
        jdbc.update("""
                INSERT INTO mdm_svc.md_dq_run
                    (id, run_no, rule_id, rule_code, status, observed_value, passed,
                     error_message, run_mode, triggered_by, duration_ms, started_at, finished_at)
                VALUES
                    (:id, :no, :rule, :ruleCode, :status, :observed, :passed, :error,
                     :mode, :actor, :duration, :started, :finished)
                """, new MapSqlParameterSource()
                .addValue("id", run.id())
                .addValue("no", run.runNo())
                .addValue("rule", run.ruleId())
                .addValue("ruleCode", run.ruleCode())
                .addValue("status", run.status())
                .addValue("observed", run.observedValue())
                .addValue("passed", run.passed())
                .addValue("error", run.errorMessage())
                .addValue("mode", run.runMode())
                .addValue("actor", run.triggeredBy())
                .addValue("duration", run.durationMs())
                .addValue("started", timestamp(run.startedAt()))
                .addValue("finished", timestamp(run.finishedAt())));
    }

    public Optional<Run> findRun(UUID id) {
        return plain.query("SELECT " + RUN_COLUMNS
                + " FROM mdm_svc.md_dq_run WHERE id = ?", RUN_MAPPER, id).stream().findFirst();
    }

    public List<Run> listRuns(String ruleCode, String status, int limit, int offset) {
        StringBuilder sql = new StringBuilder("SELECT " + RUN_COLUMNS
                + " FROM mdm_svc.md_dq_run WHERE 1 = 1");
        MapSqlParameterSource params = new MapSqlParameterSource();
        if (ruleCode != null && !ruleCode.isBlank()) {
            sql.append(" AND rule_code = :code");
            params.addValue("code", ruleCode);
        }
        if (status != null && !status.isBlank()) {
            sql.append(" AND status = :status");
            params.addValue("status", status);
        }
        sql.append(" ORDER BY started_at DESC LIMIT :limit OFFSET :offset");
        params.addValue("limit", limit).addValue("offset", offset);
        return jdbc.query(sql.toString(), params, RUN_MAPPER);
    }

    public long countRuns(String ruleCode, String status) {
        StringBuilder sql = new StringBuilder(
                "SELECT count(*) FROM mdm_svc.md_dq_run WHERE 1 = 1");
        MapSqlParameterSource params = new MapSqlParameterSource();
        if (ruleCode != null && !ruleCode.isBlank()) {
            sql.append(" AND rule_code = :code");
            params.addValue("code", ruleCode);
        }
        if (status != null && !status.isBlank()) {
            sql.append(" AND status = :status");
            params.addValue("status", status);
        }
        Long n = jdbc.queryForObject(sql.toString(), params, Long.class);
        return n == null ? 0 : n;
    }

    // ------------------------------------------------------------------ 问题

    private static final RowMapper<Issue> ISSUE_MAPPER = (rs, n) -> new Issue(
            rs.getObject("id", UUID.class),
            rs.getString("issue_no"),
            rs.getObject("rule_id", UUID.class),
            rs.getString("rule_code"),
            rs.getString("rule_name"),
            rs.getString("severity"),
            rs.getString("status"),
            rs.getString("owner"),
            rs.getObject("first_run_id", UUID.class),
            rs.getObject("last_run_id", UUID.class),
            rs.getInt("occurrence_count"),
            rs.getBigDecimal("observed_value"),
            rs.getString("acknowledged_by"),
            instant(rs, "acknowledged_at"),
            rs.getString("resolution"),
            instant(rs, "resolved_at"),
            rs.getString("waiver_reason"),
            rs.getString("waiver_by"),
            instant(rs, "waiver_at"),
            rs.getLong("version"),
            instant(rs, "created_at"),
            instant(rs, "updated_at"));

    private static final String ISSUE_COLUMNS = """
            id, issue_no, rule_id, rule_code, rule_name, severity, status, owner, first_run_id,
            last_run_id, occurrence_count, observed_value, acknowledged_by, acknowledged_at,
            resolution, resolved_at, waiver_reason, waiver_by, waiver_at, version, created_at,
            updated_at
            """;

    public String nextIssueNo() {
        Long seq = plain.queryForObject("SELECT nextval('mdm_svc.md_dq_issue_no_seq')", Long.class);
        return "DQI" + LocalDate.now().format(DAY) + String.format("%04d", seq == null ? 1 : seq);
    }

    public Optional<Issue> findOpenIssue(UUID ruleId) {
        return plain.query("SELECT " + ISSUE_COLUMNS + """
                  FROM mdm_svc.md_dq_issue
                 WHERE rule_id = ? AND status IN ('OPEN', 'ACKNOWLEDGED')
                 ORDER BY created_at DESC LIMIT 1
                """, ISSUE_MAPPER, ruleId).stream().findFirst();
    }

    public Optional<Issue> findIssue(UUID id) {
        return plain.query("SELECT " + ISSUE_COLUMNS
                + " FROM mdm_svc.md_dq_issue WHERE id = ?", ISSUE_MAPPER, id).stream().findFirst();
    }

    public List<Issue> listIssues(String status, String severity, int limit, int offset) {
        StringBuilder sql = new StringBuilder("SELECT " + ISSUE_COLUMNS
                + " FROM mdm_svc.md_dq_issue WHERE 1 = 1");
        MapSqlParameterSource params = new MapSqlParameterSource();
        if (status != null && !status.isBlank()) {
            sql.append(" AND status = :status");
            params.addValue("status", status);
        }
        if (severity != null && !severity.isBlank()) {
            sql.append(" AND severity = :severity");
            params.addValue("severity", severity);
        }
        sql.append(" ORDER BY updated_at DESC LIMIT :limit OFFSET :offset");
        params.addValue("limit", limit).addValue("offset", offset);
        return jdbc.query(sql.toString(), params, ISSUE_MAPPER);
    }

    public long countIssues(String status) {
        StringBuilder sql = new StringBuilder(
                "SELECT count(*) FROM mdm_svc.md_dq_issue WHERE 1 = 1");
        MapSqlParameterSource params = new MapSqlParameterSource();
        if (status != null && !status.isBlank()) {
            sql.append(" AND status = :status");
            params.addValue("status", status);
        }
        Long n = jdbc.queryForObject(sql.toString(), params, Long.class);
        return n == null ? 0 : n;
    }

    public long countOpenIssues() {
        Long n = plain.queryForObject("""
                SELECT count(*) FROM mdm_svc.md_dq_issue
                 WHERE status IN ('OPEN', 'ACKNOWLEDGED')
                """, Long.class);
        return n == null ? 0 : n;
    }

    public long countOpenBlockers() {
        Long n = plain.queryForObject("""
                SELECT count(*) FROM mdm_svc.md_dq_issue
                 WHERE status IN ('OPEN', 'ACKNOWLEDGED') AND severity = 'BLOCKER'
                """, Long.class);
        return n == null ? 0 : n;
    }

    public void insertIssue(Issue issue) {
        jdbc.update("""
                INSERT INTO mdm_svc.md_dq_issue
                    (id, issue_no, rule_id, rule_code, rule_name, severity, status, owner,
                     first_run_id, last_run_id, occurrence_count, observed_value)
                VALUES
                    (:id, :no, :rule, :ruleCode, :ruleName, :severity, :status, :owner,
                     :firstRun, :lastRun, 1, :observed)
                """, new MapSqlParameterSource()
                .addValue("id", issue.id())
                .addValue("no", issue.issueNo())
                .addValue("rule", issue.ruleId())
                .addValue("ruleCode", issue.ruleCode())
                .addValue("ruleName", issue.ruleName())
                .addValue("severity", issue.severity())
                .addValue("status", issue.status())
                .addValue("owner", issue.owner())
                .addValue("firstRun", issue.firstRunId())
                .addValue("lastRun", issue.lastRunId())
                .addValue("observed", issue.observedValue()));
    }

    private static final java.util.Set<String> MUTABLE_ISSUE_COLUMNS = java.util.Set.of(
            "status", "last_run_id", "occurrence_count", "observed_value", "acknowledged_by",
            "acknowledged_at", "resolution", "resolved_at", "waiver_reason", "waiver_by",
            "waiver_at");

    public boolean updateIssue(UUID id, long expectedVersion, Map<String, Object> fields) {
        if (fields == null || fields.isEmpty()) {
            return false;
        }
        List<String> sets = new ArrayList<>();
        MapSqlParameterSource params = new MapSqlParameterSource();
        for (Map.Entry<String, Object> entry : fields.entrySet()) {
            if (!MUTABLE_ISSUE_COLUMNS.contains(entry.getKey())) {
                throw new IllegalArgumentException("不允许修改的质量问题字段：" + entry.getKey());
            }
            sets.add(entry.getKey() + " = :" + entry.getKey());
            params.addValue(entry.getKey(), jdbcValue(entry.getValue()));
        }
        params.addValue("id", id).addValue("expected", expectedVersion);
        return jdbc.update("UPDATE mdm_svc.md_dq_issue SET " + String.join(", ", sets)
                + ", version = version + 1, updated_at = now()"
                + " WHERE id = :id AND version = :expected", params) > 0;
    }

    // ------------------------------------------------------------------ 看板

    public long countRunsSince(Instant since, String status) {
        StringBuilder sql = new StringBuilder(
                "SELECT count(*) FROM mdm_svc.md_dq_run WHERE started_at >= :since");
        MapSqlParameterSource params = new MapSqlParameterSource("since", timestamp(since));
        if (status != null && !status.isBlank()) {
            sql.append(" AND status = :status");
            params.addValue("status", status);
        }
        Long n = jdbc.queryForObject(sql.toString(), params, Long.class);
        return n == null ? 0 : n;
    }

    public List<RuleFailure> topFailures(Instant since, int limit) {
        return jdbc.query("""
                SELECT r.code AS rule_code, r.name AS rule_name, count(*) AS failures
                  FROM mdm_svc.md_dq_run u
                  JOIN mdm_svc.md_dq_rule r ON r.id = u.rule_id
                 WHERE u.started_at >= :since AND u.status IN ('FAILED', 'ERROR')
                 GROUP BY r.code, r.name
                 ORDER BY failures DESC, r.code
                 LIMIT :limit
                """, new MapSqlParameterSource("since", timestamp(since)).addValue("limit", limit),
                (rs, n) -> new RuleFailure(rs.getString("rule_code"), rs.getString("rule_name"),
                        rs.getLong("failures")));
    }

    // ------------------------------------------------------------------ 探针

    /**
     * 执行只读探针：外层再包一层 SELECT，确保结果只有一列数值。
     *
     * <p>SQL 白名单由服务层校验；这里只负责超时与类型；无结果行视为异常。
     */
    public BigDecimal probe(String sql) {
        try {
            return probeTemplate.queryForObject(
                    "SELECT * FROM (" + sql + ") dq_probe", BigDecimal.class);
        } catch (EmptyResultDataAccessException e) {
            throw new IllegalStateException("探针没有返回任何结果行", e);
        }
    }

    // ------------------------------------------------------------------ 工具

    private static Object jdbcValue(Object value) {
        return value instanceof Instant instant ? timestamp(instant) : value;
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp ts = rs.getTimestamp(column);
        return ts == null ? null : ts.toInstant();
    }

    private static Timestamp timestamp(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }
}
