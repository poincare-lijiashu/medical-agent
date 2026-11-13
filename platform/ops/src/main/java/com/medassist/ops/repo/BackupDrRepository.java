package com.medassist.ops.repo;

import com.medassist.ops.domain.OpsModel.BackupPolicy;
import com.medassist.ops.domain.OpsModel.BackupRun;
import com.medassist.ops.domain.OpsModel.DrDrill;
import com.medassist.ops.domain.OpsModel.DrDrillStep;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 备份与灾备仓储（ops_svc）。
 *
 * <p>纪律：
 * <ul>
 *   <li>备份运行以 {@code idempotency_key} 唯一，RUNNING 只允许被终结一次（带 version CAS）；</li>
 *   <li>演练状态推进全部带 version，避免两个人同时"判定通过"；</li>
 *   <li>步骤证据只落文本地址（日志/截图/还原结果），不把大文件塞进数据库。</li>
 * </ul>
 */
@Repository
public class BackupDrRepository {

    private final NamedParameterJdbcTemplate jdbc;
    private final JdbcTemplate plain;

    public BackupDrRepository(NamedParameterJdbcTemplate jdbc, JdbcTemplate plain) {
        this.jdbc = jdbc;
        this.plain = plain;
    }

    // ------------------------------------------------------------------ 备份策略

    private static final RowMapper<BackupPolicy> POLICY_MAPPER = (rs, n) -> new BackupPolicy(
            rs.getObject("id", UUID.class),
            rs.getString("code"),
            rs.getString("name"),
            rs.getString("scope_type"),
            rs.getString("scope_ref"),
            rs.getString("backup_type"),
            rs.getString("schedule_cron"),
            rs.getInt("retention_days"),
            rs.getString("encryption"),
            rs.getString("storage_target"),
            rs.getInt("rpo_minutes"),
            rs.getString("owner"),
            rs.getString("status"),
            instant(rs, "last_run_at"),
            rs.getLong("version"),
            instant(rs, "created_at"),
            instant(rs, "updated_at"));

    private static final String POLICY_COLUMNS = """
            id, code, name, scope_type, scope_ref, backup_type, schedule_cron, retention_days,
            encryption, storage_target, rpo_minutes, owner, status, last_run_at, version,
            created_at, updated_at
            """;

    public List<BackupPolicy> listPolicies(boolean onlyActive) {
        return plain.query("SELECT " + POLICY_COLUMNS + " FROM ops_svc.ops_backup_policy"
                + (onlyActive ? " WHERE status = 'ACTIVE'" : "")
                + " ORDER BY code", POLICY_MAPPER);
    }

    public Optional<BackupPolicy> findPolicyByCode(String code) {
        return plain.query("SELECT " + POLICY_COLUMNS
                + " FROM ops_svc.ops_backup_policy WHERE code = ?", POLICY_MAPPER, code)
                .stream().findFirst();
    }

    public void upsertPolicy(BackupPolicy policy) {
        jdbc.update("""
                INSERT INTO ops_svc.ops_backup_policy
                    (id, code, name, scope_type, scope_ref, backup_type, schedule_cron,
                     retention_days, encryption, storage_target, rpo_minutes, owner, status)
                VALUES (:id, :code, :name, :scopeType, :scopeRef, :backupType, :cron,
                        :retention, :encryption, :storage, :rpo, :owner, :status)
                ON CONFLICT (code) DO UPDATE SET
                    name = EXCLUDED.name, scope_type = EXCLUDED.scope_type,
                    scope_ref = EXCLUDED.scope_ref, backup_type = EXCLUDED.backup_type,
                    schedule_cron = EXCLUDED.schedule_cron,
                    retention_days = EXCLUDED.retention_days, encryption = EXCLUDED.encryption,
                    storage_target = EXCLUDED.storage_target, rpo_minutes = EXCLUDED.rpo_minutes,
                    owner = EXCLUDED.owner, status = EXCLUDED.status,
                    version = ops_svc.ops_backup_policy.version + 1, updated_at = now()
                """, new MapSqlParameterSource()
                .addValue("id", policy.id() == null ? UUID.randomUUID() : policy.id())
                .addValue("code", policy.code())
                .addValue("name", policy.name())
                .addValue("scopeType", policy.scopeType())
                .addValue("scopeRef", policy.scopeRef())
                .addValue("backupType", policy.backupType())
                .addValue("cron", policy.scheduleCron())
                .addValue("retention", policy.retentionDays())
                .addValue("encryption", policy.encryption())
                .addValue("storage", policy.storageTarget())
                .addValue("rpo", policy.rpoMinutes())
                .addValue("owner", policy.owner())
                .addValue("status", policy.status()));
    }

