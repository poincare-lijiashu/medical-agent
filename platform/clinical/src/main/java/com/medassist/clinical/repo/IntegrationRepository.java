package com.medassist.clinical.repo;

import com.medassist.clinical.domain.IntegrationMessage;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 集成消息台账仓储（第 257 批）。
 *
 * <p>认领用 {@code FOR UPDATE SKIP LOCKED}：多实例部署时每个实例只拿自己锁到的行，
 * 不会重复投递；SENDING 超过回收窗口会被重新认领，用于进程崩溃后的自愈。
 */
@Repository
public class IntegrationRepository {

    /** SENDING 超过该时长视为"发送进程已死"，可被重新认领。 */
    private static final String RECLAIM_WINDOW = "3 minutes";

    private static final String COLUMNS = """
            id, system_code, message_type, business_key, payload, status, attempts,
            next_retry_at, last_error, external_id, created_by, locked_at, created_at, version
            """;

    private static final org.springframework.jdbc.core.RowMapper<IntegrationMessage> MAPPER =
            (rs, i) -> new IntegrationMessage(
                    rs.getObject("id", UUID.class),
                    rs.getString("system_code"),
                    rs.getString("message_type"),
                    rs.getString("business_key"),
                    rs.getString("payload"),
                    rs.getString("status"),
                    rs.getInt("attempts"),
                    rs.getTimestamp("next_retry_at") == null
                            ? null : rs.getTimestamp("next_retry_at").toInstant(),
                    rs.getString("last_error"),
                    rs.getString("external_id"),
                    rs.getString("created_by"),
                    rs.getTimestamp("locked_at") == null
                            ? null : rs.getTimestamp("locked_at").toInstant(),
                    rs.getTimestamp("created_at").toInstant(),
                    rs.getLong("version"));

    private final JdbcClient jdbc;

    public IntegrationRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(IntegrationMessage m) {
        jdbc.sql("""
                        INSERT INTO clinical_svc.integration_message
                            (id, system_code, message_type, business_key, payload, status,
                             attempts, next_retry_at, created_by, version)
                        VALUES (:id, :systemCode, :messageType, :businessKey, :payload, :status,
                                :attempts, :nextRetryAt, :createdBy, 0)
                        """)
                .param("id", m.id()).param("systemCode", m.systemCode())
                .param("messageType", m.messageType()).param("businessKey", m.businessKey())
                .param("payload", m.payload()).param("status", m.status())
                .param("attempts", m.attempts()).param("nextRetryAt", m.nextRetryAt())
                .param("createdBy", m.createdBy())
                .update();
    }

    public Optional<IntegrationMessage> findById(UUID id) {
        return jdbc.sql("SELECT " + COLUMNS
                        + " FROM clinical_svc.integration_message WHERE id = ?")
                .param(id).query(MAPPER).optional();
    }

    public List<IntegrationMessage> findByIds(List<UUID> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        return jdbc.sql("SELECT " + COLUMNS + """
                           FROM clinical_svc.integration_message
                          WHERE id IN (:ids)
                          ORDER BY next_retry_at
                          """)
                .param("ids", ids).query(MAPPER).list();
    }

    /**
     * 锁定一批待发消息；必须在事务内调用。
     *
     * <p>排序按 next_retry_at：先到期的先发，避免失败消息一直压在队尾。
     */
    public List<UUID> lockDueIds(int batch) {
        return jdbc.sql("""
                        SELECT id
                          FROM clinical_svc.integration_message
                         WHERE (status = 'PENDING' AND next_retry_at <= now())
                            OR (status = 'SENDING' AND locked_at < now() - CAST(:window AS interval))
                         ORDER BY next_retry_at
                         LIMIT :batch
                         FOR UPDATE SKIP LOCKED
                        """)
                .param("window", RECLAIM_WINDOW).param("batch", batch)
                .query((rs, i) -> rs.getObject("id", UUID.class)).list();
    }

