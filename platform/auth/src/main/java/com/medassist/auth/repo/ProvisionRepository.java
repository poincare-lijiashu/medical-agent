package com.medassist.auth.repo;

import com.medassist.auth.domain.Provisioning;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * 开通任务仓储（§284）。
 *
 * <p>两步写入（job + step）刻意不用一个大事务包住整条流水线：
 * 每个步骤都是外部副作用（连库、建账号），把它们放进一个长事务既锁不住外部系统，
 * 也会让失败现场在回滚后消失。这里选择"每步提交 + 任务终态回写"。
 */
@Repository
public class ProvisionRepository {

    private final JdbcClient jdbc;
    private final ObjectMapper json;

    public ProvisionRepository(JdbcClient jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    public void insertJob(Provisioning.Job job) {
        jdbc.sql("""
                        INSERT INTO auth_svc.provision_job
                            (id, tenant_code, instance_code, status, requested_by, request,
                             result, started_at)
                        VALUES (:id, :tenant, :instance, :status, :actor,
                                CAST(:request AS jsonb), CAST(:result AS jsonb), :started)
                        """)
                .param("id", job.id())
                .param("tenant", job.tenantCode())
                .param("instance", job.instanceCode())
                .param("status", job.status())
                .param("actor", job.requestedBy())
                .param("request", write(job.request()))
                .param("result", write(job.result()))
                .param("started", Timestamp.from(job.startedAt()))
                .update();
    }

    /**
     * 步骤结果 upsert。
     *
     * <p>保留第一次的 {@code started_at}：resume 时页面要看到"这个步骤一共折腾了多久"，
     * 而不只是最后一次尝试的耗时。
     */
    public void upsertStep(Provisioning.Step step) {
        jdbc.sql("""
                        INSERT INTO auth_svc.provision_step
                            (job_id, step_no, step_code, status, detail, error, started_at, finished_at)
                        VALUES (:job, :no, :code, :status, CAST(:detail AS jsonb), :error,
                                :started, :finished)
                        ON CONFLICT (job_id, step_code) DO UPDATE
                           SET step_no = EXCLUDED.step_no,
                               status = EXCLUDED.status,
                               detail = EXCLUDED.detail,
                               error = EXCLUDED.error,
                               finished_at = EXCLUDED.finished_at
                        """)
                .param("job", step.jobId())
                .param("no", step.stepNo())
                .param("code", step.code())
                .param("status", step.status())
                .param("detail", write(step.detail()))
                .param("error", step.error())
                .param("started", step.startedAt() == null ? null : Timestamp.from(step.startedAt()))
                .param("finished", step.finishedAt() == null ? null : Timestamp.from(step.finishedAt()))
                .update();
    }

    public void finishJob(UUID id, String status, Map<String, Object> result, Instant finishedAt) {
        jdbc.sql("""
                        UPDATE auth_svc.provision_job
                           SET status = :status, result = CAST(:result AS jsonb), finished_at = :finished
                         WHERE id = :id
                        """)
                .param("status", status)
                .param("result", write(result))
                .param("finished", Timestamp.from(finishedAt))
                .param("id", id)
                .update();
    }

    /** resume：把失败任务重新置为 RUNNING（finished_at 清空，避免"已完成又一失败"的歧义）。 */
    public void markRunning(UUID id) {
        jdbc.sql("""
                        UPDATE auth_svc.provision_job
                           SET status = 'RUNNING', finished_at = NULL
                         WHERE id = :id
                        """)
                .param("id", id)
                .update();
    }

    public Optional<Provisioning.Job> findJob(UUID id) {
        return jdbc.sql("SELECT * FROM auth_svc.provision_job WHERE id = ?")
                .param(id)
                .query((rs, i) -> new Provisioning.Job(
                        rs.getObject("id", UUID.class),
                        rs.getString("tenant_code"),
                        rs.getString("instance_code"),
                        rs.getString("status"),
                        rs.getString("requested_by"),
                        read(rs.getString("request")),
                        read(rs.getString("result")),
                        instant(rs, "started_at"),
                        instant(rs, "finished_at"),
                        stepsOf(rs.getObject("id", UUID.class))))
                .optional();
    }

    public List<Provisioning.Job> listJobs(String tenantCode, int limit) {
        String sql = tenantCode == null || tenantCode.isBlank()
                ? "SELECT * FROM auth_svc.provision_job ORDER BY started_at DESC LIMIT ?"
                : "SELECT * FROM auth_svc.provision_job WHERE tenant_code = ? ORDER BY started_at DESC LIMIT ?";
        JdbcClient.StatementSpec spec = jdbc.sql(sql);
        if (tenantCode == null || tenantCode.isBlank()) {
            spec = spec.param(limit);
        } else {
            spec = spec.param(tenantCode).param(limit);
        }
        return spec.query((rs, i) -> new Provisioning.Job(
                        rs.getObject("id", UUID.class),
                        rs.getString("tenant_code"),
                        rs.getString("instance_code"),
                        rs.getString("status"),
                        rs.getString("requested_by"),
                        read(rs.getString("request")),
                        read(rs.getString("result")),
                        instant(rs, "started_at"),
                        instant(rs, "finished_at"),
                        stepsOf(rs.getObject("id", UUID.class))))
                .list();
    }

    /** 某租户最近一次开通任务（健康度用）。 */
    public Optional<Provisioning.Job> latestForTenant(String tenantCode) {
        return jdbc.sql("""
                        SELECT * FROM auth_svc.provision_job
                         WHERE tenant_code = ?
                         ORDER BY started_at DESC LIMIT 1
                        """)
                .param(tenantCode)
                .query((rs, i) -> new Provisioning.Job(
                        rs.getObject("id", UUID.class),
                        rs.getString("tenant_code"),
                        rs.getString("instance_code"),
                        rs.getString("status"),
                        rs.getString("requested_by"),
                        read(rs.getString("request")),
                        read(rs.getString("result")),
                        instant(rs, "started_at"),
                        instant(rs, "finished_at"),
                        stepsOf(rs.getObject("id", UUID.class))))
                .optional();
    }

    private List<Provisioning.Step> stepsOf(UUID jobId) {
        return jdbc.sql("""
                        SELECT * FROM auth_svc.provision_step
                         WHERE job_id = ? ORDER BY step_no
                        """)
                .param(jobId)
                .query((rs, i) -> new Provisioning.Step(
                        jobId,
                        rs.getInt("step_no"),
                        rs.getString("step_code"),
                        rs.getString("status"),
                        read(rs.getString("detail")),
                        rs.getString("error"),
                        instant(rs, "started_at"),
                        instant(rs, "finished_at")))
                .list();
    }

    private static Instant instant(java.sql.ResultSet rs, String column) throws java.sql.SQLException {
        Timestamp ts = rs.getTimestamp(column);
        return ts == null ? null : ts.toInstant();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> read(String raw) {
        if (raw == null || raw.isBlank()) {
            return Map.of();
        }
        try {
            Map<String, Object> value = json.readValue(raw, Map.class);
            return value == null ? Map.of() : value;
        } catch (JacksonException e) {
            return Map.of("_parse_failed", true);
        }
    }

    private String write(Map<String, Object> value) {
        try {
            return json.writeValueAsString(value == null ? Map.of() : value);
        } catch (JacksonException e) {
            return "{}";
        }
    }
}