    // ------------------------------------------------------------------ 备份运行

    private static final RowMapper<BackupRun> RUN_MAPPER = (rs, n) -> new BackupRun(
            rs.getObject("id", UUID.class),
            rs.getString("run_no"),
            rs.getObject("policy_id", UUID.class),
            rs.getString("policy_code"),
            rs.getString("backup_type"),
            rs.getString("status"),
            rs.getString("idempotency_key"),
            rs.getObject("size_bytes", Long.class),
            rs.getString("checksum"),
            rs.getString("storage_location"),
            instant(rs, "started_at"),
            instant(rs, "finished_at"),
            rs.getString("error_message"),
            rs.getString("reported_by"),
            rs.getLong("version"),
            instant(rs, "created_at"),
            instant(rs, "updated_at"));

    private static final String RUN_COLUMNS = """
            id, run_no, policy_id, policy_code, backup_type, status, idempotency_key,
            size_bytes, checksum, storage_location, started_at, finished_at, error_message,
            reported_by, version, created_at, updated_at
            """;

    public String nextRunNo() {
        Long seq = plain.queryForObject("SELECT nextval('ops_svc.ops_backup_run_no_seq')",
                Long.class);
        return "BK" + java.time.LocalDate.now()
                .format(java.time.format.DateTimeFormatter.BASIC_ISO_DATE)
                + String.format("%05d", seq == null ? 1 : seq);
    }

    public Optional<BackupRun> findRunByIdempotency(String key) {
        return plain.query("SELECT " + RUN_COLUMNS
                + " FROM ops_svc.ops_backup_run WHERE idempotency_key = ?", RUN_MAPPER, key)
                .stream().findFirst();
    }

    public Optional<BackupRun> findRun(UUID id) {
        return plain.query("SELECT " + RUN_COLUMNS
                + " FROM ops_svc.ops_backup_run WHERE id = ?", RUN_MAPPER, id).stream().findFirst();
    }

    public List<BackupRun> listRuns(String policyCode, String status, int limit, int offset) {
        StringBuilder sql = new StringBuilder("SELECT " + RUN_COLUMNS
                + " FROM ops_svc.ops_backup_run WHERE 1 = 1");
        MapSqlParameterSource params = new MapSqlParameterSource();
        if (policyCode != null && !policyCode.isBlank()) {
            sql.append(" AND policy_code = :policy");
            params.addValue("policy", policyCode);
        }
        if (status != null && !status.isBlank()) {
            sql.append(" AND status = :status");
            params.addValue("status", status);
        }
        sql.append(" ORDER BY started_at DESC, run_no DESC LIMIT :limit OFFSET :offset");
        params.addValue("limit", limit).addValue("offset", offset);
        return jdbc.query(sql.toString(), params, RUN_MAPPER);
    }

    public long countRuns(String policyCode, String status) {
        StringBuilder sql = new StringBuilder(
                "SELECT count(*) FROM ops_svc.ops_backup_run WHERE 1 = 1");
        MapSqlParameterSource params = new MapSqlParameterSource();
        if (policyCode != null && !policyCode.isBlank()) {
            sql.append(" AND policy_code = :policy");
            params.addValue("policy", policyCode);
        }
        if (status != null && !status.isBlank()) {
            sql.append(" AND status = :status");
            params.addValue("status", status);
        }
        Long n = jdbc.queryForObject(sql.toString(), params, Long.class);
        return n == null ? 0 : n;
    }