    public void markSending(List<UUID> ids) {
        if (ids == null || ids.isEmpty()) {
            return;
        }
        jdbc.sql("""
                        UPDATE clinical_svc.integration_message
                           SET status = 'SENDING', locked_at = now(), version = version + 1,
                               updated_at = now()
                         WHERE id IN (:ids)
                        """)
                .param("ids", ids).update();
    }

    /** 对端确认；expectedVersion 保证不会覆盖别的实例刚写入的结果。 */
    public boolean markAcked(UUID id, String externalId, long expectedVersion) {
        return jdbc.sql("""
                        UPDATE clinical_svc.integration_message
                           SET status = 'ACKED', external_id = :externalId, last_error = NULL,
                               locked_at = NULL, attempts = attempts + 1, version = version + 1,
                               updated_at = now()
                         WHERE id = :id AND version = :version
                        """)
                .param("id", id).param("externalId", externalId).param("version", expectedVersion)
                .update() > 0;
    }

    /** 发送失败：写入错误、重试次数与下次重试时间（由服务层算好；dead=true 表示不再自动重试）。 */
    public boolean markFailed(UUID id, int attempts, Instant nextRetryAt, String error,
                              boolean dead, long expectedVersion) {
        return jdbc.sql("""
                        UPDATE clinical_svc.integration_message
                           SET status = CASE WHEN :dead THEN 'DEAD' ELSE 'FAILED' END,
                               attempts = :attempts, next_retry_at = :nextRetryAt,
                               last_error = :error, locked_at = NULL, version = version + 1,
                               updated_at = now()
                         WHERE id = :id AND version = :version
                        """)
                .param("id", id).param("attempts", attempts).param("nextRetryAt", nextRetryAt)
                .param("error", error).param("dead", dead)
                .param("version", expectedVersion)
                .update() > 0;
    }

    /** 人工重投：FAILED/DEAD → PENDING，清零重试次数并立即到期。 */
    public boolean resetForRetry(UUID id, long expectedVersion) {
        return jdbc.sql("""
                        UPDATE clinical_svc.integration_message
                           SET status = 'PENDING', attempts = 0, next_retry_at = now(),
                               locked_at = NULL, version = version + 1, updated_at = now()
                         WHERE id = :id AND status IN ('FAILED', 'DEAD') AND version = :version
                        """)
                .param("id", id).param("version", expectedVersion)
                .update() > 0;
    }

    public List<IntegrationMessage> list(String status, String systemCode, int limit) {
        return jdbc.sql("SELECT " + COLUMNS + """
                           FROM clinical_svc.integration_message
                          WHERE (CAST(:status AS text) IS NULL OR status = CAST(:status AS text))
                            AND (CAST(:systemCode AS text) IS NULL
                                 OR system_code = CAST(:systemCode AS text))
                          ORDER BY created_at DESC
                          LIMIT :limit
                          """)
                .param("status", status).param("systemCode", systemCode)
                .param("limit", limit).query(MAPPER).list();
    }

    public List<StatusCount> countByStatus() {
        return jdbc.sql("""
                        SELECT status, count(*) AS n
                          FROM clinical_svc.integration_message
                         GROUP BY status
                        """)
                .query((rs, i) -> new StatusCount(rs.getString("status"), rs.getInt("n")))
                .list();
    }

    public List<SystemStatusCount> countBySystem() {
        return jdbc.sql("""
                        SELECT system_code, status, count(*) AS n
                          FROM clinical_svc.integration_message
                         GROUP BY system_code, status
                         ORDER BY system_code
                        """)
                .query((rs, i) -> new SystemStatusCount(rs.getString("system_code"),
                        rs.getString("status"), rs.getInt("n")))
                .list();
    }

    public record StatusCount(String status, int count) {
    }

    public record SystemStatusCount(String systemCode, String status, int count) {
    }
}
