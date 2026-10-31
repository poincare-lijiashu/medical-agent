package com.medassist.emr.repo;

import com.medassist.common.time.BusinessTime;
import com.medassist.emr.domain.EmrModel.Amendment;
import com.medassist.emr.domain.EmrModel.MedicalRecord;
import com.medassist.emr.domain.EmrModel.QcResult;
import com.medassist.emr.domain.EmrModel.QcRule;
import com.medassist.emr.domain.EmrModel.RecordSignature;
import com.medassist.emr.domain.EmrModel.Template;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 病历仓储（JdbcClient 显式 SQL）。
 *
 * <p>所有状态迁移都是"期望状态 + 版本号"CAS；签名与归档使用专用方法，
 * 保证 signed_by / content_hash / archived_at 不会因为漏写而留下不一致数据。
 */
@Repository
public class EmrRepository {

    private static final RowMapper<Template> TPL_MAPPER = (rs, i) -> new Template(
            rs.getObject("id", UUID.class), rs.getString("template_code"), rs.getString("name"),
            rs.getString("doc_type"), rs.getString("scope"),
            rs.getString("schema_json"), rs.getBoolean("enabled"), rs.getLong("version"));

    private static final RowMapper<MedicalRecord> RECORD_MAPPER = (rs, i) -> new MedicalRecord(
            rs.getObject("id", UUID.class), rs.getString("record_no"),
            rs.getObject("patient_id", UUID.class), rs.getString("patient_no"),
            rs.getObject("visit_id", UUID.class), rs.getString("visit_no"),
            rs.getObject("dept_id", UUID.class), rs.getString("doc_type"),
            rs.getString("template_code"), rs.getString("title"),
            rs.getString("content"), rs.getString("content_text"), rs.getString("author"),
            rs.getString("status"), rs.getInt("revision"), rs.getString("signed_by"),
            rs.getTimestamp("signed_at") == null ? null : rs.getTimestamp("signed_at").toInstant(),
            rs.getString("content_hash"),
            rs.getTimestamp("archived_at") == null ? null : rs.getTimestamp("archived_at").toInstant(),
            rs.getLong("version"), rs.getTimestamp("created_at").toInstant());

    private static final RowMapper<RecordSignature> SIGN_MAPPER = (rs, i) -> new RecordSignature(
            rs.getObject("id", UUID.class), rs.getObject("record_id", UUID.class),
            rs.getInt("revision"), rs.getString("signer"), rs.getString("sign_type"),
            rs.getTimestamp("signed_at").toInstant(), rs.getString("content_hash"));

    private static final RowMapper<Amendment> AMEND_MAPPER = (rs, i) -> new Amendment(
            rs.getObject("id", UUID.class), rs.getObject("record_id", UUID.class),
            rs.getInt("revision"), rs.getString("content"), rs.getString("content_text"),
            rs.getString("content_hash"), rs.getString("author"), rs.getString("reason"),
            rs.getTimestamp("created_at").toInstant());

    private static final RowMapper<QcRule> RULE_MAPPER = (rs, i) -> new QcRule(
            rs.getObject("id", UUID.class), rs.getString("rule_code"), rs.getString("name"),
            rs.getString("doc_type"), rs.getString("rule_type"), rs.getString("field_key"),
            rs.getObject("threshold") == null ? null : rs.getInt("threshold"),
            rs.getString("severity"), rs.getString("message"), rs.getBoolean("enabled"));

    private static final RowMapper<QcResult> RESULT_MAPPER = (rs, i) -> new QcResult(
            rs.getObject("id", UUID.class), rs.getObject("record_id", UUID.class),
            rs.getInt("revision"), rs.getString("rule_code"), rs.getString("severity"),
            rs.getBoolean("passed"), rs.getString("message"));

    private final JdbcClient jdbc;

    public EmrRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public String nextRecordNo() {
        Long seq = jdbc.sql("SELECT nextval(CAST(:seq AS regclass))")
                .param("seq", "emr_svc.record_no_seq").query(Long.class).single();
        return "MR" + BusinessTime.today().format(DateTimeFormatter.BASIC_ISO_DATE)
                + String.format("%06d", seq);
    }