    public void insertRun(BackupRun run) {
        jdbc.update("""
                INSERT INTO ops_svc.ops_backup_run
                    (id, run_no, policy_id, policy_code, backup_type, status, idempotency_key,
                     size_bytes, checksum, storage_location, started_at, finished_at,
                     error_message, reported_by)
                VALUES
                    (:id, :no, :policyId, :policyCode, :type, :status, :key,
                     :size, :checksum, :location, :started, :finished, :error, :reportedBy)
                """, new MapSqlParameterSource()
                .addValue("id", run.id())
                .addValue("no", run.runNo())
                .addValue("policyId", run.policyId())
                .addValue("policyCode", run.policyCode())
                .addValue("type", run.backupType())
                .addValue("status", run.status())
                .addValue("key", run.idempotencyKey())
                .addValue("size", run.sizeBytes())
                .addValue("checksum", run.checksum())
                .addValue("location", run.storageLocation())
                .addValue("started", timestamp(run.startedAt()))
                .addValue("finished", timestamp(run.finishedAt()))
                .addValue("error", run.errorMessage())
                .addValue("reportedBy", run.reportedBy()));
    }

    /** 终结 RUNNING 运行：只有仍处于 RUNNING 且版本一致才允许，返回是否成功。 */
    public boolean finalizeRun(UUID id, long expectedVersion, String status, Instant finishedAt,
                               Long sizeBytes, String checksum, String storageLocation,
                               String errorMessage) {
        return jdbc.update("""
                UPDATE ops_svc.ops_backup_run
                   SET status = :status, finished_at = :finished, size_bytes = :size,
                       checksum = :checksum, storage_location = :location,
                       error_message = :error, version = version + 1, updated_at = now()
                 WHERE id = :id AND version = :expected AND status = 'RUNNING'
                """, new MapSqlParameterSource()
                .addValue("id", id)
                .addValue("expected", expectedVersion)
                .addValue("status", status)
                .addValue("finished", timestamp(finishedAt))
                .addValue("size", sizeBytes)
                .addValue("checksum", checksum)
                .addValue("location", storageLocation)
                .addValue("error", errorMessage)) > 0;
    }

    public void touchPolicyLastRun(UUID policyId, Instant lastRunAt) {
        plain.update("""
                UPDATE ops_svc.ops_backup_policy
                   SET last_run_at = ?, updated_at = now()
                 WHERE id = ?
                """, timestamp(lastRunAt), policyId);
    }

    // ------------------------------------------------------------------ 灾备演练

    private static final RowMapper<DrDrill> DRILL_MAPPER = (rs, n) -> new DrDrill(
            rs.getObject("id", UUID.class),
            rs.getString("drill_no"),
            rs.getString("title"),
            rs.getString("scope"),
            rs.getString("environment"),
            rs.getString("status"),
            rs.getObject("backup_run_id", UUID.class),
            rs.getInt("rto_target_minutes"),
            rs.getObject("rto_actual_minutes", Integer.class),
            rs.getInt("rpo_target_minutes"),
            rs.getObject("rpo_actual_minutes", Integer.class),
            rs.getObject("data_loss_minutes", Integer.class),
            rs.getBoolean("consistency_verified"),
            rs.getString("executor"),
            rs.getString("verifier"),
            instant(rs, "verified_at"),
            rs.getString("evidence"),
            rs.getString("note"),
            instant(rs, "started_at"),
            instant(rs, "completed_at"),
            rs.getLong("version"),
            instant(rs, "created_at"),
            instant(rs, "updated_at"),
            List.of());

    private static final String DRILL_COLUMNS = """
            id, drill_no, title, scope, environment, status, backup_run_id,
            rto_target_minutes, rto_actual_minutes, rpo_target_minutes, rpo_actual_minutes,
            data_loss_minutes, consistency_verified, executor, verifier, verified_at,
            evidence, note, started_at, completed_at, version, created_at, updated_at
            """;

    public String nextDrillNo() {
        Long seq = plain.queryForObject("SELECT nextval('ops_svc.ops_dr_drill_no_seq')",
                Long.class);
        return "DR" + java.time.LocalDate.now()
                .format(java.time.format.DateTimeFormatter.BASIC_ISO_DATE)
                + String.format("%04d", seq == null ? 1 : seq);
    }

