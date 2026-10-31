package com.medassist.emr.repo;

import com.medassist.common.time.BusinessTime;
import com.medassist.emr.domain.EmrModel.Amendment;
import com.medassist.emr.domain.EmrModel.ChangeRequest;
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
            rs.getString("doc_type"), rs.getString("title"), rs.getString("author"),
            rs.getString("status"), rs.getInt("revision"),
            rs.getTimestamp("created_at").toInstant());

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
        jdbc.sql("""
                        INSERT INTO emr_svc.medical_record
                            (id, record_no, patient_id, patient_no, visit_id, visit_no, dept_id,
                             doc_type, template_code, title, content, content_text, author,
                             status, revision, version, visit_started_at)
                        VALUES (:id, :no, :patientId, :patientNo, CAST(:visitId AS uuid),
                                CAST(:visitNo AS text), CAST(:deptId AS uuid), :docType,
                                :templateCode, :title, CAST(:content AS jsonb), :contentText,
                                :author, :status, 1, 0, CAST(:visitStartedAt AS timestamptz))
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
        String sql = pattern == null ? """
                        SELECT id, record_no, patient_id, patient_no, doc_type, title,
                               author, status, revision, created_at
                          FROM emr_svc.medical_record
                         WHERE 1 = 1
                        """ + SEARCH_FILTERS + """
                         ORDER BY created_at DESC
                         LIMIT :limit
                        """ : """
                        SELECT id, record_no, patient_id, patient_no, doc_type, title,
                               author, status, revision, created_at
                          FROM (
                                (SELECT id, record_no, patient_id, patient_no, doc_type, title,
                                        author, status, revision, created_at
                                   FROM emr_svc.medical_record
                                  WHERE (title ILIKE CAST(:pattern AS text) ESCAPE '\\'
                                         OR content_text ILIKE CAST(:pattern AS text) ESCAPE '\\')
                                """ + SEARCH_FILTERS + """
                                  ORDER BY created_at DESC
                                  LIMIT :limit)
                                UNION
                                (SELECT id, record_no, patient_id, patient_no, doc_type, title,
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
                .param("fromDate", from).param("toDate", to).param("limit", limit);
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