    // ---------------------------------------------------------------- 模板

    public Optional<Template> findTemplateByCode(String code) {
        return jdbc.sql("""
                        SELECT id, template_code, name, doc_type, scope,
                               schema_json::text AS schema_json, enabled, version
                          FROM emr_svc.md_template WHERE template_code = ?
                        """).param(code).query(TPL_MAPPER).optional();
    }

    public List<Template> listTemplates(String docType, Boolean enabled) {
        return jdbc.sql("""
                        SELECT id, template_code, name, doc_type, scope,
                               schema_json::text AS schema_json, enabled, version
                          FROM emr_svc.md_template
                         WHERE (CAST(:docType AS text) IS NULL OR doc_type = CAST(:docType AS text))
                           AND (CAST(:enabled AS boolean) IS NULL OR enabled = CAST(:enabled AS boolean))
                         ORDER BY doc_type, template_code
                        """)
                .param("docType", docType).param("enabled", enabled)
                .query(TPL_MAPPER).list();
    }

    public void insertTemplate(Template t) {
        jdbc.sql("""
                        INSERT INTO emr_svc.md_template
                            (id, template_code, name, doc_type, scope, schema_json, enabled, version)
                        VALUES (:id, :code, :name, :docType, :scope, CAST(:schema AS jsonb), true, 0)
                        """)
                .param("id", t.id()).param("code", t.templateCode()).param("name", t.name())
                .param("docType", t.docType()).param("scope", t.scope())
                .param("schema", t.schemaJson()).update();
    }

    public boolean setTemplateEnabled(UUID id, boolean enabled, long expectedVersion) {
        return jdbc.sql("""
                        UPDATE emr_svc.md_template
                           SET enabled = :enabled, version = version + 1, updated_at = now()
                         WHERE id = :id AND version = :version
                        """)
                .param("enabled", enabled).param("id", id).param("version", expectedVersion)
                .update() > 0;
    }

    // ---------------------------------------------------------------- 病历

    public void insertRecord(MedicalRecord r) {
        jdbc.sql("""
                        INSERT INTO emr_svc.medical_record
                            (id, record_no, patient_id, patient_no, visit_id, visit_no, dept_id,
                             doc_type, template_code, title, content, content_text, author,
                             status, revision, version)
                        VALUES (:id, :no, :patientId, :patientNo, CAST(:visitId AS uuid),
                                CAST(:visitNo AS text), CAST(:deptId AS uuid), :docType,
                                :templateCode, :title, CAST(:content AS jsonb), :contentText,
                                :author, :status, 1, 0)
                        """)
                .param("id", r.id()).param("no", r.recordNo())
                .param("patientId", r.patientId()).param("patientNo", r.patientNo())
                .param("visitId", r.visitId()).param("visitNo", r.visitNo())
                .param("deptId", r.deptId()).param("docType", r.docType())
                .param("templateCode", r.templateCode()).param("title", r.title())
                .param("content", r.content()).param("contentText", r.contentText())
                .param("author", r.author()).param("status", r.status()).update();
    }

    public Optional<MedicalRecord> findRecord(UUID id) {
        return jdbc.sql("""
                        SELECT id, record_no, patient_id, patient_no, visit_id, visit_no, dept_id,
                               doc_type, template_code, title, content::text AS content, content_text,
                               author, status, revision, signed_by, signed_at, content_hash,
                               archived_at, version, created_at
                          FROM emr_svc.medical_record WHERE id = ?
                        """).param(id).query(RECORD_MAPPER).optional();
    }

    /** 加行锁读取；必须在事务内调用。 */
    public Optional<MedicalRecord> lockRecord(UUID id) {
        return jdbc.sql("""
                        SELECT id, record_no, patient_id, patient_no, visit_id, visit_no, dept_id,
                               doc_type, template_code, title, content::text AS content, content_text,
                               author, status, revision, signed_by, signed_at, content_hash,
                               archived_at, version, created_at
                          FROM emr_svc.medical_record WHERE id = ? FOR UPDATE
                        """).param(id).query(RECORD_MAPPER).optional();
    }

