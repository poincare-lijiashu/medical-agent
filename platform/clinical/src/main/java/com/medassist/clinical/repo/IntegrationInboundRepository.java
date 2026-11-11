package com.medassist.clinical.repo;

import com.medassist.clinical.domain.IntegrationInboundMessage;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 入站集成消息仓储（第 258 批）。
 *
 * <p>幂等由数据库唯一索引兜底（而不是"先查再插"）：并发重推时只有一条能插入成功，
 * 另一条走异常分支读回已有记录，避免检查与写入之间的竞态。
 */
@Repository
public class IntegrationInboundRepository {

    private static final String COLUMNS = """
            id, system_code, message_type, external_id, business_key, payload, payload_hash,
            status, error, received_by, processed_at, created_at, version
            """;

    private static final org.springframework.jdbc.core.RowMapper<IntegrationInboundMessage> MAPPER =
            (rs, i) -> new IntegrationInboundMessage(
                    rs.getObject("id", UUID.class),
                    rs.getString("system_code"),
                    rs.getString("message_type"),
                    rs.getString("external_id"),
                    rs.getString("business_key"),
                    rs.getString("payload"),
                    rs.getString("payload_hash"),
                    rs.getString("status"),
                    rs.getString("error"),
                    rs.getString("received_by"),
                    rs.getTimestamp("processed_at") == null
                            ? null : rs.getTimestamp("processed_at").toInstant(),
                    rs.getTimestamp("created_at").toInstant(),
                    rs.getLong("version"));

    private final JdbcClient jdbc;

    public IntegrationInboundRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(IntegrationInboundMessage m) {
        jdbc.sql("""
                        INSERT INTO clinical_svc.integration_inbound
                            (id, system_code, message_type, external_id, business_key, payload,
                             payload_hash, status, received_by, version)
                        VALUES (:id, :systemCode, :messageType, :externalId, :businessKey, :payload,
                                :payloadHash, :status, :receivedBy, 0)
                        """)
                .param("id", m.id()).param("systemCode", m.systemCode())
                .param("messageType", m.messageType()).param("externalId", m.externalId())
                .param("businessKey", m.businessKey()).param("payload", m.payload())
                .param("payloadHash", m.payloadHash()).param("status", m.status())
                .param("receivedBy", m.receivedBy()).update();
    }

    public Optional<IntegrationInboundMessage> findByExternalId(String systemCode,
                                                               String externalId) {
        return jdbc.sql("SELECT " + COLUMNS + """
                           FROM clinical_svc.integration_inbound
                          WHERE system_code = :systemCode AND external_id = :externalId
                          """)
                .param("systemCode", systemCode).param("externalId", externalId)
                .query(MAPPER).optional();
    }

    public Optional<IntegrationInboundMessage> findById(UUID id) {
        return jdbc.sql("SELECT " + COLUMNS
                        + " FROM clinical_svc.integration_inbound WHERE id = ?")
                .param(id).query(MAPPER).optional();
    }

    /** 处理结果落库（CAS）；PROCESSED/FAILED 只能从 RECEIVED 迁移。 */
    public boolean markProcessed(UUID id, String status, String error, long expectedVersion) {
        return jdbc.sql("""
                        UPDATE clinical_svc.integration_inbound
                           SET status = :status, error = :error, processed_at = now(),
                               version = version + 1
                         WHERE id = :id AND status = 'RECEIVED' AND version = :version
                        """)
                .param("id", id).param("status", status).param("error", error)
                .param("version", expectedVersion).update() > 0;
    }

    public List<IntegrationInboundMessage> list(String status, String systemCode, int limit) {
        return jdbc.sql("SELECT " + COLUMNS + """
                           FROM clinical_svc.integration_inbound
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
                          FROM clinical_svc.integration_inbound
                         GROUP BY status
                        """)
                .query((rs, i) -> new StatusCount(rs.getString("status"), rs.getInt("n")))
                .list();
    }

    public record StatusCount(String status, int count) {
    }
}
