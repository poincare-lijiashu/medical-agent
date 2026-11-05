package com.medassist.emr.repo;

import com.medassist.common.time.BusinessTime;
import com.medassist.emr.domain.EmrModel.ConsentRecord;
import com.medassist.emr.domain.EmrModel.ConsentTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 知情同意书仓储（G44）。
 *
 * <p>同意书正文在创建时固化；签署/撤销走 CAS（状态 + version），并发只有一个成功。
 */
@Repository
public class ConsentRepository {

    private static final RowMapper<ConsentTemplate> TEMPLATE_MAPPER = (rs, i) -> new ConsentTemplate(
            rs.getObject("id", UUID.class),
            rs.getString("template_code"),
            rs.getString("name"),
            rs.getString("procedure_type"),
            rs.getString("content"),
            rs.getInt("template_version"),
            rs.getString("status"),
            rs.getString("created_by"),
            rs.getTimestamp("created_at").toInstant(),
            rs.getTimestamp("updated_at").toInstant(),
            rs.getLong("version"));

    private static final RowMapper<ConsentRecord> CONSENT_MAPPER = (rs, i) -> new ConsentRecord(
            rs.getObject("id", UUID.class),
            rs.getString("consent_no"),
            rs.getObject("template_id", UUID.class),
            rs.getString("template_code"),
            rs.getInt("template_version"),
            rs.getString("template_name"),
            rs.getObject("patient_id", UUID.class),
            rs.getString("patient_no"),
            rs.getString("patient_name"),
            rs.getObject("visit_id", UUID.class),
            rs.getString("procedure_name"),
            rs.getString("content_text"),
            rs.getString("content_hash"),
            rs.getString("status"),
            rs.getString("patient_signer"),
            rs.getString("patient_relation"),
            rs.getString("witness_name"),
            rs.getString("doctor_name"),
            instant(rs.getTimestamp("signed_at")),
            rs.getString("sign_method"),
            rs.getString("certificate_serial"),
            rs.getString("signature_value"),
            rs.getString("algorithm"),
            rs.getString("revoke_reason"),
            rs.getString("revoked_by"),
            instant(rs.getTimestamp("revoked_at")),
            rs.getString("created_by"),
            rs.getTimestamp("created_at").toInstant(),
            rs.getTimestamp("updated_at").toInstant(),
            rs.getLong("version"));

    private static final String CONSENT_COLS = """
            id, consent_no, template_id, template_code, template_version, template_name,
            patient_id, patient_no, patient_name, visit_id, procedure_name,
            content_text, content_hash, status, patient_signer, patient_relation, witness_name,
            doctor_name, signed_at, sign_method, certificate_serial, signature_value, algorithm,
            revoke_reason, revoked_by, revoked_at, created_by, created_at, updated_at, version
            """;

    private final JdbcClient jdbc;

    public ConsentRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    // ---------------------------------------------------------------- templates

    public void insertTemplate(ConsentTemplate template) {
        jdbc.sql("""
                        INSERT INTO emr_svc.consent_template
                            (id, template_code, name, procedure_type, content, template_version,
                             status, created_by)
                        VALUES (:id, :code, :name, :type, :content, :templateVersion, :status, :createdBy)
                        """)
                .param("id", template.id())
                .param("code", template.templateCode())
                .param("name", template.name())
                .param("type", template.procedureType())
                .param("content", template.content())
                .param("templateVersion", template.templateVersion())
                .param("status", template.status())
                .param("createdBy", template.createdBy())
                .update();
    }

    public Optional<ConsentTemplate> findTemplateByCode(String templateCode) {
        return jdbc.sql("""
                        SELECT id, template_code, name, procedure_type, content, template_version,
                               status, created_by, created_at, updated_at, version
                          FROM emr_svc.consent_template WHERE template_code = ?
                        """)
                .param(templateCode)
                .query(TEMPLATE_MAPPER)
                .optional();
    }

    public Optional<ConsentTemplate> findTemplate(UUID id) {
        return jdbc.sql("""
                        SELECT id, template_code, name, procedure_type, content, template_version,
                               status, created_by, created_at, updated_at, version
                          FROM emr_svc.consent_template WHERE id = ?
                        """)
                .param(id)
                .query(TEMPLATE_MAPPER)
                .optional();
    }