    /** 保存草稿内容（仅 DRAFT）。 */
    public boolean updateDraftContent(UUID id, String content, String contentText, long expectedVersion) {
        return jdbc.sql("""
                        UPDATE emr_svc.medical_record
                           SET content = CAST(:content AS jsonb), content_text = :contentText,
                               version = version + 1, updated_at = now()
                         WHERE id = :id AND status = 'DRAFT' AND version = :version
                        """)
                .param("content", content).param("contentText", contentText)
                .param("id", id).param("version", expectedVersion).update() > 0;
    }

    /** 状态迁移（CAS）。 */
    public boolean updateStatus(UUID id, String expected, String next, long expectedVersion) {
        return jdbc.sql("""
                        UPDATE emr_svc.medical_record
                           SET status = :next, version = version + 1, updated_at = now()
                         WHERE id = :id AND status = :expected AND version = :version
                        """)
                .param("next", next).param("id", id)
                .param("expected", expected).param("version", expectedVersion).update() > 0;
    }

    /** 签名（CAS：仅 SUBMITTED 可签，同时落签名人/时间/哈希）。 */
    public boolean signRecord(UUID id, String signer, String contentHash, long expectedVersion) {
        return jdbc.sql("""
                        UPDATE emr_svc.medical_record
                           SET status = 'SIGNED', signed_by = :signer, signed_at = now(),
                               content_hash = :hash, version = version + 1, updated_at = now()
                         WHERE id = :id AND status = 'SUBMITTED' AND version = :version
                        """)
                .param("signer", signer).param("hash", contentHash)
                .param("id", id).param("version", expectedVersion).update() > 0;
    }

    /** 归档（CAS：仅 SIGNED 可归档，终态）。 */
    public boolean archiveRecord(UUID id, long expectedVersion) {
        return jdbc.sql("""
                        UPDATE emr_svc.medical_record
                           SET status = 'ARCHIVED', archived_at = now(),
                               version = version + 1, updated_at = now()
                         WHERE id = :id AND status = 'SIGNED' AND version = :version
                        """)
                .param("id", id).param("version", expectedVersion).update() > 0;
    }

    /** 修订：覆盖正文、revision+1、回到 SUBMITTED、清空签名（需重新签）。 */
    public boolean applyAmendment(UUID id, String content, String contentText, long expectedVersion,
                                  String expectedStatus) {
        return jdbc.sql("""
                        UPDATE emr_svc.medical_record
                           SET content = CAST(:content AS jsonb), content_text = :contentText,
                               revision = revision + 1, status = 'SUBMITTED',
                               signed_by = NULL, signed_at = NULL, content_hash = NULL,
                               version = version + 1, updated_at = now()
                         WHERE id = :id AND status = :expected AND version = :version
                        """)
                .param("content", content).param("contentText", contentText)
                .param("id", id).param("expected", expectedStatus)
                .param("version", expectedVersion).update() > 0;
    }

    public List<MedicalRecord> listRecords(String status, UUID patientId, UUID visitId, int limit) {
        return jdbc.sql("""
                        SELECT id, record_no, patient_id, patient_no, visit_id, visit_no, dept_id,
                               doc_type, template_code, title, content::text AS content, content_text,
                               author, status, revision, signed_by, signed_at, content_hash,
                               archived_at, version, created_at
                          FROM emr_svc.medical_record
                         WHERE (CAST(:status AS text) IS NULL OR status = CAST(:status AS text))
                           AND (CAST(:patientId AS uuid) IS NULL OR patient_id = CAST(:patientId AS uuid))
                           AND (CAST(:visitId AS uuid) IS NULL OR visit_id = CAST(:visitId AS uuid))
                         ORDER BY created_at DESC
                         LIMIT :limit
                        """)
                .param("status", status).param("patientId", patientId)
                .param("visitId", visitId).param("limit", limit)
                .query(RECORD_MAPPER).list();
    }

