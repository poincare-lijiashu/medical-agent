package com.medassist.tech.repo;

import com.medassist.common.time.BusinessTime;
import com.medassist.tech.domain.TechModel.Application;
import com.medassist.tech.domain.TechModel.CriticalValue;
import com.medassist.tech.domain.TechModel.ExamItem;
import com.medassist.tech.domain.TechModel.Report;
import com.medassist.tech.domain.TechModel.Specimen;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 医技仓储（JdbcClient 显式 SQL）。
 *
 * <p>并发语义：申请/标本/危急值的状态迁移都使用"期望状态 + 版本号" CAS；
 * 报告采用追加版本，切换当前版本时先取消旧版本 is_current 再插入新版本（同一事务）。
 */
@Repository
public class TechRepository {

    private static final RowMapper<ExamItem> ITEM_MAPPER = (rs, i) -> new ExamItem(
            rs.getObject("id", UUID.class), rs.getString("item_code"), rs.getString("name"),
            rs.getString("category"), rs.getString("specimen_type"),
            rs.getBoolean("enabled"), rs.getLong("version"));

    private static final RowMapper<Application> APP_MAPPER = (rs, i) -> new Application(
            rs.getObject("id", UUID.class), rs.getString("application_no"),
            rs.getObject("patient_id", UUID.class), rs.getString("patient_no"),
            rs.getObject("visit_id", UUID.class), rs.getString("visit_no"),
            rs.getObject("dept_id", UUID.class), rs.getString("applied_by"),
            rs.getString("category"), rs.getString("item_code"), rs.getString("item_name"),
            rs.getString("body_part"), rs.getString("priority"), rs.getString("clinical_diag"),
            rs.getString("specimen_type"), rs.getString("status"), rs.getLong("version"),
            rs.getTimestamp("created_at").toInstant());

    private static final RowMapper<Specimen> SPECIMEN_MAPPER = (rs, i) -> new Specimen(
            rs.getObject("id", UUID.class), rs.getObject("application_id", UUID.class),
            rs.getString("barcode"), rs.getString("specimen_type"), rs.getString("status"),
            rs.getString("collected_by"), rs.getTimestamp("collected_at").toInstant(),
            rs.getString("received_by"),
            rs.getTimestamp("received_at") == null ? null : rs.getTimestamp("received_at").toInstant(),
            rs.getString("reject_reason"), rs.getLong("version"));

    private static final RowMapper<Report> REPORT_MAPPER = (rs, i) -> new Report(
            rs.getObject("id", UUID.class), rs.getString("report_no"),
            rs.getObject("application_id", UUID.class), rs.getInt("revision"),
            rs.getBoolean("is_current"), rs.getString("findings"), rs.getString("conclusion"),
            rs.getString("reporter"), rs.getString("reviewer"),
            rs.getTimestamp("reviewed_at") == null ? null : rs.getTimestamp("reviewed_at").toInstant(),
            rs.getString("status"), rs.getBoolean("critical_flag"),
            rs.getTimestamp("created_at").toInstant());

    private static final RowMapper<CriticalValue> CRITICAL_MAPPER = (rs, i) -> new CriticalValue(
            rs.getObject("id", UUID.class), rs.getObject("application_id", UUID.class),
            rs.getObject("report_id", UUID.class), rs.getString("item_name"),
            rs.getString("result_value"), rs.getString("reference_range"), rs.getString("severity"),
            rs.getString("status"), rs.getString("notified_to"),
            rs.getTimestamp("notified_at") == null ? null : rs.getTimestamp("notified_at").toInstant(),
            rs.getString("acknowledged_by"),
            rs.getTimestamp("acknowledged_at") == null ? null : rs.getTimestamp("acknowledged_at").toInstant(),
            rs.getString("note"), rs.getTimestamp("created_at").toInstant());

    private final JdbcClient jdbc;

    public TechRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    // ---------------------------------------------------------------- 单号