    public List<ConsentTemplate> listTemplates(String status, int limit) {
        StringBuilder sql = new StringBuilder("""
                SELECT id, template_code, name, procedure_type, content, template_version,
                       status, created_by, created_at, updated_at, version
                  FROM emr_svc.consent_template WHERE 1=1
                """);
        if (status != null) {
            sql.append(" AND status = :status");
        }
        sql.append(" ORDER BY updated_at DESC LIMIT :limit");
        JdbcClient.StatementSpec spec = jdbc.sql(sql.toString());
        if (status != null) {
            spec = spec.param("status", status);
        }
        return spec.param("limit", limit).query(TEMPLATE_MAPPER).list();
    }

    public boolean updateTemplateStatus(UUID id, String fromStatus, String toStatus,
                                        long expectedVersion) {
        return jdbc.sql("""
                        UPDATE emr_svc.consent_template
                           SET status = :toStatus, version = version + 1, updated_at = now()
                         WHERE id = :id AND status = :fromStatus AND version = :version
                        """)
                .param("toStatus", toStatus)
                .param("id", id)
                .param("fromStatus", fromStatus)
                .param("version", expectedVersion)
                .update() > 0;
    }

    // ---------------------------------------------------------------- consents

    public void insertConsent(ConsentRecord consent) {
        insertConsent(consent, com.medassist.common.security.DataScope.DEFAULT_CAMPUS_ID);
    }

    /** 创建同意书并固化院区归属（G45）。 */
    public void insertConsent(ConsentRecord consent, UUID campusId) {
        jdbc.sql("""
                        INSERT INTO emr_svc.consent_record
                            (id, consent_no, template_id, template_code, template_version, template_name,
                             patient_id, patient_no, patient_name, visit_id, procedure_name,
                             content_text, content_hash, status, doctor_name, created_by, campus_id)
                        VALUES (:id, :no, :templateId, :templateCode, :templateVersion, :templateName,
                                :patientId, :patientNo, :patientName, :visitId, :procedureName,
                                :contentText, :contentHash, :status, :doctorName, :createdBy,
                                :campusId)
                        """)
                .param("id", consent.id())
                .param("no", consent.consentNo())
                .param("templateId", consent.templateId())
                .param("templateCode", consent.templateCode())
                .param("templateVersion", consent.templateVersion())
                .param("templateName", consent.templateName())
                .param("patientId", consent.patientId())
                .param("patientNo", consent.patientNo())
                .param("patientName", consent.patientName())
                .param("visitId", consent.visitId())
                .param("procedureName", consent.procedureName())
                .param("contentText", consent.contentText())
                .param("contentHash", consent.contentHash())
                .param("status", consent.status())
                .param("doctorName", consent.doctorName())
                .param("createdBy", consent.createdBy())
                .param("campusId", campusId == null
                        ? com.medassist.common.security.DataScope.DEFAULT_CAMPUS_ID : campusId)
                .update();
    }

    /** 同意书所属院区（G45 行级权限校验）。 */
    public Optional<UUID> findConsentCampus(UUID id) {
        return jdbc.sql("SELECT campus_id FROM emr_svc.consent_record WHERE id = ?")
                .param(id)
                .query((rs, i) -> rs.getObject("campus_id", UUID.class))
                .optional();
    }

    /**
     * 生成同意书号：IC + 日期 + 6 位序列。
     *
     * <p>原实现用 SQL 的 {@code to_char(now())}，日期取决于数据库会话时区——
     * 与病历号/首页号/结算单号的业务时区口径不一致，跨零点前后两份文书可能落在不同日期（G76）。
     * 现在统一在 Java 侧取 {@link BusinessTime#today()}。
     */
    public String nextConsentNo() {
        Long seq = jdbc.sql("SELECT nextval('emr_svc.consent_no_seq')")
                .query(Long.class).single();
        return "IC" + BusinessTime.today().format(DateTimeFormatter.BASIC_ISO_DATE)
                + String.format("%06d", seq == null ? 0 : seq);
    }

    public Optional<ConsentRecord> findConsent(UUID id) {
        return jdbc.sql("SELECT " + CONSENT_COLS + " FROM emr_svc.consent_record WHERE id = ?")
                .param(id)
                .query(CONSENT_MAPPER)
                .optional();
    }

    public Optional<ConsentRecord> lockConsent(UUID id) {
        return jdbc.sql("SELECT " + CONSENT_COLS
                        + " FROM emr_svc.consent_record WHERE id = ? FOR UPDATE")
                .param(id)
                .query(CONSENT_MAPPER)
                .optional();
    }

    public List<ConsentRecord> listConsents(UUID patientId, String status, int limit) {
        return listConsents(patientId, status, limit, "");
    }