    // ---------------------------------------------------------------- 签名 / 修订

    public void insertSignature(RecordSignature s) {
        jdbc.sql("""
                        INSERT INTO emr_svc.record_signature
                            (id, record_id, revision, signer, sign_type, content_hash)
                        VALUES (:id, :recordId, :revision, :signer, :signType, :hash)
                        """)
                .param("id", s.id()).param("recordId", s.recordId())
                .param("revision", s.revision()).param("signer", s.signer())
                .param("signType", s.signType()).param("hash", s.contentHash()).update();
    }

    public List<RecordSignature> listSignatures(UUID recordId) {
        return jdbc.sql("""
                        SELECT id, record_id, revision, signer, sign_type, signed_at, content_hash
                          FROM emr_svc.record_signature
                         WHERE record_id = ? ORDER BY revision, signed_at
                        """).param(recordId).query(SIGN_MAPPER).list();
    }

    public void insertAmendment(Amendment a) {
        jdbc.sql("""
                        INSERT INTO emr_svc.record_amendment
                            (id, record_id, revision, content, content_text, content_hash, author, reason)
                        VALUES (:id, :recordId, :revision, CAST(:content AS jsonb), :contentText,
                                :hash, :author, :reason)
                        """)
                .param("id", a.id()).param("recordId", a.recordId())
                .param("revision", a.revision()).param("content", a.content())
                .param("contentText", a.contentText()).param("hash", a.contentHash())
                .param("author", a.author()).param("reason", a.reason()).update();
    }

    public List<Amendment> listAmendments(UUID recordId) {
        return jdbc.sql("""
                        SELECT id, record_id, revision, content::text AS content, content_text,
                               content_hash, author, reason, created_at
                          FROM emr_svc.record_amendment
                         WHERE record_id = ? ORDER BY revision
                        """).param(recordId).query(AMEND_MAPPER).list();
    }

    // ---------------------------------------------------------------- 质控

    public List<QcRule> listQcRules(String docType) {
        return jdbc.sql("""
                        SELECT id, rule_code, name, doc_type, rule_type, field_key, threshold,
                               severity, message, enabled
                          FROM emr_svc.qc_rule
                         WHERE doc_type = :docType AND enabled
                         ORDER BY severity, rule_code
                        """).param("docType", docType).query(RULE_MAPPER).list();
    }

    public void insertQcResult(QcResult r) {
        jdbc.sql("""
                        INSERT INTO emr_svc.qc_result
                            (id, record_id, revision, rule_code, severity, passed, message)
                        VALUES (:id, :recordId, :revision, :ruleCode, :severity, :passed, :message)
                        """)
                .param("id", r.id()).param("recordId", r.recordId())
                .param("revision", r.revision()).param("ruleCode", r.ruleCode())
                .param("severity", r.severity()).param("passed", r.passed())
                .param("message", r.message()).update();
    }

    public List<QcResult> listQcResults(UUID recordId, int revision) {
        return jdbc.sql("""
                        SELECT id, record_id, revision, rule_code, severity, passed, message
                          FROM emr_svc.qc_result
                         WHERE record_id = :recordId AND revision = :revision
                         ORDER BY severity, rule_code
                        """)
                .param("recordId", recordId).param("revision", revision)
                .query(RESULT_MAPPER).list();
    }

    // ---------------------------------------------------------------- 审计

    public void logChange(String actor, String action, String targetType, String targetId, String detailJson) {
        jdbc.sql("""
                        INSERT INTO emr_svc.emr_change_log
                            (actor, action, target_type, target_id, detail)
                        VALUES (:actor, :action, :targetType, :targetId, CAST(:detail AS jsonb))
                        """)
                .param("actor", actor).param("action", action)
                .param("targetType", targetType).param("targetId", targetId)
                .param("detail", detailJson == null ? "{}" : detailJson).update();
    }
}