    public void insertDrill(DrDrill drill) {
        jdbc.update("""
                INSERT INTO ops_svc.ops_dr_drill
                    (id, drill_no, title, scope, environment, status, backup_run_id,
                     rto_target_minutes, rpo_target_minutes, executor, note)
                VALUES
                    (:id, :no, :title, :scope, :environment, :status, :backupRun,
                     :rtoTarget, :rpoTarget, :executor, :note)
                """, new MapSqlParameterSource()
                .addValue("id", drill.id())
                .addValue("no", drill.drillNo())
                .addValue("title", drill.title())
                .addValue("scope", drill.scope())
                .addValue("environment", drill.environment())
                .addValue("status", drill.status())
                .addValue("backupRun", drill.backupRunId())
                .addValue("rtoTarget", drill.rtoTargetMinutes())
                .addValue("rpoTarget", drill.rpoTargetMinutes())
                .addValue("executor", drill.executor())
                .addValue("note", drill.note()));
    }

    public void insertSteps(List<DrDrillStep> steps) {
        for (DrDrillStep step : steps) {
            jdbc.update("""
                    INSERT INTO ops_svc.ops_dr_drill_step
                        (id, drill_id, step_no, name, status, required, evidence, note, actor)
                    VALUES (:id, :drill, :no, :name, 'PENDING', :required, NULL, NULL, NULL)
                    """, new MapSqlParameterSource()
                    .addValue("id", step.id())
                    .addValue("drill", step.drillId())
                    .addValue("no", step.stepNo())
                    .addValue("name", step.name())
                    .addValue("required", step.required()));
        }
    }

    public Optional<DrDrill> findDrill(UUID id) {
        return plain.query("SELECT " + DRILL_COLUMNS
                + " FROM ops_svc.ops_dr_drill WHERE id = ?", DRILL_MAPPER, id).stream().findFirst();
    }

    public List<DrDrill> listDrills(String scope, String status, int limit, int offset) {
        StringBuilder sql = new StringBuilder("SELECT " + DRILL_COLUMNS
                + " FROM ops_svc.ops_dr_drill WHERE 1 = 1");
        MapSqlParameterSource params = new MapSqlParameterSource();
        if (scope != null && !scope.isBlank()) {
            sql.append(" AND scope = :scope");
            params.addValue("scope", scope);
        }
        if (status != null && !status.isBlank()) {
            sql.append(" AND status = :status");
            params.addValue("status", status);
        }
        sql.append(" ORDER BY created_at DESC LIMIT :limit OFFSET :offset");
        params.addValue("limit", limit).addValue("offset", offset);
        return jdbc.query(sql.toString(), params, DRILL_MAPPER);
    }

    public long countDrills(String scope, String status) {
        StringBuilder sql = new StringBuilder(
                "SELECT count(*) FROM ops_svc.ops_dr_drill WHERE 1 = 1");
        MapSqlParameterSource params = new MapSqlParameterSource();
        if (scope != null && !scope.isBlank()) {
            sql.append(" AND scope = :scope");
            params.addValue("scope", scope);
        }
        if (status != null && !status.isBlank()) {
            sql.append(" AND status = :status");
            params.addValue("status", status);
        }
        Long n = jdbc.queryForObject(sql.toString(), params, Long.class);
        return n == null ? 0 : n;
    }

    /** 最近一次已通过且已复核的演练（发布门禁用）。 */
    public Optional<DrDrill> findLatestPassed(String scope, String environment, Instant since) {
        return plain.query("SELECT " + DRILL_COLUMNS + """
                  FROM ops_svc.ops_dr_drill
                 WHERE status = 'PASSED'
                   AND environment = ?
                   AND verifier IS NOT NULL
                   AND (scope = ? OR scope = 'ALL')
                   AND completed_at >= ?
                 ORDER BY completed_at DESC
                 LIMIT 1
                """, DRILL_MAPPER, environment, scope, timestamp(since)).stream().findFirst();
    }

    public boolean markRunning(UUID id, long expectedVersion, Instant startedAt) {
        return plain.update("""
                UPDATE ops_svc.ops_dr_drill
                   SET status = 'RUNNING', started_at = ?, version = version + 1, updated_at = now()
                 WHERE id = ? AND version = ? AND status = 'PLANNED'
                """, timestamp(startedAt), id, expectedVersion) > 0;
    }