    public String nextApplicationNo() {
        return nextNo("tech_svc.application_no_seq", "AP");
    }

    public String nextReportNo() {
        return nextNo("tech_svc.report_no_seq", "RP");
    }

    /** 标本条码：BC + yyyyMMdd + 8 位序列（与 LIS 扫码枪兼容的数字串）。 */
    public String nextBarcode() {
        Long seq = jdbc.sql("SELECT nextval(CAST(:seq AS regclass))")
                .param("seq", "tech_svc.barcode_seq").query(Long.class).single();
        return "BC" + BusinessTime.today().format(DateTimeFormatter.BASIC_ISO_DATE)
                + String.format("%08d", seq);
    }

    private String nextNo(String sequence, String prefix) {
        Long seq = jdbc.sql("SELECT nextval(CAST(:seq AS regclass))")
                .param("seq", sequence).query(Long.class).single();
        return prefix + BusinessTime.today().format(DateTimeFormatter.BASIC_ISO_DATE)
                + String.format("%06d", seq);
    }

    // ---------------------------------------------------------------- 项目字典

    public Optional<ExamItem> findExamItemByCode(String itemCode) {
        return jdbc.sql("""
                        SELECT id, item_code, name, category, specimen_type, enabled, version
                          FROM tech_svc.md_exam_item WHERE item_code = ?
                        """).param(itemCode).query(ITEM_MAPPER).optional();
    }

    public List<ExamItem> listExamItems(String category, String keyword, int limit) {
        String pattern = keyword == null || keyword.isBlank() ? null : "%" + keyword.trim() + "%";
        return jdbc.sql("""
                        SELECT id, item_code, name, category, specimen_type, enabled, version
                          FROM tech_svc.md_exam_item
                         WHERE (CAST(:category AS text) IS NULL OR category = CAST(:category AS text))
                           AND (CAST(:pattern AS text) IS NULL OR name ILIKE CAST(:pattern AS text)
                                OR item_code ILIKE CAST(:pattern AS text))
                           AND enabled = true
                         ORDER BY item_code
                         LIMIT :limit
                        """)
                .param("category", category).param("pattern", pattern).param("limit", limit)
                .query(ITEM_MAPPER).list();
    }

    // ---------------------------------------------------------------- 申请

    public void insertApplication(Application a) {
        jdbc.sql("""
                        INSERT INTO tech_svc.exam_application
                            (id, application_no, patient_id, patient_no, visit_id, visit_no, dept_id,
                             applied_by, category, item_code, item_name, body_part, priority,
                             clinical_diag, specimen_type, status, version)
                        VALUES (:id, :no, :patientId, :patientNo, CAST(:visitId AS uuid),
                                CAST(:visitNo AS text), CAST(:deptId AS uuid), :appliedBy,
                                :category, :itemCode, :itemName, CAST(:bodyPart AS text), :priority,
                                CAST(:clinicalDiag AS text), CAST(:specimenType AS text),
                                :status, 0)
                        """)
                .param("id", a.id()).param("no", a.applicationNo())
                .param("patientId", a.patientId()).param("patientNo", a.patientNo())
                .param("visitId", a.visitId()).param("visitNo", a.visitNo())
                .param("deptId", a.deptId()).param("appliedBy", a.appliedBy())
                .param("category", a.category()).param("itemCode", a.itemCode())
                .param("itemName", a.itemName()).param("bodyPart", a.bodyPart())
                .param("priority", a.priority()).param("clinicalDiag", a.clinicalDiag())
                .param("specimenType", a.specimenType()).param("status", a.status()).update();
    }

    public Optional<Application> findApplication(UUID id) {
        return jdbc.sql("""
                        SELECT id, application_no, patient_id, patient_no, visit_id, visit_no, dept_id,
                               applied_by, category, item_code, item_name, body_part, priority,
                               clinical_diag, specimen_type, status, version, created_at
                          FROM tech_svc.exam_application WHERE id = ?
                        """).param(id).query(APP_MAPPER).optional();
    }

