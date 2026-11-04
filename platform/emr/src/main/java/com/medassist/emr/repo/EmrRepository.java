package com.medassist.emr.repo;

import com.medassist.common.time.BusinessTime;
import com.medassist.emr.domain.EmrModel.Amendment;
import com.medassist.emr.domain.EmrModel.CaSignature;
import com.medassist.emr.domain.EmrModel.ChangeRequest;
import com.medassist.emr.domain.EmrModel.CodingCorrection;
import com.medassist.emr.domain.EmrModel.CodingQueueItem;
import com.medassist.emr.domain.EmrModel.Homepage;
import com.medassist.emr.domain.EmrModel.HomepageCoding;
import com.medassist.emr.domain.EmrModel.HomepageSummary;
import com.medassist.emr.domain.EmrModel.HqmsExportBatch;
import com.medassist.emr.domain.EmrModel.IcdDiagnosis;
import com.medassist.emr.domain.EmrModel.IcdProcedure;
import com.medassist.emr.domain.EmrModel.MedicalRecord;
import com.medassist.emr.domain.EmrModel.QcResult;
import com.medassist.emr.domain.EmrModel.QcRule;
import com.medassist.emr.domain.EmrModel.RecordHit;
import com.medassist.emr.domain.EmrModel.RecordSignature;
import com.medassist.emr.domain.EmrModel.SignPolicy;
import com.medassist.emr.domain.EmrModel.Template;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
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
            rs.getObject("change_request_id", UUID.class),
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

    private static final RowMapper<ChangeRequest> CHANGE_MAPPER = (rs, i) -> new ChangeRequest(
            rs.getObject("id", UUID.class), rs.getObject("record_id", UUID.class),
            rs.getString("record_no"), rs.getString("reason"),
            rs.getString("proposed_content"), rs.getString("proposed_text"),
            rs.getString("status"), rs.getString("requester"),
            rs.getTimestamp("requested_at").toInstant(), rs.getString("reviewer"),
            rs.getTimestamp("reviewed_at") == null ? null : rs.getTimestamp("reviewed_at").toInstant(),
            rs.getString("review_comment"),
            rs.getObject("applied_revision") == null ? null : rs.getInt("applied_revision"),
            rs.getObject("resubmit_of", UUID.class));

    private static final RowMapper<RecordHit> HIT_MAPPER = (rs, i) -> new RecordHit(
            rs.getObject("id", UUID.class), rs.getString("record_no"),
            rs.getObject("patient_id", UUID.class), rs.getString("patient_no"),
            rs.getObject("dept_id", UUID.class),
            rs.getString("doc_type"), rs.getString("title"), rs.getString("author"),
            rs.getString("status"), rs.getInt("revision"),
            rs.getTimestamp("created_at").toInstant());

    private static final RowMapper<Homepage> HOMEPAGE_MAPPER = (rs, i) -> new Homepage(
            rs.getObject("id", UUID.class), rs.getString("homepage_no"),
            rs.getObject("visit_id", UUID.class), rs.getObject("admission_id", UUID.class),
            rs.getObject("patient_id", UUID.class), rs.getString("patient_no"),
            rs.getString("patient_name"), rs.getString("patient_sex"),
            rs.getDate("birth_date") == null ? null : rs.getDate("birth_date").toLocalDate(),
            rs.getObject("admit_age") == null ? null : rs.getInt("admit_age"),
            rs.getString("visit_no"),
            rs.getObject("dept_id", UUID.class), rs.getString("dept_name"),
            rs.getObject("ward_id", UUID.class), rs.getString("ward_name"), rs.getString("bed_no"),
            instant(rs, "admitted_at"), instant(rs, "discharged_at"), rs.getInt("stay_days"),
            rs.getString("attending_doctor"), rs.getString("admission_way"),
            rs.getString("discharge_mode"), instant(rs, "death_time"), rs.getString("death_cause"),
            rs.getString("readmit_plan"),
            rs.getString("marital_status"), rs.getString("occupation"), rs.getString("nation"),
            rs.getString("blood_type"), rs.getString("rh_factor"),
            rs.getInt("rescue_count"), rs.getInt("rescue_success_count"),
            rs.getBoolean("critical_case"), rs.getBoolean("hospital_infection"),
            rs.getString("chief_physician"), rs.getString("resident_physician"), rs.getString("coder"),
            rs.getString("diagnoses") == null ? "[]" : rs.getString("diagnoses"),
            rs.getString("procedures") == null ? "[]" : rs.getString("procedures"),
            rs.getString("fee_summary") == null ? "{}" : rs.getString("fee_summary"),
            rs.getBigDecimal("total_amount"), rs.getString("status"),
            rs.getString("submitted_by"), rs.getObject("submitted_by_id", UUID.class),
            instant(rs, "submitted_at"),
            rs.getString("archived_by"), instant(rs, "archived_at"),
            rs.getLong("version"), instant(rs, "created_at"));

    private static java.time.Instant instant(java.sql.ResultSet rs, String column)
            throws java.sql.SQLException {
        java.sql.Timestamp ts = rs.getTimestamp(column);
        return ts == null ? null : ts.toInstant();
    }

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

    /**
     * 插入病历；{@code visitStartedAt} 为创建时从临床域取回的入院时间（门诊为空），
     * 供书写时限质控使用。
     */
    public void insertRecord(MedicalRecord r, java.time.Instant visitStartedAt) {
        insertRecord(r, visitStartedAt,
                com.medassist.common.security.DataScope.DEFAULT_CAMPUS_ID);
    }

    /** 创建病历并固化院区归属（G45）。 */
    public void insertRecord(MedicalRecord r, java.time.Instant visitStartedAt, UUID campusId) {
        jdbc.sql("""
                        INSERT INTO emr_svc.medical_record
                            (id, record_no, patient_id, patient_no, visit_id, visit_no, dept_id,
                             doc_type, template_code, title, content, content_text, author,
                             status, revision, version, visit_started_at, campus_id)
                        VALUES (:id, :no, :patientId, :patientNo, CAST(:visitId AS uuid),
                                CAST(:visitNo AS text), CAST(:deptId AS uuid), :docType,
                                :templateCode, :title, CAST(:content AS jsonb), :contentText,
                                :author, :status, 1, 0, CAST(:visitStartedAt AS timestamptz),
                                :campusId)
                        """)
                .param("id", r.id()).param("no", r.recordNo())
                .param("patientId", r.patientId()).param("patientNo", r.patientNo())
                .param("visitId", r.visitId()).param("visitNo", r.visitNo())
                .param("deptId", r.deptId()).param("docType", r.docType())
                .param("templateCode", r.templateCode()).param("title", r.title())
                .param("content", r.content()).param("contentText", r.contentText())
                .param("author", r.author()).param("status", r.status())
                .param("visitStartedAt", visitStartedAt == null ? null
                        : java.time.OffsetDateTime.ofInstant(visitStartedAt, java.time.ZoneOffset.UTC))
                .param("campusId", campusId == null
                        ? com.medassist.common.security.DataScope.DEFAULT_CAMPUS_ID : campusId)
                .update();
    }

    /** 病历创建时固化的入院时间；门诊病历为空。 */
    public Optional<java.time.Instant> visitStartedAt(UUID recordId) {
        List<java.time.Instant> rows = jdbc.sql("""
                        SELECT visit_started_at FROM emr_svc.medical_record
                         WHERE id = :id AND visit_started_at IS NOT NULL
                        """)
                .param("id", recordId)
                .query((rs, i) -> rs.getTimestamp("visit_started_at").toInstant())
                .list();
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    /**
     * 补写入院时间（自愈）：仅当当前为空时写入，避免覆盖已固化的基准。
     *
     * <p>场景：创建病历时临床域短暂不可用，导致入院时间没取到；提交时回源成功则补写，
     * 让时限质控恢复可用，而不是永久跳过。
     */
    public boolean bindVisitStartedAt(UUID recordId, java.time.Instant startedAt) {
        return jdbc.sql("""
                        UPDATE emr_svc.medical_record
                           SET visit_started_at = CAST(:ts AS timestamptz), updated_at = now()
                         WHERE id = :id AND visit_started_at IS NULL
                        """)
                .param("ts", java.time.OffsetDateTime.ofInstant(startedAt, java.time.ZoneOffset.UTC))
                .param("id", recordId).update() > 0;
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

    /** 上级签名（主治/主任）：正文不变，只追加签名并刷新签名人与时间。 */
    public boolean countersign(UUID id, String signer, String contentHash, long expectedVersion) {
        return jdbc.sql("""
                        UPDATE emr_svc.medical_record
                           SET signed_by = :signer, signed_at = now(), content_hash = :hash,
                               version = version + 1, updated_at = now()
                         WHERE id = :id AND status = 'SIGNED' AND version = :version
                        """)
                .param("signer", signer).param("hash", contentHash)
                .param("id", id).param("version", expectedVersion).update() > 0;
    }

    /** 当前 revision 已签到的级别（AUTHOR=1/ATTENDING=2/CHIEF=3，未签=0）。 */
    public int currentSignLevel(UUID recordId, int revision) {
        Integer level = jdbc.sql("""
                        SELECT COALESCE(MAX(CASE sign_type
                                                WHEN 'AUTHOR' THEN 1
                                                WHEN 'ATTENDING' THEN 2
                                                WHEN 'CHIEF' THEN 3
                                                ELSE 0 END), 0)
                          FROM emr_svc.record_signature
                         WHERE record_id = :recordId AND revision = :revision
                        """)
                .param("recordId", recordId).param("revision", revision)
                .query(Integer.class).single();
        return level == null ? 0 : level;
    }

    /**
     * 归档要求的签名级别。
     *
     * <p>未配置策略时**拒绝**而不是默认按 1 级放行：fail-open 会让漏配的文书类型
     * 变成"书写医师签完即可归档"，与三甲审签要求直接冲突。策略表已覆盖全部内置 docType，
     * 新增文书类型必须同步补策略（见 V2 / V7 说明）。
     */
    public Optional<Integer> requiredSignLevel(String docType) {
        return jdbc.sql("SELECT required_level FROM emr_svc.sign_policy WHERE doc_type = ?")
                .param(docType).query(Integer.class).optional();
    }

    /** 模板表里出现过的全部文书类型（启动时与签名策略做包含校验）。 */
    public List<String> listTemplateDocTypes() {
        return jdbc.sql("SELECT DISTINCT doc_type FROM emr_svc.md_template ORDER BY doc_type")
                .query(String.class).list();
    }

    /** 签名策略已配置的全部文书类型。 */
    public List<String> listPolicyDocTypes() {
        return jdbc.sql("SELECT doc_type FROM emr_svc.sign_policy ORDER BY doc_type")
                .query(String.class).list();
    }

    // ---------------------------------------------------------------- 签名策略管理（N-04）

    private static final RowMapper<SignPolicy> POLICY_MAPPER = (rs, i) -> new SignPolicy(
            rs.getString("doc_type"), rs.getInt("required_level"), rs.getLong("version"),
            rs.getString("updated_by"),
            rs.getTimestamp("updated_at") == null ? null : rs.getTimestamp("updated_at").toInstant());

    public List<SignPolicy> listSignPolicies() {
        return jdbc.sql("""
                        SELECT doc_type, required_level, version, updated_by, updated_at
                          FROM emr_svc.sign_policy
                         ORDER BY doc_type
                        """).query(POLICY_MAPPER).list();
    }

    public Optional<SignPolicy> findSignPolicy(String docType) {
        return jdbc.sql("""
                        SELECT doc_type, required_level, version, updated_by, updated_at
                          FROM emr_svc.sign_policy
                         WHERE doc_type = ?
                        """).param(docType).query(POLICY_MAPPER).optional();
    }

    /** 新增策略；并发重复创建时返回 false（由服务层转 409）。 */
    public boolean insertSignPolicy(String docType, int requiredLevel, String actor) {
        return jdbc.sql("""
                        INSERT INTO emr_svc.sign_policy
                            (doc_type, required_level, version, updated_by, updated_at)
                        VALUES (:docType, :level, 0, :actor, now())
                        ON CONFLICT (doc_type) DO NOTHING
                        """)
                .param("docType", docType).param("level", requiredLevel)
                .param("actor", actor).update() > 0;
    }

    /** 修改策略（期望版本号 CAS）；版本不匹配返回 false。 */
    public boolean updateSignPolicy(String docType, int requiredLevel, long expectedVersion,
                                    String actor) {
        return jdbc.sql("""
                        UPDATE emr_svc.sign_policy
                           SET required_level = :level, version = version + 1,
                               updated_by = :actor, updated_at = now()
                         WHERE doc_type = :docType AND version = :version
                        """)
                .param("level", requiredLevel).param("docType", docType)
                .param("version", expectedVersion).param("actor", actor).update() > 0;
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
        return listRecords(status, patientId, visitId, limit, "");
    }

    /** 列表：campusCsv 为空表示不限制院区；否则只返回可见院区（G45）。 */
    public List<MedicalRecord> listRecords(String status, UUID patientId, UUID visitId, int limit,
                                           String campusCsv) {
        return jdbc.sql("""
                        SELECT id, record_no, patient_id, patient_no, visit_id, visit_no, dept_id,
                               doc_type, template_code, title, content::text AS content, content_text,
                               author, status, revision, signed_by, signed_at, content_hash,
                               archived_at, version, created_at
                          FROM emr_svc.medical_record
                         WHERE (CAST(:status AS text) IS NULL OR status = CAST(:status AS text))
                           AND (CAST(:patientId AS uuid) IS NULL OR patient_id = CAST(:patientId AS uuid))
                           AND (CAST(:visitId AS uuid) IS NULL OR visit_id = CAST(:visitId AS uuid))
                           AND (CAST(:campusCsv AS text) = ''
                                OR campus_id = ANY(string_to_array(CAST(:campusCsv AS text), ',')::uuid[]))
                         ORDER BY created_at DESC
                         LIMIT :limit
                        """)
                .param("status", status).param("patientId", patientId)
                .param("visitId", visitId).param("campusCsv", campusCsv == null ? "" : campusCsv)
                .param("limit", limit)
                .query(RECORD_MAPPER).list();
    }

    /**
     * 病历所属科室（行级权限校验用）。
     *
     * <p>返回 empty 有两种可能：记录不存在，或建档时未带科室。
     * 两者对调用方是同一个结论——不可见，因此不区分。
     */
    public Optional<UUID> findRecordDepartment(UUID id) {
        return jdbc.sql("SELECT dept_id FROM emr_svc.medical_record WHERE id = ?")
                .param(id)
                .query((rs, i) -> rs.getObject("dept_id", UUID.class))
                .optional();
    }

    /** 病历所属院区（G45 行级权限校验）。 */
    public Optional<UUID> findRecordCampus(UUID id) {
        return jdbc.sql("SELECT campus_id FROM emr_svc.medical_record WHERE id = ?")
                .param(id)
                .query((rs, i) -> rs.getObject("campus_id", UUID.class))
                .optional();
    }

    /** 病案首页所属科室（行级权限校验用）。 */
    public Optional<UUID> findHomepageDepartment(UUID id) {
        return jdbc.sql("SELECT dept_id FROM emr_svc.record_homepage WHERE id = ?")
                .param(id)
                .query((rs, i) -> rs.getObject("dept_id", UUID.class))
                .optional();
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

    // ---------------------------------------------------------------- CA 电子签名（G41）

    private static final RowMapper<CaSignature> CA_SIGNATURE_MAPPER = (rs, i) ->
            new CaSignature(
                    rs.getObject("id", UUID.class), rs.getObject("signature_id", UUID.class),
                    rs.getObject("record_id", UUID.class), rs.getInt("revision"),
                    rs.getString("signer"), rs.getString("sign_type"),
                    rs.getString("content_hash"), rs.getString("provider"),
                    rs.getString("certificate_serial"), rs.getString("algorithm"),
                    rs.getString("signature_value"), rs.getTimestamp("signed_at").toInstant(),
                    rs.getString("status"), rs.getInt("verify_count"),
                    rs.getTimestamp("last_verified_at") == null ? null
                            : rs.getTimestamp("last_verified_at").toInstant(),
                    rs.getObject("last_verify_result", Boolean.class),
                    rs.getString("revoke_reason"), rs.getString("revoked_by"),
                    rs.getTimestamp("revoked_at") == null ? null
                            : rs.getTimestamp("revoked_at").toInstant(),
                    rs.getTimestamp("created_at").toInstant());

    private static final String CA_SIGNATURE_SELECT = """
            SELECT id, signature_id, record_id, revision, signer, sign_type, content_hash,
                   provider, certificate_serial, algorithm, signature_value, signed_at, status,
                   verify_count, last_verified_at, last_verify_result, revoke_reason, revoked_by,
                   revoked_at, created_at
              FROM emr_svc.ca_signature
            """;

    public void insertCaSignature(CaSignature s) {
        jdbc.sql("""
                        INSERT INTO emr_svc.ca_signature
                            (id, signature_id, record_id, revision, signer, sign_type,
                             content_hash, provider, certificate_serial, algorithm,
                             signature_value, signed_at, status)
                        VALUES (:id, :signatureId, :recordId, :revision, :signer, :signType,
                                :contentHash, :provider, :certificateSerial, :algorithm,
                                :signatureValue, :signedAt, :status)
                        """)
                .param("id", s.id()).param("signatureId", s.signatureId())
                .param("recordId", s.recordId()).param("revision", s.revision())
                .param("signer", s.signer()).param("signType", s.signType())
                .param("contentHash", s.contentHash()).param("provider", s.provider())
                .param("certificateSerial", s.certificateSerial())
                .param("algorithm", s.algorithm())
                .param("signatureValue", s.signatureValue())
                .param("signedAt", ts(s.signedAt())).param("status", s.status()).update();
    }

    public Optional<CaSignature> findCaSignature(UUID id) {
        return jdbc.sql(CA_SIGNATURE_SELECT + " WHERE id = ?")
                .param(id).query(CA_SIGNATURE_MAPPER).optional();
    }

    public Optional<CaSignature> findCaSignatureBySignature(UUID signatureId) {
        return jdbc.sql(CA_SIGNATURE_SELECT + " WHERE signature_id = ?")
                .param(signatureId).query(CA_SIGNATURE_MAPPER).optional();
    }

    public List<CaSignature> listCaSignatures(UUID recordId, String status, String signer,
                                              int limit) {
        return jdbc.sql(CA_SIGNATURE_SELECT + """
                         WHERE (CAST(:recordId AS uuid) IS NULL
                                OR record_id = CAST(:recordId AS uuid))
                           AND (CAST(:status AS text) IS NULL
                                OR status = CAST(:status AS text))
                           AND (CAST(:signer AS text) IS NULL
                                OR signer = CAST(:signer AS text))
                         ORDER BY signed_at DESC
                         LIMIT :limit
                        """)
                .param("recordId", recordId).param("status", status).param("signer", signer)
                .param("limit", limit).query(CA_SIGNATURE_MAPPER).list();
    }

    public boolean recordCaVerify(UUID id, boolean valid) {
        return jdbc.sql("""
                        UPDATE emr_svc.ca_signature
                           SET verify_count = verify_count + 1, last_verified_at = now(),
                               last_verify_result = :valid
                         WHERE id = :id AND status = 'VALID'
                        """)
                .param("id", id).param("valid", valid).update() > 0;
    }

    public boolean revokeCaSignature(UUID id, String reason, String actor) {
        return jdbc.sql("""
                        UPDATE emr_svc.ca_signature
                           SET status = 'REVOKED', revoke_reason = :reason, revoked_by = :actor,
                               revoked_at = now()
                         WHERE id = :id AND status = 'VALID'
                        """)
                .param("id", id).param("reason", reason).param("actor", actor).update() > 0;
    }

    /** CA 台账：今日签名/有效/撤销/今日验签失败。 */
    public CaBoardRow caBoard(java.time.OffsetDateTime fromTs, java.time.OffsetDateTime toTs) {
        return jdbc.sql("""
                        SELECT count(*) FILTER (WHERE signed_at >= :fromTs
                                                AND signed_at < :toTs) AS signed_today,
                               count(*) FILTER (WHERE status = 'VALID') AS valid_total,
                               count(*) FILTER (WHERE status = 'REVOKED') AS revoked_total,
                               count(*) FILTER (WHERE last_verify_result = false
                                                AND last_verified_at >= :fromTs
                                                AND last_verified_at < :toTs)
                                   AS verify_failures_today
                          FROM emr_svc.ca_signature
                        """)
                .param("fromTs", fromTs).param("toTs", toTs)
                .query((rs, i) -> new CaBoardRow(rs.getLong("signed_today"),
                        rs.getLong("valid_total"), rs.getLong("revoked_total"),
                        rs.getLong("verify_failures_today")))
                .single();
    }

    public record CaBoardRow(long signedToday, long validTotal, long revokedTotal,
                             long verifyFailuresToday) {
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
                            (id, record_id, revision, content, content_text, content_hash, author,
                             reason, change_request_id)
                        VALUES (:id, :recordId, :revision, CAST(:content AS jsonb), :contentText,
                                :hash, :author, :reason, :changeRequestId)
                        """)
                .param("id", a.id()).param("recordId", a.recordId())
                .param("revision", a.revision()).param("content", a.content())
                .param("contentText", a.contentText()).param("hash", a.contentHash())
                .param("author", a.author()).param("reason", a.reason())
                .param("changeRequestId", a.changeRequestId()).update();
    }

    public List<Amendment> listAmendments(UUID recordId) {
        return jdbc.sql("""
                        SELECT id, record_id, revision, content::text AS content, content_text,
                               content_hash, author, reason, change_request_id, created_at
                          FROM emr_svc.record_amendment
                         WHERE record_id = ? ORDER BY revision
                        """).param(recordId).query(AMEND_MAPPER).list();
    }

    /** 某版本的修订留痕（用于判断该版是否由病案修改申请产生）。 */
    public Optional<Amendment> findAmendmentByRevision(UUID recordId, int revision) {
        return jdbc.sql("""
                        SELECT id, record_id, revision, content::text AS content, content_text,
                               content_hash, author, reason, change_request_id, created_at
                          FROM emr_svc.record_amendment
                         WHERE record_id = :recordId AND revision = :revision
                         ORDER BY created_at DESC LIMIT 1
                        """)
                .param("recordId", recordId).param("revision", revision)
                .query(AMEND_MAPPER).optional();
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

    // ---------------------------------------------------------------- CDR 检索

    /**
     * 病历检索（CDR）。
     *
     * <p>两类关键词分开走各自的索引，避免把 OR 条件混在一张表上导致全表扫描：
     * <ul>
     *   <li>标题/正文：{@code ILIKE '%kw%'}，走 pg_trgm GIN 索引（V4 迁移创建）；</li>
     *   <li>病历号/患者号：{@code LIKE 'kw%'} 前缀匹配，走 text_pattern_ops B-tree 索引。</li>
     * </ul>
     * 两条分支先各自裁剪到 limit 再合并去重，最后整体按建档时间倒序截断。关键词通配符已由调用方转义。
     *
     * @param pattern 形如 {@code %kw%}，为空表示不做文本模糊检索
     * @param prefix  形如 {@code kw%}，为空表示不做编号前缀检索
     */
    public List<RecordHit> searchRecords(String pattern, String prefix, UUID patientId, String docType,
                                         String status, LocalDate from, LocalDate to, int limit) {
        return searchRecords(pattern, prefix, patientId, docType, status, from, to, limit, "");
    }

    /** 检索：campusCsv 为空表示不限制院区；否则只返回可见院区（G45）。 */
    public List<RecordHit> searchRecords(String pattern, String prefix, UUID patientId, String docType,
                                         String status, LocalDate from, LocalDate to, int limit,
                                         String campusCsv) {
        String sql = pattern == null ? """
                        SELECT id, record_no, patient_id, patient_no, dept_id, doc_type, title,
                               author, status, revision, created_at
                          FROM emr_svc.medical_record
                         WHERE 1 = 1
                        """ + SEARCH_FILTERS + """
                         ORDER BY created_at DESC
                         LIMIT :limit
                        """ : """
                        SELECT id, record_no, patient_id, patient_no, dept_id, doc_type, title,
                               author, status, revision, created_at
                          FROM (
                                (SELECT id, record_no, patient_id, patient_no, dept_id, doc_type, title,
                                        author, status, revision, created_at
                                   FROM emr_svc.medical_record
                                  WHERE (title ILIKE CAST(:pattern AS text) ESCAPE '\\'
                                         OR content_text ILIKE CAST(:pattern AS text) ESCAPE '\\')
                                """ + SEARCH_FILTERS + """
                                  ORDER BY created_at DESC
                                  LIMIT :limit)
                                UNION
                                (SELECT id, record_no, patient_id, patient_no, dept_id, doc_type, title,
                                        author, status, revision, created_at
                                   FROM emr_svc.medical_record
                                  WHERE (record_no LIKE CAST(:prefix AS text) ESCAPE '\\'
                                         OR patient_no LIKE CAST(:prefix AS text) ESCAPE '\\')
                                """ + SEARCH_FILTERS + """
                                  ORDER BY created_at DESC
                                  LIMIT :limit)
                               ) t
                         ORDER BY created_at DESC
                         LIMIT :limit
                        """;

        JdbcClient.StatementSpec spec = jdbc.sql(sql)
                .param("patientId", patientId).param("docType", docType).param("status", status)
                .param("fromDate", from).param("toDate", to).param("limit", limit)
                .param("campusCsv", campusCsv == null ? "" : campusCsv);
        if (pattern != null) {
            spec = spec.param("pattern", pattern).param("prefix", prefix);
        }
        return spec.query(HIT_MAPPER).list();
    }

    /** 检索公共过滤条件：患者 / 文书类型 / 状态 / 建档日期，均为可选。 */
    private static final String SEARCH_FILTERS = """
              AND (CAST(:patientId AS uuid) IS NULL OR patient_id = CAST(:patientId AS uuid))
              AND (CAST(:docType AS text) IS NULL OR doc_type = CAST(:docType AS text))
              AND (CAST(:status AS text) IS NULL OR status = CAST(:status AS text))
              AND (CAST(:fromDate AS date) IS NULL OR created_at >= CAST(:fromDate AS date))
              AND (CAST(:toDate AS date) IS NULL
                   OR created_at < CAST(:toDate AS date) + INTERVAL '1 day')
              AND (CAST(:campusCsv AS text) = ''
                   OR campus_id = ANY(string_to_array(CAST(:campusCsv AS text), ',')::uuid[]))
            """;

    // ---------------------------------------------------------------- 归档后修改申请

    public void insertChangeRequest(ChangeRequest c) {
        jdbc.sql("""
                        INSERT INTO emr_svc.record_change_request
                            (id, record_id, record_no, reason, proposed_content, proposed_text,
                             status, requester, resubmit_of)
                        VALUES (:id, :recordId, :recordNo, :reason, CAST(:content AS jsonb),
                                :text, 'PENDING', :requester, :resubmitOf)
                        """)
                .param("id", c.id()).param("recordId", c.recordId())
                .param("recordNo", c.recordNo()).param("reason", c.reason())
                .param("content", c.proposedContent()).param("text", c.proposedText())
                .param("requester", c.requester()).param("resubmitOf", c.resubmitOf()).update();
    }

    public boolean hasPendingChangeRequest(UUID recordId) {
        Boolean exists = jdbc.sql("""
                        SELECT EXISTS (SELECT 1 FROM emr_svc.record_change_request
                                        WHERE record_id = :recordId AND status = 'PENDING')
                        """).param("recordId", recordId).query(Boolean.class).single();
        return Boolean.TRUE.equals(exists);
    }

    public Optional<ChangeRequest> lockChangeRequest(UUID id) {
        return jdbc.sql("""
                        SELECT id, record_id, record_no, reason, proposed_content::text AS proposed_content,
                               proposed_text, status, requester, requested_at, reviewer,
                               reviewed_at, review_comment, applied_revision, resubmit_of
                          FROM emr_svc.record_change_request WHERE id = ? FOR UPDATE
                        """).param(id).query(CHANGE_MAPPER).optional();
    }

    public List<ChangeRequest> listChangeRequests(UUID recordId) {
        return jdbc.sql("""
                        SELECT id, record_id, record_no, reason, proposed_content::text AS proposed_content,
                               proposed_text, status, requester, requested_at, reviewer,
                               reviewed_at, review_comment, applied_revision, resubmit_of
                          FROM emr_svc.record_change_request
                         WHERE record_id = ? ORDER BY requested_at DESC
                        """).param(recordId).query(CHANGE_MAPPER).list();
    }

    /** 申请人撤回（CAS：仅本人、仅 PENDING）。 */
    public boolean withdrawChangeRequest(UUID id, String requester) {
        return jdbc.sql("""
                        UPDATE emr_svc.record_change_request
                           SET status = 'WITHDRAWN', reviewer = :requester, reviewed_at = now(),
                               review_comment = '申请人撤回'
                         WHERE id = :id AND status = 'PENDING' AND requester = :requester
                        """)
                .param("id", id).param("requester", requester).update() > 0;
    }

    /** 审批结果（CAS：仅 PENDING 可审批）。 */
    public boolean reviewChangeRequest(UUID id, String status, String reviewer,
                                       String comment, Integer appliedRevision) {
        return jdbc.sql("""
                        UPDATE emr_svc.record_change_request
                           SET status = :status, reviewer = :reviewer, reviewed_at = now(),
                               review_comment = :comment, applied_revision = :revision
                         WHERE id = :id AND status = 'PENDING'
                        """)
                .param("status", status).param("reviewer", reviewer)
                .param("comment", comment).param("revision", appliedRevision)
                .param("id", id).update() > 0;
    }

    // ---------------------------------------------------------------- 病案首页

    /** 首页单号 HPYYYYMMDD######（按业务日切分，便于病案室按日交接）。 */
    public String nextHomepageNo() {
        Long seq = jdbc.sql("SELECT nextval(CAST(:seq AS regclass))")
                .param("seq", "emr_svc.homepage_no_seq").query(Long.class).single();
        return "HP" + BusinessTime.today().format(DateTimeFormatter.BASIC_ISO_DATE)
                + String.format("%06d", seq);
    }

    private static final String HOMEPAGE_COLUMNS = """
            id, homepage_no, visit_id, admission_id, patient_id, patient_no, patient_name,
            patient_sex, birth_date, admit_age,
            visit_no, dept_id, dept_name, ward_id, ward_name, bed_no, admitted_at, discharged_at,
            stay_days, attending_doctor, admission_way, discharge_mode, death_time, death_cause,
            readmit_plan,
            marital_status, occupation, nation, blood_type, rh_factor, rescue_count,
            rescue_success_count, critical_case, hospital_infection, chief_physician,
            resident_physician, coder, diagnoses::text AS diagnoses,
            procedures::text AS procedures, fee_summary::text AS fee_summary, total_amount,
            status, submitted_by, submitted_by_id, submitted_at, archived_by, archived_at, version, created_at
            """;

    public void insertHomepage(Homepage h) {
        jdbc.sql("""
                        INSERT INTO emr_svc.record_homepage
                            (id, homepage_no, visit_id, admission_id, patient_id, patient_no,
                             patient_name, patient_sex, birth_date, admit_age, visit_no,
                             dept_id, dept_name, ward_id, ward_name,
                             bed_no, admitted_at, discharged_at, stay_days, attending_doctor,
                             admission_way, discharge_mode, death_time, death_cause,
                             readmit_plan, marital_status,
                             occupation, nation, blood_type, rh_factor, rescue_count,
                             rescue_success_count, critical_case, hospital_infection,
                             chief_physician, resident_physician, coder, diagnoses, procedures,
                             fee_summary, total_amount, status, version)
                        VALUES (:id, :homepageNo, :visitId, CAST(:admissionId AS uuid), :patientId,
                                :patientNo, :patientName, :patientSex, CAST(:birthDate AS date),
                                :admitAge, :visitNo, CAST(:deptId AS uuid), :deptName,
                                CAST(:wardId AS uuid), :wardName, :bedNo,
                                CAST(:admittedAt AS timestamptz), CAST(:dischargedAt AS timestamptz),
                                :stayDays, :attendingDoctor, :admissionWay, :dischargeMode,
                                CAST(:deathTime AS timestamptz), :deathCause,
                                :readmitPlan, :maritalStatus, :occupation, :nation, :bloodType,
                                :rhFactor, :rescueCount, :rescueSuccessCount, :criticalCase,
                                :hospitalInfection, :chiefPhysician, :residentPhysician, :coder,
                                CAST(:diagnoses AS jsonb), CAST(:procedures AS jsonb),
                                CAST(:feeSummary AS jsonb), :totalAmount, 'DRAFT', 0)
                        """)
                .param("id", h.id()).param("homepageNo", h.homepageNo())
                .param("visitId", h.visitId()).param("admissionId", h.admissionId())
                .param("patientId", h.patientId()).param("patientNo", h.patientNo())
                .param("patientName", h.patientName()).param("patientSex", h.patientSex())
                .param("birthDate", h.birthDate() == null ? null : h.birthDate().toString())
                .param("admitAge", h.admitAge())
                .param("visitNo", h.visitNo())
                .param("deptId", h.deptId()).param("deptName", h.deptName())
                .param("wardId", h.wardId()).param("wardName", h.wardName())
                .param("bedNo", h.bedNo())
                .param("admittedAt", ts(h.admittedAt())).param("dischargedAt", ts(h.dischargedAt()))
                .param("stayDays", h.stayDays()).param("attendingDoctor", h.attendingDoctor())
                .param("admissionWay", h.admissionWay()).param("dischargeMode", h.dischargeMode())
                .param("deathTime", ts(h.deathTime())).param("deathCause", h.deathCause())
                .param("readmitPlan", h.readmitPlan()).param("maritalStatus", h.maritalStatus())
                .param("occupation", h.occupation()).param("nation", h.nation())
                .param("bloodType", h.bloodType()).param("rhFactor", h.rhFactor())
                .param("rescueCount", h.rescueCount())
                .param("rescueSuccessCount", h.rescueSuccessCount())
                .param("criticalCase", h.criticalCase())
                .param("hospitalInfection", h.hospitalInfection())
                .param("chiefPhysician", h.chiefPhysician())
                .param("residentPhysician", h.residentPhysician())
                .param("coder", h.coder())
                .param("diagnoses", h.diagnoses()).param("procedures", h.procedures())
                .param("feeSummary", h.feeSummary()).param("totalAmount", h.totalAmount())
                .update();
    }

    private static java.time.OffsetDateTime ts(java.time.Instant i) {
        return i == null ? null : java.time.OffsetDateTime.ofInstant(i, java.time.ZoneOffset.UTC);
    }

    public Optional<Homepage> findHomepage(UUID id) {
        return jdbc.sql("SELECT " + HOMEPAGE_COLUMNS
                        + " FROM emr_svc.record_homepage WHERE id = ?")
                .param(id).query(HOMEPAGE_MAPPER).optional();
    }

    /** 加行锁读取；必须在事务内调用。 */
    public Optional<Homepage> lockHomepage(UUID id) {
        return jdbc.sql("SELECT " + HOMEPAGE_COLUMNS
                        + " FROM emr_svc.record_homepage WHERE id = ? FOR UPDATE")
                .param(id).query(HOMEPAGE_MAPPER).optional();
    }

    public Optional<Homepage> findHomepageByVisit(UUID visitId) {
        return jdbc.sql("SELECT " + HOMEPAGE_COLUMNS
                        + " FROM emr_svc.record_homepage WHERE visit_id = ?")
                .param(visitId).query(HOMEPAGE_MAPPER).optional();
    }

    /**
     * 保存草稿（仅 DRAFT；CAS 版本号）。
     *
     * <p>只允许改"首页专有信息"与科室/病区/床位/主治医师的书写修正；
     * 诊断、费用、出院时间属于临床快照，任何状态都不在这里更新
     * （数据库触发器在 SUBMITTED 之后也会拒绝修改）。
     */
    public boolean updateHomepageDraft(UUID id, Homepage h, long expectedVersion) {
        return jdbc.sql("""
                        UPDATE emr_svc.record_homepage
                           SET dept_name = :deptName, ward_name = :wardName, bed_no = :bedNo,
                               attending_doctor = :attendingDoctor, admission_way = :admissionWay,
                               discharge_mode = :dischargeMode,
                               death_time = CAST(:deathTime AS timestamptz),
                               death_cause = :deathCause,
                               readmit_plan = :readmitPlan,
                               marital_status = :maritalStatus, occupation = :occupation,
                               nation = :nation, blood_type = :bloodType, rh_factor = :rhFactor,
                               rescue_count = :rescueCount,
                               rescue_success_count = :rescueSuccessCount,
                               critical_case = :criticalCase,
                               hospital_infection = :hospitalInfection,
                               chief_physician = :chiefPhysician,
                               resident_physician = :residentPhysician, coder = :coder,
                               procedures = CAST(:procedures AS jsonb),
                               version = version + 1, updated_at = now()
                         WHERE id = :id AND status = 'DRAFT' AND version = :version
                        """)
                .param("deptName", h.deptName()).param("wardName", h.wardName())
                .param("bedNo", h.bedNo()).param("attendingDoctor", h.attendingDoctor())
                .param("admissionWay", h.admissionWay()).param("dischargeMode", h.dischargeMode())
                .param("deathTime", ts(h.deathTime())).param("deathCause", h.deathCause())
                .param("readmitPlan", h.readmitPlan()).param("maritalStatus", h.maritalStatus())
                .param("occupation", h.occupation()).param("nation", h.nation())
                .param("bloodType", h.bloodType()).param("rhFactor", h.rhFactor())
                .param("rescueCount", h.rescueCount())
                .param("rescueSuccessCount", h.rescueSuccessCount())
                .param("criticalCase", h.criticalCase())
                .param("hospitalInfection", h.hospitalInfection())
                .param("chiefPhysician", h.chiefPhysician())
                .param("residentPhysician", h.residentPhysician())
                .param("coder", h.coder())
                .param("procedures", h.procedures())
                .param("id", id).param("version", expectedVersion)
                .update() > 0;
    }

    /**
     * 重新生成首页快照（CAS：仅 DRAFT）。
     *
     * <p>病案首页诊断/费用是"生成时快照"，出院后退费或补录诊断会让快照与临床账实不符；
     * 这里按当前临床数据重算，仅允许在草稿态（提交后由触发器拒绝修改）。
     */
    public boolean recalculateHomepage(UUID id, long expectedVersion, String diagnoses,
                                       String procedures, String feeSummary, BigDecimal totalAmount,
                                       java.time.Instant admittedAt, java.time.Instant dischargedAt,
                                       int stayDays) {
        return jdbc.sql("""
                        UPDATE emr_svc.record_homepage
                           SET diagnoses = CAST(:diagnoses AS jsonb),
                               procedures = CAST(:procedures AS jsonb),
                               fee_summary = CAST(:feeSummary AS jsonb),
                               total_amount = :totalAmount,
                               admitted_at = CAST(:admittedAt AS timestamptz),
                               discharged_at = CAST(:dischargedAt AS timestamptz),
                               stay_days = :stayDays,
                               version = version + 1, updated_at = now()
                         WHERE id = :id AND status = 'DRAFT' AND version = :version
                        """)
                .param("diagnoses", diagnoses).param("procedures", procedures)
                .param("feeSummary", feeSummary)
                .param("totalAmount", totalAmount)
                .param("admittedAt", ts(admittedAt)).param("dischargedAt", ts(dischargedAt))
                .param("stayDays", stayDays)
                .param("id", id).param("version", expectedVersion)
                .update() > 0;
    }

    /**
     * 提交（CAS：仅 DRAFT）。
     *
     * @param actorId 提交人账号主键（X-User-Id）；写入后归档按该主键做四眼校验（MF-13）
     */
    public boolean submitHomepage(UUID id, String actor, UUID actorId, long expectedVersion) {
        return jdbc.sql("""
                        UPDATE emr_svc.record_homepage
                           SET status = 'SUBMITTED', submitted_by = :actor,
                               submitted_by_id = :actorId, submitted_at = now(),
                               version = version + 1, updated_at = now()
                         WHERE id = :id AND status = 'DRAFT' AND version = :version
                        """)
                .param("actor", actor).param("actorId", actorId)
                .param("id", id).param("version", expectedVersion)
                .update() > 0;
    }

    /** 归档（CAS：仅 SUBMITTED；终态）。 */
    public boolean archiveHomepage(UUID id, String actor, long expectedVersion) {
        return jdbc.sql("""
                        UPDATE emr_svc.record_homepage
                           SET status = 'ARCHIVED', archived_by = :actor, archived_at = now(),
                               version = version + 1, updated_at = now()
                         WHERE id = :id AND status = 'SUBMITTED' AND version = :version
                        """)
                .param("actor", actor).param("id", id).param("version", expectedVersion)
                .update() > 0;
    }

    /**
     * 列表摘要（第六轮 MF-12）：患者姓名在 SQL 内掩码，不返回诊断/费用/手术明细。
     *
     * <p>一次请求最多 200 条；过去这里直接返回全字段实体，等于一次拉走 200 名患者的
     * 姓名 + 出院诊断 + 费用，且不写审计。
     */
    public List<HomepageSummary> listHomepageSummaries(String status, UUID patientId, UUID deptId,
                                                       int limit) {
        return jdbc.sql("""
                        SELECT id, homepage_no, visit_no, patient_no,
                               CASE WHEN patient_name IS NULL OR patient_name = '' THEN ''
                                    ELSE left(patient_name, 1) || '**' END AS patient_name_masked,
                               dept_id, dept_name, ward_name, discharged_at, stay_days, total_amount,
                               status, version
                          FROM emr_svc.record_homepage
                         WHERE (CAST(:status AS text) IS NULL OR status = CAST(:status AS text))
                           AND (CAST(:patientId AS uuid) IS NULL
                                OR patient_id = CAST(:patientId AS uuid))
                           AND (CAST(:deptId AS uuid) IS NULL OR dept_id = CAST(:deptId AS uuid))
                         ORDER BY created_at DESC
                         LIMIT :limit
                        """)
                .param("status", status).param("patientId", patientId)
                .param("deptId", deptId).param("limit", limit)
                .query((rs, i) -> new HomepageSummary(
                        rs.getObject("id", UUID.class), rs.getString("homepage_no"),
                        rs.getString("visit_no"), rs.getString("patient_no"),
                        rs.getString("patient_name_masked"), rs.getObject("dept_id", UUID.class),
                        rs.getString("dept_name"),
                        rs.getString("ward_name"), instant(rs, "discharged_at"),
                        rs.getInt("stay_days"), rs.getBigDecimal("total_amount"),
                        rs.getString("status"), rs.getLong("version")))
                .list();
    }

    public List<Homepage> listHomepages(String status, UUID patientId, UUID deptId, int limit) {
        return jdbc.sql("SELECT " + HOMEPAGE_COLUMNS + """
                          FROM emr_svc.record_homepage
                         WHERE (CAST(:status AS text) IS NULL OR status = CAST(:status AS text))
                           AND (CAST(:patientId AS uuid) IS NULL
                                OR patient_id = CAST(:patientId AS uuid))
                           AND (CAST(:deptId AS uuid) IS NULL OR dept_id = CAST(:deptId AS uuid))
                         ORDER BY created_at DESC
                         LIMIT :limit
                        """)
                .param("status", status).param("patientId", patientId)
                .param("deptId", deptId).param("limit", limit)
                .query(HOMEPAGE_MAPPER).list();
    }

    // ---------------------------------------------------------------- HQMS 上报批次

    private static final RowMapper<HqmsExportBatch> HQMS_MAPPER = (rs, i) -> new HqmsExportBatch(
            rs.getObject("id", UUID.class), rs.getString("batch_no"),
            rs.getDate("period_from").toLocalDate(), rs.getDate("period_to").toLocalDate(),
            rs.getInt("homepage_count"), rs.getBigDecimal("total_amount"),
            rs.getString("payload"), rs.getString("payload_hash"), rs.getString("status"),
            rs.getString("receipt_no"), rs.getString("receipt_message"), instant(rs, "receipt_at"),
            rs.getString("cancelled_reason"), instant(rs, "cancelled_at"),
            rs.getString("created_by"), rs.getLong("version"), instant(rs, "created_at"));

    private static final String HQMS_COLUMNS = """
            id, batch_no, period_from, period_to, homepage_count, total_amount, payload,
            payload_hash, status, receipt_no, receipt_message, receipt_at, cancelled_reason,
            cancelled_at, created_by, version, created_at
            """;

    /** 批次单号 HQYYYYMMDD######。 */
    public String nextHqmsBatchNo() {
        Long seq = jdbc.sql("SELECT nextval(CAST(:seq AS regclass))")
                .param("seq", "emr_svc.hqms_batch_no_seq").query(Long.class).single();
        return "HQ" + BusinessTime.today().format(DateTimeFormatter.BASIC_ISO_DATE)
                + String.format("%06d", seq);
    }

    /**
     * HQMS 上报的串行化锁（事务级 advisory lock，全局单键）。
     *
     * <p>与医保报盘同一原因：并发 create 时各自的 NOT EXISTS 都在对方提交前查空，
     * 同一首页会进入两个 GENERATED 批次。
     *
     * <p>键必须是**全局单键**（第六轮 MF-07）：按期间拼键时，期间重叠但不相等的两次
     * create（如 1..31 与 10..20）会取得不同锁，互不等待，同一首页照样能进两个批次。
     * 数据库侧由 `ux_hqms_item_active_homepage` 唯一索引兜底，这里让正常并发先排队。
     */
    public void lockHqmsExport() {
        jdbc.sql("""
                        WITH acquired AS (
                            SELECT pg_advisory_xact_lock(hashtextextended(CAST(:key AS text), 0))
                        )
                        SELECT 1 FROM acquired
                        """)
                .param("key", "emr:hqms-export")
                .query(Integer.class).single();
    }

    /** 期间内已归档、且尚未进入有效批次（GENERATED/ACCEPTED）的首页数。 */
    public long countHqmsPending(LocalDate from, LocalDate to) {
        return jdbc.sql("""
                        SELECT count(*)
                          FROM emr_svc.record_homepage h
                         WHERE h.status = 'ARCHIVED'
                           AND h.discharged_at IS NOT NULL
                           AND (h.discharged_at AT TIME ZONE 'Asia/Shanghai')::date
                               BETWEEN :from AND :to
                           AND NOT EXISTS (
                               SELECT 1
                                 FROM emr_svc.hqms_export_item i
                                WHERE i.homepage_id = h.id
                                  AND i.active)
                        """)
                .param("from", from).param("to", to)
                .query(Long.class).single();
    }

    /** 期间内已归档、但已被有效批次占用的首页数（用于"均已上报"的提示）。 */
    public long countHqmsAlreadyExported(LocalDate from, LocalDate to) {
        return jdbc.sql("""
                        SELECT count(*)
                          FROM emr_svc.record_homepage h
                         WHERE h.status = 'ARCHIVED'
                           AND h.discharged_at IS NOT NULL
                           AND (h.discharged_at AT TIME ZONE 'Asia/Shanghai')::date
                               BETWEEN :from AND :to
                           AND EXISTS (
                               SELECT 1
                                 FROM emr_svc.hqms_export_item i
                                WHERE i.homepage_id = h.id
                                  AND i.active)
                        """)
                .param("from", from).param("to", to)
                .query(Long.class).single();
    }

    /** 待上报首页（已归档、期间内、未被有效批次占用），按出院时间升序。 */
    public List<Homepage> listHqmsPending(LocalDate from, LocalDate to, int limit) {
        return jdbc.sql("SELECT " + HOMEPAGE_COLUMNS + """
                          FROM emr_svc.record_homepage h
                         WHERE h.status = 'ARCHIVED'
                           AND h.discharged_at IS NOT NULL
                           AND (h.discharged_at AT TIME ZONE 'Asia/Shanghai')::date
                               BETWEEN :from AND :to
                           AND NOT EXISTS (
                               SELECT 1
                                 FROM emr_svc.hqms_export_item i
                                WHERE i.homepage_id = h.id
                                  AND i.active)
                         ORDER BY h.discharged_at, h.homepage_no
                         LIMIT :limit
                        """)
                .param("from", from).param("to", to).param("limit", limit)
                .query(HOMEPAGE_MAPPER).list();
    }

    public void insertHqmsBatch(HqmsExportBatch b) {
        jdbc.sql("""
                        INSERT INTO emr_svc.hqms_export_batch
                            (id, batch_no, period_from, period_to, homepage_count, total_amount,
                             payload, payload_hash, status, created_by, version)
                        VALUES (:id, :batchNo, :from, :to, :count, :total,
                                :payload, :hash, :status, :actor, 0)
                        """)
                .param("id", b.id()).param("batchNo", b.batchNo())
                .param("from", b.periodFrom()).param("to", b.periodTo())
                .param("count", b.homepageCount()).param("total", b.totalAmount())
                .param("payload", b.payload()).param("hash", b.payloadHash())
                .param("status", b.status()).param("actor", b.createdBy()).update();
    }

    public void insertHqmsItem(UUID batchId, Homepage h) {
        jdbc.sql("""
                        INSERT INTO emr_svc.hqms_export_item
                            (batch_id, homepage_id, homepage_no, patient_no, discharged_at,
                             total_amount)
                        VALUES (:batchId, :homepageId, :homepageNo, :patientNo,
                                CAST(:dischargedAt AS timestamptz), :totalAmount)
                        """)
                .param("batchId", batchId).param("homepageId", h.id())
                .param("homepageNo", h.homepageNo()).param("patientNo", h.patientNo())
                .param("dischargedAt", ts(h.dischargedAt()))
                .param("totalAmount", h.totalAmount()).update();
    }

    public Optional<HqmsExportBatch> findHqmsBatch(UUID id) {
        return jdbc.sql("SELECT " + HQMS_COLUMNS
                        + " FROM emr_svc.hqms_export_batch WHERE id = ?")
                .param(id).query(HQMS_MAPPER).optional();
    }

    public List<HqmsExportBatch> listHqmsBatches(int limit) {
        return jdbc.sql("SELECT " + HQMS_COLUMNS + """
                          FROM emr_svc.hqms_export_batch
                         ORDER BY created_at DESC
                         LIMIT :limit
                        """)
                .param("limit", limit).query(HQMS_MAPPER).list();
    }

    /** 登记回执（CAS：仅 GENERATED 可回执）。 */
    public boolean updateHqmsReceipt(UUID id, String status, String receiptNo, String message,
                                     long expectedVersion) {
        return jdbc.sql("""
                        UPDATE emr_svc.hqms_export_batch
                           SET status = :status, receipt_no = :receiptNo,
                               receipt_message = :message, receipt_at = now(),
                               version = version + 1
                         WHERE id = :id AND status = 'GENERATED' AND version = :version
                        """)
                .param("status", status).param("receiptNo", receiptNo)
                .param("message", message).param("id", id).param("version", expectedVersion)
                .update() > 0;
    }

    /** 撤销未回执批次（CAS：仅 GENERATED 可撤销）。 */
    public boolean cancelHqmsBatch(UUID id, String reason, long expectedVersion) {
        return jdbc.sql("""
                        UPDATE emr_svc.hqms_export_batch
                           SET status = 'CANCELLED', cancelled_reason = :reason, cancelled_at = now(),
                               version = version + 1
                         WHERE id = :id AND status = 'GENERATED' AND version = :version
                        """)
                .param("reason", reason).param("id", id).param("version", expectedVersion)
                .update() > 0;
    }

    // ---------------------------------------------------------------- ICD 字典（G4）

    private static final RowMapper<IcdDiagnosis> ICD_DX_MAPPER = (rs, i) -> new IcdDiagnosis(
            rs.getString("code"), rs.getString("name"), rs.getString("chapter"),
            rs.getString("category"), rs.getBoolean("enabled"), rs.getLong("version"));

    private static final RowMapper<IcdProcedure> ICD_OP_MAPPER = (rs, i) -> new IcdProcedure(
            rs.getString("code"), rs.getString("name"), rs.getString("category"),
            rs.getBoolean("enabled"), rs.getLong("version"));

    public List<IcdDiagnosis> listIcdDiagnoses(String keyword, Boolean enabled, int limit) {
        String pattern = keyword == null || keyword.isBlank() ? null : "%" + keyword.trim() + "%";
        return jdbc.sql("""
                        SELECT code, name, chapter, category, enabled, version
                          FROM emr_svc.icd_diagnosis
                         WHERE (CAST(:pattern AS text) IS NULL OR code ILIKE CAST(:pattern AS text)
                                OR name ILIKE CAST(:pattern AS text))
                           AND (CAST(:enabled AS boolean) IS NULL
                                OR enabled = CAST(:enabled AS boolean))
                         ORDER BY code
                         LIMIT :limit
                        """)
                .param("pattern", pattern).param("enabled", enabled).param("limit", limit)
                .query(ICD_DX_MAPPER).list();
    }

    public Optional<IcdDiagnosis> findIcdDiagnosis(String code) {
        return jdbc.sql("""
                        SELECT code, name, chapter, category, enabled, version
                          FROM emr_svc.icd_diagnosis WHERE code = ?
                        """).param(code).query(ICD_DX_MAPPER).optional();
    }

    public List<IcdDiagnosis> findIcdDiagnoses(java.util.Collection<String> codes) {
        if (codes == null || codes.isEmpty()) {
            return List.of();
        }
        return jdbc.sql("""
                        SELECT code, name, chapter, category, enabled, version
                          FROM emr_svc.icd_diagnosis WHERE code IN (:codes)
                        """).param("codes", codes).query(ICD_DX_MAPPER).list();
    }

    public void insertIcdDiagnosis(IcdDiagnosis d) {
        jdbc.sql("""
                        INSERT INTO emr_svc.icd_diagnosis (code, name, chapter, category, enabled)
                        VALUES (:code, :name, CAST(:chapter AS text), CAST(:category AS text), true)
                        """)
                .param("code", d.code()).param("name", d.name())
                .param("chapter", d.chapter()).param("category", d.category()).update();
    }

    public boolean setIcdDiagnosisEnabled(String code, boolean enabled, long expectedVersion) {
        return jdbc.sql("""
                        UPDATE emr_svc.icd_diagnosis
                           SET enabled = :enabled, version = version + 1, updated_at = now()
                         WHERE code = :code AND version = :version
                        """).param("enabled", enabled).param("code", code)
                .param("version", expectedVersion).update() > 0;
    }

    public List<IcdProcedure> listIcdProcedures(String keyword, Boolean enabled, int limit) {
        String pattern = keyword == null || keyword.isBlank() ? null : "%" + keyword.trim() + "%";
        return jdbc.sql("""
                        SELECT code, name, category, enabled, version
                          FROM emr_svc.icd_procedure
                         WHERE (CAST(:pattern AS text) IS NULL OR code ILIKE CAST(:pattern AS text)
                                OR name ILIKE CAST(:pattern AS text))
                           AND (CAST(:enabled AS boolean) IS NULL
                                OR enabled = CAST(:enabled AS boolean))
                         ORDER BY code
                         LIMIT :limit
                        """)
                .param("pattern", pattern).param("enabled", enabled).param("limit", limit)
                .query(ICD_OP_MAPPER).list();
    }

    public Optional<IcdProcedure> findIcdProcedure(String code) {
        return jdbc.sql("""
                        SELECT code, name, category, enabled, version
                          FROM emr_svc.icd_procedure WHERE code = ?
                        """).param(code).query(ICD_OP_MAPPER).optional();
    }

    public List<IcdProcedure> findIcdProcedures(java.util.Collection<String> codes) {
        if (codes == null || codes.isEmpty()) {
            return List.of();
        }
        return jdbc.sql("""
                        SELECT code, name, category, enabled, version
                          FROM emr_svc.icd_procedure WHERE code IN (:codes)
                        """).param("codes", codes).query(ICD_OP_MAPPER).list();
    }

    public void insertIcdProcedure(IcdProcedure p) {
        jdbc.sql("""
                        INSERT INTO emr_svc.icd_procedure (code, name, category, enabled)
                        VALUES (:code, :name, CAST(:category AS text), true)
                        """)
                .param("code", p.code()).param("name", p.name())
                .param("category", p.category()).update();
    }

    public boolean setIcdProcedureEnabled(String code, boolean enabled, long expectedVersion) {
        return jdbc.sql("""
                        UPDATE emr_svc.icd_procedure
                           SET enabled = :enabled, version = version + 1, updated_at = now()
                         WHERE code = :code AND version = :version
                        """).param("enabled", enabled).param("code", code)
                .param("version", expectedVersion).update() > 0;
    }

    public long countIcdDiagnoses() {
        Long count = jdbc.sql("SELECT count(*) FROM emr_svc.icd_diagnosis")
                .query(Long.class).single();
        return count == null ? 0 : count;
    }

    public long countIcdProcedures() {
        Long count = jdbc.sql("SELECT count(*) FROM emr_svc.icd_procedure")
                .query(Long.class).single();
        return count == null ? 0 : count;
    }

    // ---------------------------------------------------------------- 病案编码（G4）

    private static final RowMapper<HomepageCoding> CODING_MAPPER = (rs, i) -> new HomepageCoding(
            rs.getObject("id", UUID.class), rs.getObject("homepage_id", UUID.class),
            rs.getString("status"), rs.getString("coder"), instant(rs, "coded_at"),
            rs.getString("note"), rs.getString("diagnoses"), rs.getString("procedures"),
            rs.getLong("version"), instant(rs, "created_at"), instant(rs, "updated_at"));

    public Optional<HomepageCoding> findCoding(UUID homepageId) {
        return jdbc.sql("""
                        SELECT id, homepage_id, status, coder, coded_at, note, diagnoses, procedures,
                               version, created_at, updated_at
                          FROM emr_svc.homepage_coding WHERE homepage_id = ?
                        """).param(homepageId).query(CODING_MAPPER).optional();
    }

    public void insertCoding(HomepageCoding c) {
        jdbc.sql("""
                        INSERT INTO emr_svc.homepage_coding
                            (id, homepage_id, status, coder, note, diagnoses, procedures, version)
                        VALUES (:id, :homepageId, :status, :coder, CAST(:note AS text),
                                CAST(:diagnoses AS jsonb), CAST(:procedures AS jsonb), 0)
                        """)
                .param("id", c.id()).param("homepageId", c.homepageId())
                .param("status", c.status()).param("coder", c.coder()).param("note", c.note())
                .param("diagnoses", c.diagnoses()).param("procedures", c.procedures()).update();
    }

    /** 保存编码草稿（仅 CODING 态；CAS 版本号）。 */
    public boolean updateCodingDraft(UUID homepageId, String diagnoses, String procedures,
                                     String note, String coder, long expectedVersion) {
        return jdbc.sql("""
                        UPDATE emr_svc.homepage_coding
                           SET diagnoses = CAST(:diagnoses AS jsonb),
                               procedures = CAST(:procedures AS jsonb),
                               note = CAST(:note AS text), coder = :coder,
                               version = version + 1, updated_at = now()
                         WHERE homepage_id = :homepageId AND status = 'CODING' AND version = :version
                        """)
                .param("diagnoses", diagnoses).param("procedures", procedures)
                .param("note", note).param("coder", coder).param("homepageId", homepageId)
                .param("version", expectedVersion).update() > 0;
    }

    /** 编码提交：CODING → CODED（CAS）。 */
    public boolean markCoded(UUID homepageId, String coder, long expectedVersion) {
        return jdbc.sql("""
                        UPDATE emr_svc.homepage_coding
                           SET status = 'CODED', coder = :coder, coded_at = now(),
                               version = version + 1, updated_at = now()
                         WHERE homepage_id = :homepageId AND status = 'CODING' AND version = :version
                        """).param("coder", coder).param("homepageId", homepageId)
                .param("version", expectedVersion).update() > 0;
    }

    /**
     * 编码结果回写首页（诊断/手术快照）。
     *
     * <p>触发器会校验 homepage_coding 已为 CODED；这里再用 status='SUBMITTED' + version 兜一层，
     * 避免对草稿或已归档首页写入。
     */
    public boolean applyCodedSnapshot(UUID homepageId, String diagnoses, String procedures,
                                      String coder, long expectedVersion) {
        return jdbc.sql("""
                        UPDATE emr_svc.record_homepage
                           SET diagnoses = CAST(:diagnoses AS jsonb),
                               procedures = CAST(:procedures AS jsonb),
                               coder = :coder, version = version + 1, updated_at = now()
                         WHERE id = :id AND status = 'SUBMITTED' AND version = :version
                        """)
                .param("diagnoses", diagnoses).param("procedures", procedures)
                .param("coder", coder).param("id", homepageId)
                .param("version", expectedVersion).update() > 0;
    }

    // ---------------------------------------------------------------- 编码更正（G10）

    private static final RowMapper<CodingCorrection> CORRECTION_MAPPER =
            (rs, i) -> new CodingCorrection(
                    rs.getObject("id", UUID.class), rs.getString("correction_no"),
                    rs.getObject("homepage_id", UUID.class), rs.getString("status"),
                    rs.getString("reason"), rs.getString("diagnoses"),
                    rs.getString("procedures"), rs.getString("note"),
                    rs.getString("created_by"), rs.getString("reviewed_by"),
                    instant(rs, "reviewed_at"), rs.getString("review_note"),
                    rs.getString("cancelled_by"), instant(rs, "cancelled_at"),
                    rs.getString("cancel_reason"), rs.getLong("version"),
                    instant(rs, "created_at"));

    public String nextCorrectionNo() {
        Long seq = jdbc.sql("SELECT nextval(CAST(:seq AS regclass))")
                .param("seq", "emr_svc.coding_correction_no_seq").query(Long.class).single();
        return "CC" + BusinessTime.today().format(DateTimeFormatter.BASIC_ISO_DATE)
                + String.format("%06d", seq == null ? 0 : seq);
    }

    /**
     * 插入更正申请；同一首页已有待审批申请时返回 false（部分唯一索引兜底并发）。
     */
    public boolean insertCorrection(CodingCorrection c) {
        return jdbc.sql("""
                        INSERT INTO emr_svc.coding_correction
                            (id, correction_no, homepage_id, status, reason, diagnoses,
                             procedures, note, created_by, version)
                        VALUES (:id, :no, :homepageId, :status, :reason,
                                CAST(:diagnoses AS jsonb), CAST(:procedures AS jsonb),
                                CAST(:note AS text), :createdBy, 0)
                        ON CONFLICT (homepage_id) WHERE status = 'SUBMITTED' DO NOTHING
                        """)
                .param("id", c.id()).param("no", c.correctionNo())
                .param("homepageId", c.homepageId()).param("status", c.status())
                .param("reason", c.reason()).param("diagnoses", c.diagnoses())
                .param("procedures", c.procedures()).param("note", c.note())
                .param("createdBy", c.createdBy()).update() > 0;
    }

    public Optional<CodingCorrection> findCorrection(UUID id) {
        return jdbc.sql("""
                        SELECT id, correction_no, homepage_id, status, reason, diagnoses,
                               procedures, note, created_by, reviewed_by, reviewed_at,
                               review_note, cancelled_by, cancelled_at, cancel_reason,
                               version, created_at
                          FROM emr_svc.coding_correction WHERE id = ?
                        """).param(id).query(CORRECTION_MAPPER).optional();
    }

    /** 加行锁读取更正申请；审批/驳回/撤回前必须调用。 */
    public Optional<CodingCorrection> lockCorrection(UUID id) {
        return jdbc.sql("""
                        SELECT id, correction_no, homepage_id, status, reason, diagnoses,
                               procedures, note, created_by, reviewed_by, reviewed_at,
                               review_note, cancelled_by, cancelled_at, cancel_reason,
                               version, created_at
                          FROM emr_svc.coding_correction WHERE id = ? FOR UPDATE
                        """).param(id).query(CORRECTION_MAPPER).optional();
    }

    public List<CodingCorrection> listCorrections(UUID homepageId, int limit) {
        return jdbc.sql("""
                        SELECT id, correction_no, homepage_id, status, reason, diagnoses,
                               procedures, note, created_by, reviewed_by, reviewed_at,
                               review_note, cancelled_by, cancelled_at, cancel_reason,
                               version, created_at
                          FROM emr_svc.coding_correction
                         WHERE homepage_id = :homepageId
                         ORDER BY created_at DESC
                         LIMIT :limit
                        """).param("homepageId", homepageId).param("limit", limit)
                .query(CORRECTION_MAPPER).list();
    }

    /** 最近一次审批通过的更正内容：编码详情以此为准（原编码记录保持不可变）。 */
    public Optional<CodingCorrection> findLatestApprovedCorrection(UUID homepageId) {
        return jdbc.sql("""
                        SELECT id, correction_no, homepage_id, status, reason, diagnoses,
                               procedures, note, created_by, reviewed_by, reviewed_at,
                               review_note, cancelled_by, cancelled_at, cancel_reason,
                               version, created_at
                          FROM emr_svc.coding_correction
                         WHERE homepage_id = ? AND status = 'APPROVED'
                         ORDER BY reviewed_at DESC NULLS LAST, created_at DESC
                         LIMIT 1
                        """).param(homepageId).query(CORRECTION_MAPPER).optional();
    }

    /** 审批 CAS：仅 SUBMITTED 可流转。 */
    public boolean reviewCorrection(UUID id, long expectedVersion, String actor, boolean approved,
                                    String note) {
        return jdbc.sql("""
                        UPDATE emr_svc.coding_correction
                           SET status = :status, reviewed_by = :actor, reviewed_at = now(),
                               review_note = CAST(:note AS text), version = version + 1,
                               updated_at = now()
                         WHERE id = :id AND status = 'SUBMITTED' AND version = :version
                        """)
                .param("status", approved ? CodingCorrection.APPROVED
                        : CodingCorrection.REJECTED)
                .param("actor", actor).param("note", note)
                .param("id", id).param("version", expectedVersion).update() > 0;
    }

    public boolean cancelCorrection(UUID id, long expectedVersion, String actor, String reason) {
        return jdbc.sql("""
                        UPDATE emr_svc.coding_correction
                           SET status = 'CANCELLED', cancelled_by = :actor, cancelled_at = now(),
                               cancel_reason = :reason, version = version + 1, updated_at = now()
                         WHERE id = :id AND status = 'SUBMITTED' AND version = :version
                        """)
                .param("actor", actor).param("reason", reason)
                .param("id", id).param("version", expectedVersion).update() > 0;
    }

    /** 待编码队列：已提交且编码未完成（无编码记录或仍为 CODING）。 */
    public List<CodingQueueItem> listCodingQueue(String keyword, int limit) {
        String pattern = keyword == null || keyword.isBlank() ? null : "%" + keyword.trim() + "%";
        return jdbc.sql("""
                        SELECT h.id, h.homepage_no, h.patient_no,
                               CASE WHEN h.patient_name IS NULL OR h.patient_name = '' THEN ''
                                    ELSE left(h.patient_name, 1) || '**' END AS patient_name_masked,
                               h.dept_name, h.discharged_at, h.stay_days,
                               COALESCE(c.status, 'PENDING') AS coding_status,
                               c.coder, c.coded_at,
                               h.version AS homepage_version,
                               COALESCE(c.version, 0) AS coding_version
                          FROM emr_svc.record_homepage h
                          LEFT JOIN emr_svc.homepage_coding c ON c.homepage_id = h.id
                         WHERE h.status = 'SUBMITTED'
                           AND (c.status IS NULL OR c.status <> 'CODED')
                           AND (CAST(:pattern AS text) IS NULL
                                OR h.homepage_no ILIKE CAST(:pattern AS text)
                                OR h.patient_no ILIKE CAST(:pattern AS text))
                         ORDER BY h.discharged_at NULLS LAST, h.homepage_no
                         LIMIT :limit
                        """).param("pattern", pattern).param("limit", limit)
                .query((rs, i) -> new CodingQueueItem(
                        rs.getObject("id", UUID.class), rs.getString("homepage_no"),
                        rs.getString("patient_no"), rs.getString("patient_name_masked"),
                        rs.getString("dept_name"), instant(rs, "discharged_at"),
                        rs.getInt("stay_days"), rs.getString("coding_status"),
                        rs.getString("coder"), instant(rs, "coded_at"),
                        rs.getLong("homepage_version"), rs.getLong("coding_version")))
                .list();
    }

    /** 待编码首页数（已提交且未 CODED）。 */
    public long countPendingCoding() {
        Long count = jdbc.sql("""
                        SELECT count(*)
                          FROM emr_svc.record_homepage h
                          LEFT JOIN emr_svc.homepage_coding c ON c.homepage_id = h.id
                         WHERE h.status = 'SUBMITTED'
                           AND (c.status IS NULL OR c.status <> 'CODED')
                        """).query(Long.class).single();
        return count == null ? 0 : count;
    }

    /** 已编码首页数（SUBMITTED + CODED，等待归档）。 */
    public long countCodedHomepages() {
        Long count = jdbc.sql("""
                        SELECT count(*)
                          FROM emr_svc.record_homepage h
                          JOIN emr_svc.homepage_coding c ON c.homepage_id = h.id
                         WHERE h.status = 'SUBMITTED' AND c.status = 'CODED'
                        """).query(Long.class).single();
        return count == null ? 0 : count;
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