    public boolean complete(UUID id, long expectedVersion, int rtoActual, int rpoActual,
                            Integer dataLossMinutes, boolean consistencyVerified, String evidence,
                            String note, String verifier, Instant now) {
        return jdbc.update("""
                UPDATE ops_svc.ops_dr_drill
                   SET status = 'PASSED', rto_actual_minutes = :rto, rpo_actual_minutes = :rpo,
                       data_loss_minutes = :loss, consistency_verified = :consistency,
                       evidence = :evidence, note = :note, verifier = :verifier,
                       verified_at = :now, completed_at = :now,
                       version = version + 1, updated_at = now()
                 WHERE id = :id AND version = :expected AND status = 'RUNNING'
                """, new MapSqlParameterSource()
                .addValue("id", id)
                .addValue("expected", expectedVersion)
                .addValue("rto", rtoActual)
                .addValue("rpo", rpoActual)
                .addValue("loss", dataLossMinutes)
                .addValue("consistency", consistencyVerified)
                .addValue("evidence", evidence)
                .addValue("note", note)
                .addValue("verifier", verifier)
                .addValue("now", timestamp(now))) > 0;
    }

    public boolean terminate(UUID id, long expectedVersion, String status, String note, Instant now) {
        return jdbc.update("""
                UPDATE ops_svc.ops_dr_drill
                   SET status = :status, note = :note, completed_at = :now,
                       version = version + 1, updated_at = now()
                 WHERE id = :id AND version = :expected AND status IN ('PLANNED', 'RUNNING')
                """, new MapSqlParameterSource()
                .addValue("id", id)
                .addValue("expected", expectedVersion)
                .addValue("status", status)
                .addValue("note", note)
                .addValue("now", timestamp(now))) > 0;
    }

    // ------------------------------------------------------------------ 演练步骤

    private static final RowMapper<DrDrillStep> STEP_MAPPER = (rs, n) -> new DrDrillStep(
            rs.getObject("id", UUID.class),
            rs.getObject("drill_id", UUID.class),
            rs.getInt("step_no"),
            rs.getString("name"),
            rs.getString("status"),
            rs.getBoolean("required"),
            rs.getString("evidence"),
            rs.getString("note"),
            rs.getString("actor"),
            instant(rs, "started_at"),
            instant(rs, "completed_at"));

    public List<DrDrillStep> listSteps(UUID drillId) {
        return plain.query("""
                SELECT id, drill_id, step_no, name, status, required, evidence, note, actor,
                       started_at, completed_at
                  FROM ops_svc.ops_dr_drill_step WHERE drill_id = ? ORDER BY step_no
                """, STEP_MAPPER, drillId);
    }

    public boolean updateStep(UUID drillId, int stepNo, String status, String evidence,
                              String note, String actor, Instant now) {
        return jdbc.update("""
                UPDATE ops_svc.ops_dr_drill_step
                   SET status = :status, evidence = :evidence, note = :note, actor = :actor,
                       started_at = coalesce(started_at, CAST(:now AS timestamptz)),
                       completed_at = CASE WHEN :cleared THEN NULL::timestamptz
                                           ELSE CAST(:now AS timestamptz) END
                 WHERE drill_id = :drill AND step_no = :no
                """, new MapSqlParameterSource()
                .addValue("drill", drillId)
                .addValue("no", stepNo)
                .addValue("status", status)
                .addValue("evidence", evidence)
                .addValue("note", note)
                .addValue("actor", actor)
                .addValue("cleared", "PENDING".equals(status))
                .addValue("now", timestamp(now))) > 0;
    }

    public long countStepsNotPassed(UUID drillId) {
        Long n = plain.queryForObject("""
                SELECT count(*) FROM ops_svc.ops_dr_drill_step
                 WHERE drill_id = ? AND required = true AND status <> 'PASSED'
                """, Long.class, drillId);
        return n == null ? 0 : n;
    }

    // ------------------------------------------------------------------ 工具

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp ts = rs.getTimestamp(column);
        return ts == null ? null : ts.toInstant();
    }

    private static Timestamp timestamp(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }
}