    /** 加行锁读取申请；必须在事务内调用。 */
    public Optional<Application> lockApplication(UUID id) {
        return jdbc.sql("""
                        SELECT id, application_no, patient_id, patient_no, visit_id, visit_no, dept_id,
                               applied_by, category, item_code, item_name, body_part, priority,
                               clinical_diag, specimen_type, status, version, created_at
                          FROM tech_svc.exam_application WHERE id = ? FOR UPDATE
                        """).param(id).query(APP_MAPPER).optional();
    }

    /** 状态迁移（CAS：期望状态 + 版本号）。 */
    public boolean updateApplicationStatus(UUID id, String expected, String next, long expectedVersion) {
        return jdbc.sql("""
                        UPDATE tech_svc.exam_application
                           SET status = :next, version = version + 1, updated_at = now()
                         WHERE id = :id AND status = :expected AND version = :version
                        """)
                .param("next", next).param("id", id)
                .param("expected", expected).param("version", expectedVersion)
                .update() > 0;
    }

    public List<Application> listApplications(String status, UUID patientId, int limit) {
        return jdbc.sql("""
                        SELECT id, application_no, patient_id, patient_no, visit_id, visit_no, dept_id,
                               applied_by, category, item_code, item_name, body_part, priority,
                               clinical_diag, specimen_type, status, version, created_at
                          FROM tech_svc.exam_application
                         WHERE (CAST(:status AS text) IS NULL OR status = CAST(:status AS text))
                           AND (CAST(:patientId AS uuid) IS NULL OR patient_id = CAST(:patientId AS uuid))
                         ORDER BY created_at DESC
                         LIMIT :limit
                        """)
                .param("status", status).param("patientId", patientId).param("limit", limit)
                .query(APP_MAPPER).list();
    }

    // ---------------------------------------------------------------- 标本

    public void insertSpecimen(UUID id, UUID applicationId, String barcode, String specimenType,
                               String collectedBy) {
        jdbc.sql("""
                        INSERT INTO tech_svc.specimen
                            (id, application_id, barcode, specimen_type, status, collected_by, version)
                        VALUES (:id, :appId, :barcode, :type, 'COLLECTED', :by, 0)
                        """)
                .param("id", id).param("appId", applicationId).param("barcode", barcode)
                .param("type", specimenType).param("by", collectedBy).update();
    }

    public Optional<Specimen> findSpecimenByApplication(UUID applicationId) {
        return jdbc.sql("""
                        SELECT id, application_id, barcode, specimen_type, status, collected_by,
                               collected_at, received_by, received_at, reject_reason, version
                          FROM tech_svc.specimen WHERE application_id = ?
                        """).param(applicationId).query(SPECIMEN_MAPPER).optional();
    }

    public Optional<Specimen> lockSpecimen(UUID id) {
        return jdbc.sql("""
                        SELECT id, application_id, barcode, specimen_type, status, collected_by,
                               collected_at, received_by, received_at, reject_reason, version
                          FROM tech_svc.specimen WHERE id = ? FOR UPDATE
                        """).param(id).query(SPECIMEN_MAPPER).optional();
    }

    /** 标本接收/拒收（CAS：仅 COLLECTED 可迁移）。 */
    public boolean receiveSpecimen(UUID id, String status, String operator, long expectedVersion,
                                   String rejectReason) {
        return jdbc.sql("""
                        UPDATE tech_svc.specimen
                           SET status = :status, received_by = :operator, received_at = now(),
                               reject_reason = CAST(:reason AS text), version = version + 1
                         WHERE id = :id AND status = 'COLLECTED' AND version = :version
                        """)
                .param("status", status).param("operator", operator).param("reason", rejectReason)
                .param("id", id).param("version", expectedVersion).update() > 0;
    }

    // ---------------------------------------------------------------- 报告