    /** 列表：campusCsv 为空表示不限制院区；否则只返回可见院区（G45）。 */
    public List<ConsentRecord> listConsents(UUID patientId, String status, int limit,
                                            String campusCsv) {
        StringBuilder sql = new StringBuilder("SELECT " + CONSENT_COLS
                + " FROM emr_svc.consent_record WHERE 1=1");
        if (patientId != null) {
            sql.append(" AND patient_id = :patientId");
        }
        if (status != null) {
            sql.append(" AND status = :status");
        }
        sql.append(" AND (CAST(:campusCsv AS text) = ''"
                + " OR campus_id = ANY(string_to_array(CAST(:campusCsv AS text), ',')::uuid[]))");
        sql.append(" ORDER BY created_at DESC LIMIT :limit");
        JdbcClient.StatementSpec spec = jdbc.sql(sql.toString());
        if (patientId != null) {
            spec = spec.param("patientId", patientId);
        }
        if (status != null) {
            spec = spec.param("status", status);
        }
        return spec.param("campusCsv", campusCsv == null ? "" : campusCsv)
                .param("limit", limit).query(CONSENT_MAPPER).list();
    }

    public boolean signConsent(UUID id, long expectedVersion, String patientSigner,
                               String patientRelation, String witnessName, String doctorName,
                               Instant signedAt, String signMethod, String certificateSerial,
                               String signatureValue, String algorithm) {
        return jdbc.sql("""
                        UPDATE emr_svc.consent_record
                           SET status = 'SIGNED',
                               patient_signer = :signer,
                               patient_relation = :relation,
                               witness_name = :witness,
                               doctor_name = :doctor,
                               signed_at = :signedAt,
                               sign_method = :method,
                               certificate_serial = :cert,
                               signature_value = :value,
                               algorithm = :algorithm,
                               version = version + 1,
                               updated_at = now()
                         WHERE id = :id AND status = 'DRAFT' AND version = :version
                        """)
                .param("signer", patientSigner)
                .param("relation", patientRelation)
                .param("witness", witnessName)
                .param("doctor", doctorName)
                .param("signedAt", ts(signedAt))
                .param("method", signMethod)
                .param("cert", certificateSerial)
                .param("value", signatureValue)
                .param("algorithm", algorithm)
                .param("id", id)
                .param("version", expectedVersion)
                .update() > 0;
    }

    public boolean revokeConsent(UUID id, long expectedVersion, String reason, String actor,
                                 Instant revokedAt) {
        return jdbc.sql("""
                        UPDATE emr_svc.consent_record
                           SET status = 'REVOKED',
                               revoke_reason = :reason,
                               revoked_by = :actor,
                               revoked_at = :revokedAt,
                               version = version + 1,
                               updated_at = now()
                         WHERE id = :id AND status = 'SIGNED' AND version = :version
                        """)
                .param("reason", reason)
                .param("actor", actor)
                .param("revokedAt", ts(revokedAt))
                .param("id", id)
                .param("version", expectedVersion)
                .update() > 0;
    }

    /** 看板：草稿、今日签署、累计撤销。 */
    public ConsentBoard board(OffsetDateTime from, OffsetDateTime to) {
        return jdbc.sql("""
                        SELECT count(*) FILTER (WHERE status = 'DRAFT') AS drafts,
                               count(*) FILTER (WHERE status = 'SIGNED'
                                                AND signed_at >= :from AND signed_at < :to) AS signed_today,
                               count(*) FILTER (WHERE status = 'REVOKED') AS revoked_total
                          FROM emr_svc.consent_record
                        """)
                .param("from", from)
                .param("to", to)
                .query((rs, i) -> new ConsentBoard(rs.getLong("drafts"),
                        rs.getLong("signed_today"), rs.getLong("revoked_total")))
                .single();
    }

    /** 记录变更审计（emr 域自治日志）。 */
    public void logChange(String actor, String action, String targetType,
                          String targetId, String detailJson) {
        jdbc.sql("""
                        INSERT INTO emr_svc.emr_change_log (actor, action, target_type, target_id, detail)
                        VALUES (:actor, :action, :targetType, :targetId, CAST(:detail AS jsonb))
                        """)
                .param("actor", actor)
                .param("action", action)
                .param("targetType", targetType)
                .param("targetId", targetId)
                .param("detail", detailJson == null ? "{}" : detailJson)
                .update();
    }

    private static Instant instant(java.sql.Timestamp value) {
        return value == null ? null : value.toInstant();
    }

    private static OffsetDateTime ts(Instant value) {
        return value == null ? null : OffsetDateTime.ofInstant(value, ZoneOffset.UTC);
    }

    public record ConsentBoard(long drafts, long signedToday, long revokedTotal) {
    }
}