    public void insertReport(Report r) {
        jdbc.sql("""
                        INSERT INTO tech_svc.exam_report
                            (id, report_no, application_id, revision, is_current, findings,
                             conclusion, reporter, reviewer, reviewed_at, status, critical_flag)
                        VALUES (:id, :no, :appId, :revision, :current, :findings, :conclusion,
                                :reporter, CAST(:reviewer AS text),
                                CASE WHEN CAST(:reviewer AS text) IS NULL THEN NULL ELSE now() END,
                                :status, :criticalFlag)
                        """)
                .param("id", r.id()).param("no", r.reportNo())
                .param("appId", r.applicationId()).param("revision", r.revision())
                .param("current", r.current()).param("findings", r.findings())
                .param("conclusion", r.conclusion()).param("reporter", r.reporter())
                .param("reviewer", r.reviewer()).param("status", r.status())
                .param("criticalFlag", r.criticalFlag()).update();
    }

    /** 把该申请当前报告标记为非当前（修订时调用，须在同一事务内）。 */
    public int clearCurrentReport(UUID applicationId) {
        return jdbc.sql("""
                        UPDATE tech_svc.exam_report SET is_current = false
                         WHERE application_id = :appId AND is_current
                        """).param("appId", applicationId).update();
    }

    public Optional<Report> findReport(UUID id) {
        return jdbc.sql("""
                        SELECT id, report_no, application_id, revision, is_current, findings,
                               conclusion, reporter, reviewer, reviewed_at, status, critical_flag, created_at
                          FROM tech_svc.exam_report WHERE id = ?
                        """).param(id).query(REPORT_MAPPER).optional();
    }

    public Optional<Report> findCurrentReport(UUID applicationId) {
        return jdbc.sql("""
                        SELECT id, report_no, application_id, revision, is_current, findings,
                               conclusion, reporter, reviewer, reviewed_at, status, critical_flag, created_at
                          FROM tech_svc.exam_report
                         WHERE application_id = :appId AND is_current
                        """).param("appId", applicationId).query(REPORT_MAPPER).optional();
    }

    public List<Report> listReports(UUID applicationId) {
        return jdbc.sql("""
                        SELECT id, report_no, application_id, revision, is_current, findings,
                               conclusion, reporter, reviewer, reviewed_at, status, critical_flag, created_at
                          FROM tech_svc.exam_report
                         WHERE application_id = :appId ORDER BY revision
                        """).param("appId", applicationId).query(REPORT_MAPPER).list();
    }

    /** 重写草稿（仅 DRAFT）：技师改错别字/补充所见，不产生新版本。 */
    public boolean updateDraftReport(UUID id, String findings, String conclusion, boolean criticalFlag) {
        return jdbc.sql("""
                        UPDATE tech_svc.exam_report
                           SET findings = :findings, conclusion = :conclusion,
                               critical_flag = :criticalFlag
                         WHERE id = :id AND status = 'DRAFT'
                        """)
                .param("findings", findings).param("conclusion", conclusion)
                .param("criticalFlag", criticalFlag).param("id", id).update() > 0;
    }

    /** 审核签发（CAS：仅 DRAFT 可迁移到 FINAL）。 */
    public boolean finalizeReport(UUID id, String reviewer, long expectedRevision) {
        return jdbc.sql("""
                        UPDATE tech_svc.exam_report
                           SET status = 'FINAL', reviewer = :reviewer, reviewed_at = now()
                         WHERE id = :id AND status = 'DRAFT' AND revision = :revision
                        """)
                .param("reviewer", reviewer).param("id", id).param("revision", expectedRevision)
                .update() > 0;
    }

    public int nextReportRevision(UUID applicationId) {
        Integer max = jdbc.sql("SELECT COALESCE(max(revision), 0) FROM tech_svc.exam_report WHERE application_id = ?")
                .param(applicationId).query(Integer.class).single();
        return (max == null ? 0 : max) + 1;
    }

    // ---------------------------------------------------------------- 危急值

    public void insertCriticalValue(CriticalValue c) {
        jdbc.sql("""
                        INSERT INTO tech_svc.critical_value
                            (id, application_id, report_id, item_name, result_value, reference_range,
                             severity, status, notified_to, notified_at)
                        VALUES (:id, :appId, :reportId, :itemName, :value, CAST(:range AS text),
                                :severity, 'PENDING', CAST(:notifiedTo AS text),
                                CASE WHEN CAST(:notifiedTo AS text) IS NULL THEN NULL ELSE now() END)
                        """)
                .param("id", c.id()).param("appId", c.applicationId()).param("reportId", c.reportId())
                .param("itemName", c.itemName()).param("value", c.resultValue())
                .param("range", c.referenceRange()).param("severity", c.severity())
                .param("notifiedTo", c.notifiedTo()).update();
    }

    public Optional<CriticalValue> findCriticalValue(UUID id) {
        return jdbc.sql("""
                        SELECT id, application_id, report_id, item_name, result_value, reference_range,
                               severity, status, notified_to, notified_at, acknowledged_by,
                               acknowledged_at, note, created_at
                          FROM tech_svc.critical_value WHERE id = ?
                        """).param(id).query(CRITICAL_MAPPER).optional();
    }

    public List<CriticalValue> listCriticalValues(String status, UUID applicationId, int limit) {
        return jdbc.sql("""
                        SELECT id, application_id, report_id, item_name, result_value, reference_range,
                               severity, status, notified_to, notified_at, acknowledged_by,
                               acknowledged_at, note, created_at
                          FROM tech_svc.critical_value
                         WHERE (CAST(:status AS text) IS NULL OR status = CAST(:status AS text))
                           AND (CAST(:appId AS uuid) IS NULL OR application_id = CAST(:appId AS uuid))
                         ORDER BY created_at DESC
                         LIMIT :limit
                        """)
                .param("status", status).param("appId", applicationId).param("limit", limit)
                .query(CRITICAL_MAPPER).list();
    }

    /** 临床确认（CAS：仅 PENDING）。 */
    public boolean acknowledgeCritical(UUID id, String actor, String note) {
        return jdbc.sql("""
                        UPDATE tech_svc.critical_value
                           SET status = 'ACKNOWLEDGED', acknowledged_by = :actor, acknowledged_at = now(),
                               note = CAST(:note AS text)
                         WHERE id = :id AND status = 'PENDING'
                        """)
                .param("actor", actor).param("note", note).param("id", id).update() > 0;
    }

    /** 关闭（CAS：仅 ACKNOWLEDGED，不允许跳过确认）。 */
    public boolean closeCritical(UUID id, String note) {
        return jdbc.sql("""
                        UPDATE tech_svc.critical_value
                           SET status = 'CLOSED',
                               note = COALESCE(CAST(:note AS text), note)
                         WHERE id = :id AND status = 'ACKNOWLEDGED'
                        """)
                .param("note", note).param("id", id).update() > 0;
    }

    public int countOpenCritical(UUID applicationId) {
        Long n = jdbc.sql("""
                        SELECT count(*) FROM tech_svc.critical_value
                         WHERE application_id = ? AND status <> 'CLOSED'
                        """).param(applicationId).query(Long.class).single();
        return n == null ? 0 : n.intValue();
    }

    // ---------------------------------------------------------------- 审计

    public void logChange(String actor, String action, String targetType, String targetId, String detailJson) {
        jdbc.sql("""
                        INSERT INTO tech_svc.tech_change_log
                            (actor, action, target_type, target_id, detail)
                        VALUES (:actor, :action, :targetType, :targetId, CAST(:detail AS jsonb))
                        """)
                .param("actor", actor).param("action", action)
                .param("targetType", targetType).param("targetId", targetId)
                .param("detail", detailJson == null ? "{}" : detailJson).update();
    }
}
