package com.medassist.tech.repo;

import com.medassist.common.time.BusinessTime;
import com.medassist.tech.domain.TechModel.Application;
import com.medassist.tech.domain.TechModel.CriticalValue;
import com.medassist.tech.domain.TechModel.Device;
import com.medassist.tech.domain.TechModel.DeviceQc;
import com.medassist.tech.domain.TechModel.DeviceStatusCount;
import com.medassist.tech.domain.TechModel.ExamItem;
import com.medassist.tech.domain.TechModel.LisInboundMessage;
import com.medassist.tech.domain.TechModel.PacsAccessLog;
import com.medassist.tech.domain.TechModel.PacsSeries;
import com.medassist.tech.domain.TechModel.PacsStudy;
import com.medassist.tech.domain.TechModel.ReadingQueueItem;
import com.medassist.tech.domain.TechModel.ReadingTask;
import com.medassist.tech.domain.TechModel.Report;
import com.medassist.tech.domain.TechModel.ReportTemplate;
import com.medassist.tech.domain.TechModel.Specimen;
import com.medassist.tech.domain.TechModel.WorklistItem;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.time.OffsetDateTime;
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
            rs.getTimestamp("created_at").toInstant(),
            rs.getObject("source_order_id", UUID.class));

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
            rs.getTimestamp("created_at").toInstant(),
            rs.getObject("template_id", UUID.class), rs.getString("structured_json"));

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

    /** 开立申请；{@code campusId} 为开单院区（G49，写入时固化，不接受空值）。 */
    public void insertApplication(Application a, UUID campusId) {
        jdbc.sql("""
                        INSERT INTO tech_svc.exam_application
                            (id, application_no, patient_id, patient_no, visit_id, visit_no, dept_id,
                             applied_by, category, item_code, item_name, body_part, priority,
                             clinical_diag, specimen_type, status, version, source_order_id, campus_id)
                        VALUES (:id, :no, :patientId, :patientNo, CAST(:visitId AS uuid),
                                CAST(:visitNo AS text), CAST(:deptId AS uuid), :appliedBy,
                                :category, :itemCode, :itemName, CAST(:bodyPart AS text), :priority,
                                CAST(:clinicalDiag AS text), CAST(:specimenType AS text),
                                :status, 0, CAST(:sourceOrderId AS uuid), :campusId)
                        """)
                .param("id", a.id()).param("no", a.applicationNo())
                .param("patientId", a.patientId()).param("patientNo", a.patientNo())
                .param("visitId", a.visitId()).param("visitNo", a.visitNo())
                .param("deptId", a.deptId()).param("appliedBy", a.appliedBy())
                .param("category", a.category()).param("itemCode", a.itemCode())
                .param("itemName", a.itemName()).param("bodyPart", a.bodyPart())
                .param("priority", a.priority()).param("clinicalDiag", a.clinicalDiag())
                .param("specimenType", a.specimenType()).param("status", a.status())
                .param("sourceOrderId", a.sourceOrderId()).param("campusId", campusId).update();
    }

    /** 申请所属院区；用于院区级越权判定（不存在返回空）。 */
    public Optional<UUID> findApplicationCampus(UUID applicationId) {
        return jdbc.sql("""
                        SELECT campus_id FROM tech_svc.exam_application WHERE id = ?
                        """).param(applicationId).query(UUID.class).optional();
    }

    /** 按来源医嘱查申请（临床转送幂等键；手工开单返回空）。 */
    public Optional<Application> findApplicationBySourceOrder(UUID sourceOrderId) {
        return jdbc.sql("""
                        SELECT id, application_no, patient_id, patient_no, visit_id, visit_no, dept_id,
                               applied_by, category, item_code, item_name, body_part, priority,
                               clinical_diag, specimen_type, status, version, created_at,
                               source_order_id
                          FROM tech_svc.exam_application WHERE source_order_id = ?
                        """).param(sourceOrderId).query(APP_MAPPER).optional();
    }

    /** 按来源医嘱加行锁查申请；必须在事务内调用（医嘱撤销/停嘱联动作废）。 */
    public Optional<Application> lockApplicationBySourceOrder(UUID sourceOrderId) {
        return jdbc.sql("""
                        SELECT id, application_no, patient_id, patient_no, visit_id, visit_no, dept_id,
                               applied_by, category, item_code, item_name, body_part, priority,
                               clinical_diag, specimen_type, status, version, created_at,
                               source_order_id
                          FROM tech_svc.exam_application WHERE source_order_id = ? FOR UPDATE
                        """).param(sourceOrderId).query(APP_MAPPER).optional();
    }

    /** 作废申请（CAS：版本号一致且仍为 APPLIED——已采集/执行中的申请不能作废）。 */
    public boolean cancelApplication(UUID id, long expectedVersion, String reason) {
        return jdbc.sql("""
                        UPDATE tech_svc.exam_application
                           SET status = 'CANCELLED', cancel_reason = :reason,
                               version = version + 1, updated_at = now()
                         WHERE id = :id AND version = :version AND status = 'APPLIED'
                        """)
                .param("reason", reason).param("id", id).param("version", expectedVersion)
                .update() > 0;
    }

    public Optional<Application> findApplication(UUID id) {
        return jdbc.sql("""
                        SELECT id, application_no, patient_id, patient_no, visit_id, visit_no, dept_id,
                               applied_by, category, item_code, item_name, body_part, priority,
                               clinical_diag, specimen_type, status, version, created_at,
                               source_order_id
                          FROM tech_svc.exam_application WHERE id = ?
                        """).param(id).query(APP_MAPPER).optional();
    }

    /** 加行锁读取申请；必须在事务内调用。 */
    public Optional<Application> lockApplication(UUID id) {
        return jdbc.sql("""
                        SELECT id, application_no, patient_id, patient_no, visit_id, visit_no, dept_id,
                               applied_by, category, item_code, item_name, body_part, priority,
                               clinical_diag, specimen_type, status, version, created_at,
                               source_order_id
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

    /**
     * 申请列表（P0-S3 行级范围 + G49 院区隔离）。
     *
     * <p>{@code deptIds} 非空时只返回这些科室；{@code campusCsv} 非空时只返回这些院区
     * （逗号分隔 UUID，来自 {@code DataScope.campusFilter()}；空串表示跨院区角色不过滤）。
     */
    public List<Application> listApplications(String status, UUID patientId,
                                              java.util.Collection<UUID> deptIds, String campusCsv,
                                              int limit) {
        return jdbc.sql("""
                        SELECT id, application_no, patient_id, patient_no, visit_id, visit_no, dept_id,
                               applied_by, category, item_code, item_name, body_part, priority,
                               clinical_diag, specimen_type, status, version, created_at,
                               source_order_id
                          FROM tech_svc.exam_application
                         WHERE (CAST(:status AS text) IS NULL OR status = CAST(:status AS text))
                           AND (CAST(:patientId AS uuid) IS NULL OR patient_id = CAST(:patientId AS uuid))
                           AND (CAST(:deptIds AS uuid[]) IS NULL OR dept_id = ANY (CAST(:deptIds AS uuid[])))
                           AND (CAST(:campusCsv AS text) = ''
                                OR campus_id = ANY (string_to_array(CAST(:campusCsv AS text), ',')::uuid[]))
                         ORDER BY created_at DESC
                         LIMIT :limit
                        """)
                .param("status", status).param("patientId", patientId)
                .param("deptIds", deptIds == null || deptIds.isEmpty() ? null : deptIds.toArray(UUID[]::new))
                .param("campusCsv", campusCsv == null ? "" : campusCsv)
                .param("limit", limit)
                .query(APP_MAPPER).list();
    }

    /**
     * 各状态申请数量（P0-F4）：一条 GROUP BY 拿到准确待办数。
     *
     * <p>{@code deptIds} / {@code campusCsv} 与列表的行级范围保持一致，
     * 否则 KPI 会显示"看不到的待办"。
     */
    public java.util.Map<String, Integer> countApplicationsByStatus(
            java.util.Collection<UUID> deptIds, String campusCsv) {
        java.util.Map<String, Integer> counts = new java.util.LinkedHashMap<>();
        UUID[] deptArray = deptIds == null || deptIds.isEmpty() ? null : deptIds.toArray(UUID[]::new);
        String campus = campusCsv == null ? "" : campusCsv;
        jdbc.sql("""
                        SELECT status, count(*) AS n
                          FROM tech_svc.exam_application
                         WHERE (CAST(:deptIds AS uuid[]) IS NULL OR dept_id = ANY (CAST(:deptIds AS uuid[])))
                           AND (CAST(:campusCsv AS text) = ''
                                OR campus_id = ANY (string_to_array(CAST(:campusCsv AS text), ',')::uuid[]))
                         GROUP BY status
                        """)
                .param("deptIds", deptArray).param("campusCsv", campus)
                .query((rs, i) -> java.util.Map.entry(rs.getString("status"), rs.getInt("n")))
                .list()
                .forEach(e -> counts.put(e.getKey(), e.getValue()));
        // 合成键：待采集 = 已开立的检验申请（检查类不需要标本，不进此队列）
        Integer pendingCollect = jdbc.sql("""
                        SELECT count(*)
                          FROM tech_svc.exam_application
                         WHERE status = 'APPLIED' AND category = 'LAB'
                           AND (CAST(:deptIds AS uuid[]) IS NULL OR dept_id = ANY (CAST(:deptIds AS uuid[])))
                           AND (CAST(:campusCsv AS text) = ''
                                OR campus_id = ANY (string_to_array(CAST(:campusCsv AS text), ',')::uuid[]))
                        """)
                .param("deptIds", deptArray).param("campusCsv", campus)
                .query(Integer.class).single();
        counts.put("PENDING_COLLECT", pendingCollect == null ? 0 : pendingCollect);
        return counts;
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
                             conclusion, reporter, reviewer, reviewed_at, status, critical_flag,
                             template_id, structured_json)
                        VALUES (:id, :no, :appId, :revision, :current, :findings, :conclusion,
                                :reporter, CAST(:reviewer AS text),
                                CASE WHEN CAST(:reviewer AS text) IS NULL THEN NULL ELSE now() END,
                                :status, :criticalFlag, CAST(:templateId AS uuid),
                                CAST(:structured AS jsonb))
                        """)
                .param("id", r.id()).param("no", r.reportNo())
                .param("appId", r.applicationId()).param("revision", r.revision())
                .param("current", r.current()).param("findings", r.findings())
                .param("conclusion", r.conclusion()).param("reporter", r.reporter())
                .param("reviewer", r.reviewer()).param("status", r.status())
                .param("criticalFlag", r.criticalFlag())
                .param("templateId", r.templateId()).param("structured", r.structuredJson())
                .update();
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
                               conclusion, reporter, reviewer, reviewed_at, status, critical_flag,
                               created_at, template_id, structured_json
                          FROM tech_svc.exam_report WHERE id = ?
                        """).param(id).query(REPORT_MAPPER).optional();
    }

    public Optional<Report> findCurrentReport(UUID applicationId) {
        return jdbc.sql("""
                        SELECT id, report_no, application_id, revision, is_current, findings,
                               conclusion, reporter, reviewer, reviewed_at, status, critical_flag,
                               created_at, template_id, structured_json
                          FROM tech_svc.exam_report
                         WHERE application_id = :appId AND is_current
                        """).param("appId", applicationId).query(REPORT_MAPPER).optional();
    }

    public List<Report> listReports(UUID applicationId) {
        return jdbc.sql("""
                        SELECT id, report_no, application_id, revision, is_current, findings,
                               conclusion, reporter, reviewer, reviewed_at, status, critical_flag,
                               created_at, template_id, structured_json
                          FROM tech_svc.exam_report
                         WHERE application_id = :appId ORDER BY revision
                        """).param("appId", applicationId).query(REPORT_MAPPER).list();
    }

    /** 重写草稿（仅 DRAFT）：技师改错别字/补充所见，不产生新版本。 */
    public boolean updateDraftReport(UUID id, String findings, String conclusion,
                                     boolean criticalFlag, UUID templateId,
                                     String structuredJson) {
        return jdbc.sql("""
                        UPDATE tech_svc.exam_report
                           SET findings = :findings, conclusion = :conclusion,
                               critical_flag = :criticalFlag,
                               template_id = CAST(:templateId AS uuid),
                               structured_json = CAST(:structured AS jsonb)
                         WHERE id = :id AND status = 'DRAFT'
                        """)
                .param("findings", findings).param("conclusion", conclusion)
                .param("criticalFlag", criticalFlag)
                .param("templateId", templateId).param("structured", structuredJson)
                .param("id", id).update() > 0;
    }

    /** 签发：首版落 FINAL，修订版（revision>1）落 AMENDED，两者都记录审核人（CAS：仅 DRAFT）。 */
    public boolean finalizeReport(UUID id, String reviewer, long expectedRevision) {
        return jdbc.sql("""
                        UPDATE tech_svc.exam_report
                           SET status = CASE WHEN revision > 1 THEN 'AMENDED' ELSE 'FINAL' END,
                               reviewer = :reviewer, reviewed_at = now()
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

    /** 危急值列表（G49）：按申请院区收敛，{@code campusCsv} 为空串表示跨院区角色不过滤。 */
    public List<CriticalValue> listCriticalValues(String status, UUID applicationId,
                                                  String campusCsv, int limit) {
        return jdbc.sql("""
                        SELECT c.id, c.application_id, c.report_id, c.item_name, c.result_value,
                               c.reference_range, c.severity, c.status, c.notified_to, c.notified_at,
                               c.acknowledged_by, c.acknowledged_at, c.note, c.created_at
                          FROM tech_svc.critical_value c
                          JOIN tech_svc.exam_application a ON a.id = c.application_id
                         WHERE (CAST(:status AS text) IS NULL OR c.status = CAST(:status AS text))
                           AND (CAST(:appId AS uuid) IS NULL OR c.application_id = CAST(:appId AS uuid))
                           AND (CAST(:campusCsv AS text) = ''
                                OR a.campus_id = ANY (string_to_array(CAST(:campusCsv AS text), ',')::uuid[]))
                         ORDER BY c.created_at DESC
                         LIMIT :limit
                        """)
                .param("status", status).param("appId", applicationId)
                .param("campusCsv", campusCsv == null ? "" : campusCsv).param("limit", limit)
                .query(CRITICAL_MAPPER).list();
    }

    /**
     * 按报告定向读取危急值明细。
     *
     * <p>不能用"全库前 200 条再过滤"：危急值一多，早年报告会被截断成"没有危急值"，
     * 临床上等同于漏报。
     */
    public List<CriticalValue> listCriticalValuesByReport(UUID reportId) {
        return jdbc.sql("""
                        SELECT id, application_id, report_id, item_name, result_value, reference_range,
                               severity, status, notified_to, notified_at, acknowledged_by,
                               acknowledged_at, note, created_at
                          FROM tech_svc.critical_value
                         WHERE report_id = ?
                         ORDER BY created_at
                        """).param(reportId).query(CRITICAL_MAPPER).list();
    }

    /**
     * 按申请读取危急值（G67，评审 §3.8）。
     *
     * <p>危急值天然属于"这次检查"，报告修订会追加新 revision，若按 {@code report_id} 读，
     * 修订后界面会显示"无危急值"。这里按申请维度读取，修订版与历史版共享同一组危急值。
     */
    public List<CriticalValue> listCriticalValuesByApplication(UUID applicationId) {
        return jdbc.sql("""
                        SELECT id, application_id, report_id, item_name, result_value, reference_range,
                               severity, status, notified_to, notified_at, acknowledged_by,
                               acknowledged_at, note, created_at
                          FROM tech_svc.critical_value
                         WHERE application_id = ?
                         ORDER BY created_at
                        """).param(applicationId).query(CRITICAL_MAPPER).list();
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

    // ---------------------------------------------------------------- LIS 入站消息（N6）

    private static final RowMapper<LisInboundMessage> LIS_MAPPER = (rs, i) -> new LisInboundMessage(
            rs.getObject("id", UUID.class), rs.getString("message_control_id"),
            rs.getString("message_type"), rs.getString("sending_app"),
            rs.getString("application_no"), rs.getObject("application_id", UUID.class),
            rs.getObject("report_id", UUID.class), rs.getString("status"),
            rs.getString("error_message"), rs.getString("raw_message"),
            rs.getString("message_hash"), rs.getString("actor"),
            rs.getTimestamp("received_at").toInstant());

    public Optional<Application> findApplicationByNo(String applicationNo) {
        return jdbc.sql("""
                        SELECT id, application_no, patient_id, patient_no, visit_id, visit_no, dept_id,
                               applied_by, category, item_code, item_name, body_part, priority,
                               clinical_diag, specimen_type, status, version, created_at,
                               source_order_id
                          FROM tech_svc.exam_application WHERE application_no = ?
                        """).param(applicationNo).query(APP_MAPPER).optional();
    }

    public Optional<LisInboundMessage> findLisMessageByControlId(String messageControlId) {
        return jdbc.sql("""
                        SELECT id, message_control_id, message_type, sending_app, application_no,
                               application_id, report_id, status, error_message, raw_message,
                               message_hash, actor, received_at
                          FROM tech_svc.lis_inbound_message WHERE message_control_id = ?
                        """).param(messageControlId).query(LIS_MAPPER).optional();
    }

    public Optional<LisInboundMessage> findLisMessage(UUID id) {
        return jdbc.sql("""
                        SELECT id, message_control_id, message_type, sending_app, application_no,
                               application_id, report_id, status, error_message, raw_message,
                               message_hash, actor, received_at
                          FROM tech_svc.lis_inbound_message WHERE id = ?
                        """).param(id).query(LIS_MAPPER).optional();
    }

    /**
     * 回写处理结果（G67）。
     *
     * <p>只允许从 PROCESSING 收敛，避免"重放刚抢到占位、首轮又把结果写回去"的竞态。
     */
    public boolean updateLisMessageResult(UUID id, String status, String errorMessage,
                                          UUID applicationId, UUID reportId) {
        return jdbc.sql("""
                        UPDATE tech_svc.lis_inbound_message
                           SET status = :status, error_message = CAST(:error AS text),
                               application_id = CAST(:applicationId AS uuid),
                               report_id = CAST(:reportId AS uuid)
                         WHERE id = :id AND status = 'PROCESSING'
                        """)
                .param("id", id).param("status", status).param("error", errorMessage)
                .param("applicationId", applicationId).param("reportId", reportId)
                .update() > 0;
    }

    /**
     * 重放占位：FAILED/IGNORED → PROCESSING（CAS），并发重放只有一个能继续。
     *
     * <p>另允许接管"卡死超过 10 分钟仍 PROCESSING"的占位（进程崩溃残留），
     * 与医保实时结算 DELIVERING 超时接管同一口径。
     */
    public boolean claimLisMessageForReplay(UUID id, String expectedStatus) {
        return jdbc.sql("""
                        UPDATE tech_svc.lis_inbound_message
                           SET status = 'PROCESSING', error_message = NULL
                         WHERE id = :id
                           AND (status = :expected
                                OR (status = 'PROCESSING'
                                    AND received_at < now() - interval '10 minutes'))
                        """)
                .param("id", id).param("expected", expectedStatus).update() > 0;
    }

    /**
     * 落库入站消息。
     *
     * @return false 表示并发下同一控制 ID 已被另一请求占位（唯一索引兜底），调用方应改为读取已有记录
     */
    public boolean insertLisMessage(LisInboundMessage m) {
        return jdbc.sql("""
                        INSERT INTO tech_svc.lis_inbound_message
                            (id, message_control_id, message_type, sending_app, application_no,
                             application_id, report_id, status, error_message, raw_message,
                             message_hash, actor, received_at)
                        VALUES (:id, :controlId, :type, :app, :applicationNo,
                                CAST(:applicationId AS uuid), CAST(:reportId AS uuid), :status,
                                :error, :raw, :hash, :actor, now())
                        ON CONFLICT (message_control_id) DO NOTHING
                        """)
                .param("id", m.id()).param("controlId", m.messageControlId())
                .param("type", m.messageType()).param("app", m.sendingApp())
                .param("applicationNo", m.applicationNo()).param("applicationId", m.applicationId())
                .param("reportId", m.reportId()).param("status", m.status())
                .param("error", m.errorMessage()).param("raw", m.rawMessage())
                .param("hash", m.messageHash()).param("actor", m.actor()).update() > 0;
    }

    // ---------------------------------------------------------------- 影像（PACS）

    private static final RowMapper<PacsStudy> PACS_MAPPER =
            (rs, i) -> new PacsStudy(
                    rs.getObject("id", UUID.class), rs.getObject("application_id", UUID.class),
                    rs.getString("study_instance_uid"), rs.getString("accession_number"),
                    rs.getString("modality"), rs.getString("body_part"), rs.getString("status"),
                    rs.getInt("series_count"), rs.getInt("image_count"), rs.getString("viewer_url"),
                    rs.getTimestamp("started_at") == null ? null : rs.getTimestamp("started_at").toInstant(),
                    rs.getTimestamp("completed_at") == null ? null : rs.getTimestamp("completed_at").toInstant(),
                    rs.getLong("version"), rs.getTimestamp("created_at").toInstant());

    private static final RowMapper<WorklistItem> WORKLIST_MAPPER =
            (rs, i) -> new WorklistItem(
                    rs.getObject("application_id", UUID.class), rs.getString("application_no"),
                    rs.getString("patient_no"), rs.getString("item_code"), rs.getString("item_name"),
                    rs.getString("body_part"), rs.getString("priority"),
                    rs.getString("application_status"), rs.getTimestamp("created_at").toInstant(),
                    rs.getString("modality"), rs.getObject("study_id", UUID.class),
                    rs.getString("study_instance_uid"), rs.getString("study_status"),
                    (Integer) rs.getObject("series_count"), (Integer) rs.getObject("image_count"),
                    rs.getString("viewer_url"));

    /** 医技项目对应的 DICOM Modality（未配置时 OT）。 */
    public Optional<String> findItemModality(String itemCode) {
        return jdbc.sql("""
                        SELECT modality FROM tech_svc.md_exam_item WHERE item_code = ?
                        """).param(itemCode).query(String.class).optional();
    }

    public void insertPacsStudy(PacsStudy s) {
        jdbc.sql("""
                        INSERT INTO tech_svc.pacs_study
                            (id, application_id, study_instance_uid, accession_number, modality,
                             body_part, status, series_count, image_count, viewer_url, version)
                        VALUES (:id, :appId, :uid, :accession, :modality, CAST(:bodyPart AS text),
                                :status, :series, :image, CAST(:viewer AS text), 0)
                        """)
                .param("id", s.id()).param("appId", s.applicationId()).param("uid", s.studyInstanceUid())
                .param("accession", s.accessionNumber()).param("modality", s.modality())
                .param("bodyPart", s.bodyPart()).param("status", s.status())
                .param("series", s.seriesCount()).param("image", s.imageCount())
                .param("viewer", s.viewerUrl()).update();
    }

    /** 某申请的**当前**影像登记（未取消）；已取消的记录只作为历史保留，不参与幂等判断。 */
    public Optional<PacsStudy> findActivePacsStudyByApplication(UUID applicationId) {
        return jdbc.sql("""
                        SELECT id, application_id, study_instance_uid, accession_number, modality,
                               body_part, status, series_count, image_count, viewer_url,
                               started_at, completed_at, version, created_at
                          FROM tech_svc.pacs_study
                         WHERE application_id = ? AND status <> 'CANCELLED'
                         ORDER BY created_at DESC
                         LIMIT 1
                        """).param(applicationId).query(PACS_MAPPER).optional();
    }

    public Optional<PacsStudy> findPacsStudyByUid(String studyInstanceUid) {
        return jdbc.sql("""
                        SELECT id, application_id, study_instance_uid, accession_number, modality,
                               body_part, status, series_count, image_count, viewer_url,
                               started_at, completed_at, version, created_at
                          FROM tech_svc.pacs_study WHERE study_instance_uid = ?
                        """).param(studyInstanceUid).query(PACS_MAPPER).optional();
    }

    /** 加行锁读取影像登记；必须在事务内调用。 */
    public Optional<PacsStudy> lockPacsStudyByUid(String studyInstanceUid) {
        return jdbc.sql("""
                        SELECT id, application_id, study_instance_uid, accession_number, modality,
                               body_part, status, series_count, image_count, viewer_url,
                               started_at, completed_at, version, created_at
                          FROM tech_svc.pacs_study WHERE study_instance_uid = ? FOR UPDATE
                        """).param(studyInstanceUid).query(PACS_MAPPER).optional();
    }

    /** 影像状态迁移（CAS：期望状态 + 版本号）；数量为空表示保持不变。 */
    public boolean updatePacsStudyStatus(String studyInstanceUid, String expected, String next,
                                         Integer seriesCount, Integer imageCount, String viewerUrl,
                                         long expectedVersion) {
        return jdbc.sql("""
                        UPDATE tech_svc.pacs_study
                           SET status = :next,
                               series_count = COALESCE(CAST(:series AS integer), series_count),
                               image_count = COALESCE(CAST(:image AS integer), image_count),
                               viewer_url = COALESCE(CAST(:viewer AS text), viewer_url),
                               started_at = CASE WHEN :next = 'IN_PROGRESS' AND started_at IS NULL
                                                 THEN now() ELSE started_at END,
                               completed_at = CASE WHEN :next = 'COMPLETED' THEN now() ELSE completed_at END,
                               version = version + 1,
                               updated_at = now()
                         WHERE study_instance_uid = :uid AND status = :expected AND version = :version
                        """)
                .param("next", next).param("series", seriesCount).param("image", imageCount)
                .param("viewer", viewerUrl).param("uid", studyInstanceUid)
                .param("expected", expected).param("version", expectedVersion).update() > 0;
    }

    /**
     * 检查类 Worklist：未完成的检查申请（APPLIED / IN_PROGRESS），按设备类型与开单时间过滤。
     *
     * <p>三点刻意设计（第五轮 R5-M7 / R5-S6）：
     * <ul>
     *   <li>字典表用 LEFT JOIN + COALESCE：医技项目改码或停用不会让申请从设备列表"静默消失"；</li>
     *   <li>时间过滤用调用方算好的绝对时刻 [fromTs, toTs)，不用 {@code AT TIME ZONE} 逐行折算——
     *       后者无法走索引，且在夜班跨天场景下语义容易错；</li>
     *   <li>fromTs 可为 null（表示"开单日 ≤ 今天"），夜班 23 点开单的检查次日仍能看到。</li>
     * </ul>
     */
    public List<WorklistItem> listWorklist(String modality, OffsetDateTime fromTs, OffsetDateTime toTs,
                                           String campusCsv, int limit) {
        return jdbc.sql("""
                        SELECT a.id AS application_id, a.application_no, a.patient_no,
                               a.item_code, a.item_name, a.body_part, a.priority,
                               a.status AS application_status, a.created_at,
                               COALESCE(i.modality, 'OT') AS modality,
                               s.id AS study_id, s.study_instance_uid,
                               s.status AS study_status, s.series_count, s.image_count, s.viewer_url
                          FROM tech_svc.exam_application a
                          LEFT JOIN tech_svc.md_exam_item i ON i.item_code = a.item_code
                          LEFT JOIN tech_svc.pacs_study s
                                 ON s.application_id = a.id AND s.status <> 'CANCELLED'
                         WHERE a.category = 'EXAM'
                           AND a.status IN ('APPLIED', 'IN_PROGRESS')
                           AND (s.status IS NULL OR s.status IN ('SCHEDULED', 'ARRIVED', 'IN_PROGRESS'))
                           AND (CAST(:modality AS text) IS NULL
                                OR COALESCE(i.modality, 'OT') = CAST(:modality AS text))
                           AND (CAST(:campusCsv AS text) = ''
                                OR a.campus_id = ANY (string_to_array(CAST(:campusCsv AS text), ',')::uuid[]))
                           AND (CAST(:fromTs AS timestamptz) IS NULL
                                OR a.created_at >= CAST(:fromTs AS timestamptz))
                           AND (CAST(:toTs AS timestamptz) IS NULL
                                OR a.created_at < CAST(:toTs AS timestamptz))
                         ORDER BY CASE a.priority WHEN 'STAT' THEN 0 WHEN 'URGENT' THEN 1 ELSE 2 END,
                                  a.created_at
                         LIMIT :limit
                        """)
                .param("modality", modality).param("fromTs", fromTs).param("toTs", toTs)
                .param("campusCsv", campusCsv == null ? "" : campusCsv)
                .param("limit", limit)
                .query(WORKLIST_MAPPER).list();
    }

    // ---------------------------------------------------------------- 阅片与调阅（G5）

    private static final RowMapper<ReadingTask> READING_TASK_MAPPER =
            (rs, i) -> new ReadingTask(
                    rs.getObject("id", UUID.class), rs.getObject("study_id", UUID.class),
                    rs.getString("assignee"), rs.getString("status"),
                    rs.getTimestamp("claimed_at").toInstant(),
                    rs.getTimestamp("released_at") == null ? null : rs.getTimestamp("released_at").toInstant(),
                    rs.getTimestamp("completed_at") == null ? null : rs.getTimestamp("completed_at").toInstant(),
                    rs.getString("note"), rs.getLong("version"));

    private static final RowMapper<ReadingQueueItem> READING_QUEUE_MAPPER =
            (rs, i) -> new ReadingQueueItem(
                    rs.getObject("study_id", UUID.class), rs.getString("study_instance_uid"),
                    rs.getString("accession_number"), rs.getString("modality"),
                    rs.getString("body_part"), rs.getInt("series_count"), rs.getInt("image_count"),
                    rs.getTimestamp("completed_at") == null ? null : rs.getTimestamp("completed_at").toInstant(),
                    rs.getString("viewer_url"),
                    rs.getObject("application_id", UUID.class), rs.getString("application_no"),
                    rs.getString("patient_no"), rs.getString("item_name"), rs.getString("priority"),
                    rs.getObject("dept_id", UUID.class), rs.getString("report_status"),
                    rs.getObject("reading_task_id", UUID.class), rs.getString("assignee"),
                    rs.getString("reading_status"),
                    rs.getTimestamp("claimed_at") == null ? null : rs.getTimestamp("claimed_at").toInstant(),
                    rs.getTimestamp("read_completed_at") == null ? null
                            : rs.getTimestamp("read_completed_at").toInstant());

    private static final RowMapper<PacsSeries> PACS_SERIES_MAPPER =
            (rs, i) -> new PacsSeries(
                    rs.getObject("id", UUID.class), rs.getObject("study_id", UUID.class),
                    rs.getString("series_instance_uid"), (Integer) rs.getObject("series_number"),
                    rs.getString("modality"), rs.getString("body_part"), rs.getString("description"),
                    rs.getInt("instance_count"), rs.getTimestamp("synced_at").toInstant());

    private static final RowMapper<PacsAccessLog> PACS_ACCESS_MAPPER =
            (rs, i) -> new PacsAccessLog(
                    rs.getObject("id", UUID.class), rs.getObject("study_id", UUID.class),
                    rs.getString("study_instance_uid"), rs.getString("accession_number"),
                    rs.getString("actor"), rs.getString("action"),
                    rs.getString("series_instance_uid"), rs.getString("sop_instance_uid"),
                    rs.getString("detail"), rs.getTimestamp("created_at").toInstant());

    /**
     * 阅片队列：已完成（COMPLETED）的影像检查。
     *
     * <p>state 过滤（大小写不敏感）：UNCLAIMED 未领取且未完成过、CLAIMED 有人正在写、
     * MINE 我领取的、DONE 已完成过；为空返回全部。日期按检查完成时刻归日。
     */
    public List<ReadingQueueItem> listReadingQueue(String modality, String state, String assignee,
                                                   OffsetDateTime fromTs, OffsetDateTime toTs,
                                                   String campusCsv, int limit) {
        return jdbc.sql("""
                        SELECT s.id AS study_id, s.study_instance_uid, s.accession_number,
                               s.modality, s.body_part, s.series_count, s.image_count,
                               s.completed_at, s.viewer_url,
                               a.id AS application_id, a.application_no, a.patient_no,
                               a.item_name, a.priority, a.dept_id,
                               r.status AS report_status,
                               t.id AS reading_task_id, t.assignee, t.status AS reading_status,
                               t.claimed_at,
                               (SELECT max(d.completed_at) FROM tech_svc.pacs_reading_task d
                                 WHERE d.study_id = s.id AND d.status = 'COMPLETED')
                                   AS read_completed_at
                          FROM tech_svc.pacs_study s
                          JOIN tech_svc.exam_application a ON a.id = s.application_id
                          LEFT JOIN tech_svc.exam_report r
                                 ON r.application_id = a.id AND r.is_current
                          LEFT JOIN tech_svc.pacs_reading_task t
                                 ON t.study_id = s.id AND t.status = 'CLAIMED'
                         WHERE s.status = 'COMPLETED'
                           AND (CAST(:modality AS text) IS NULL
                                OR s.modality = CAST(:modality AS text))
                           AND (CAST(:campusCsv AS text) = ''
                                OR a.campus_id = ANY (string_to_array(CAST(:campusCsv AS text), ',')::uuid[]))
                           AND (CAST(:fromTs AS timestamptz) IS NULL
                                OR s.completed_at >= CAST(:fromTs AS timestamptz))
                           AND (CAST(:toTs AS timestamptz) IS NULL
                                OR s.completed_at < CAST(:toTs AS timestamptz))
                           AND (CAST(:state AS text) IS NULL
                                OR (CAST(:state AS text) = 'UNCLAIMED' AND t.id IS NULL
                                    AND NOT EXISTS (SELECT 1 FROM tech_svc.pacs_reading_task d
                                                     WHERE d.study_id = s.id
                                                       AND d.status = 'COMPLETED'))
                                OR (CAST(:state AS text) = 'CLAIMED' AND t.id IS NOT NULL)
                                OR (CAST(:state AS text) = 'MINE'
                                    AND t.assignee = CAST(:assignee AS text))
                                OR (CAST(:state AS text) = 'DONE'
                                    AND EXISTS (SELECT 1 FROM tech_svc.pacs_reading_task d
                                                 WHERE d.study_id = s.id
                                                   AND d.status = 'COMPLETED')))
                         ORDER BY CASE a.priority WHEN 'STAT' THEN 0
                                                  WHEN 'URGENT' THEN 1 ELSE 2 END,
                                  s.completed_at
                         LIMIT :limit
                        """)
                .param("modality", modality).param("state", state).param("assignee", assignee)
                .param("fromTs", fromTs).param("toTs", toTs)
                .param("campusCsv", campusCsv == null ? "" : campusCsv).param("limit", limit)
                .query(READING_QUEUE_MAPPER).list();
    }

    public Optional<ReadingTask> findReadingTask(UUID id) {
        return jdbc.sql("""
                        SELECT id, study_id, assignee, status, claimed_at, released_at,
                               completed_at, note, version
                          FROM tech_svc.pacs_reading_task WHERE id = ?
                        """).param(id).query(READING_TASK_MAPPER).optional();
    }

    /** 当前持有的阅片任务（CLAIMED）；释放/完成后返回空。 */
    public Optional<ReadingTask> findActiveReadingTask(UUID studyId) {
        return jdbc.sql("""
                        SELECT id, study_id, assignee, status, claimed_at, released_at,
                               completed_at, note, version
                          FROM tech_svc.pacs_reading_task
                         WHERE study_id = ? AND status = 'CLAIMED'
                        """).param(studyId).query(READING_TASK_MAPPER).optional();
    }

    public void insertReadingTask(ReadingTask t) {
        jdbc.sql("""
                        INSERT INTO tech_svc.pacs_reading_task
                            (id, study_id, assignee, status, claimed_at, note, version)
                        VALUES (:id, :studyId, :assignee, :status, now(), CAST(:note AS text), 0)
                        """)
                .param("id", t.id()).param("studyId", t.studyId()).param("assignee", t.assignee())
                .param("status", t.status()).param("note", t.note()).update();
    }

    /**
     * 关闭阅片任务（释放/完成）：CAS 校验"期望状态 + 版本号 + 持有人"。
     *
     * <p>持有人条件写在 SQL 里而不是只在服务层判断——即便服务层被绕过，
     * 也无法由他人代释放/代完成。
     */
    public boolean closeReadingTask(UUID id, String expectedStatus, String next, String actor,
                                    String note, long expectedVersion) {
        return jdbc.sql("""
                        UPDATE tech_svc.pacs_reading_task
                           SET status = :next,
                               released_at = CASE WHEN :next = 'RELEASED' THEN now()
                                                  ELSE released_at END,
                               completed_at = CASE WHEN :next = 'COMPLETED' THEN now()
                                                   ELSE completed_at END,
                               note = COALESCE(CAST(:note AS text), note),
                               version = version + 1,
                               updated_at = now()
                         WHERE id = :id AND status = :expected AND version = :version
                           AND assignee = :actor
                        """)
                .param("next", next).param("id", id).param("expected", expectedStatus)
                .param("version", expectedVersion).param("actor", actor).param("note", note)
                .update() > 0;
    }

    public List<PacsSeries> listPacsSeries(UUID studyId) {
        return jdbc.sql("""
                        SELECT id, study_id, series_instance_uid, series_number, modality,
                               body_part, description, instance_count, synced_at
                          FROM tech_svc.pacs_series
                         WHERE study_id = ?
                         ORDER BY COALESCE(series_number, 2147483647), series_instance_uid
                        """).param(studyId).query(PACS_SERIES_MAPPER).list();
    }

    /** 按检查 + 序列 UID 定位缓存序列（影像分页前校验序列确实属于该检查）。 */
    public Optional<PacsSeries> findPacsSeries(UUID studyId, String seriesInstanceUid) {
        return jdbc.sql("""
                        SELECT id, study_id, series_instance_uid, series_number, modality,
                               body_part, description, instance_count, synced_at
                          FROM tech_svc.pacs_series
                         WHERE study_id = ? AND series_instance_uid = ?
                        """)
                .param(studyId).param(seriesInstanceUid)
                .query(PACS_SERIES_MAPPER).optional();
    }

    /** 删除已从 PACS 消失的序列（按主键，保留仍在的序列 ID 与同步历史）。 */
    public void deletePacsSeriesById(UUID id) {
        jdbc.sql("DELETE FROM tech_svc.pacs_series WHERE id = ?").param(id).update();
    }

    public void insertPacsSeries(PacsSeries s) {
        jdbc.sql("""
                        INSERT INTO tech_svc.pacs_series
                            (id, study_id, series_instance_uid, series_number, modality,
                             body_part, description, instance_count, synced_at)
                        VALUES (:id, :studyId, :uid, :number, :modality, CAST(:bodyPart AS text),
                                CAST(:description AS text), :count, now())
                        ON CONFLICT (series_instance_uid) DO UPDATE
                           SET series_number = EXCLUDED.series_number,
                               modality = EXCLUDED.modality,
                               body_part = EXCLUDED.body_part,
                               description = EXCLUDED.description,
                               instance_count = EXCLUDED.instance_count,
                               synced_at = now(),
                               updated_at = now()
                         WHERE tech_svc.pacs_series.study_id = EXCLUDED.study_id
                        """)
                .param("id", s.id()).param("studyId", s.studyId())
                .param("uid", s.seriesInstanceUid()).param("number", s.seriesNumber())
                .param("modality", s.modality()).param("bodyPart", s.bodyPart())
                .param("description", s.description()).param("count", s.instanceCount()).update();
    }

    public void insertPacsAccess(PacsAccessLog log) {
        jdbc.sql("""
                        INSERT INTO tech_svc.pacs_access_log
                            (id, study_id, study_instance_uid, accession_number, actor, action,
                             series_instance_uid, sop_instance_uid, detail)
                        VALUES (:id, :studyId, :studyUid, :accession, :actor, :action,
                                CAST(:seriesUid AS text), CAST(:sopUid AS text),
                                CAST(:detail AS text))
                        """)
                .param("id", log.id()).param("studyId", log.studyId())
                .param("studyUid", log.studyInstanceUid()).param("accession", log.accessionNumber())
                .param("actor", log.actor()).param("action", log.action())
                .param("seriesUid", log.seriesInstanceUid()).param("sopUid", log.sopInstanceUid())
                .param("detail", log.detail()).update();
    }

    public List<PacsAccessLog> listPacsAccess(UUID studyId, int limit) {
        return jdbc.sql("""
                        SELECT id, study_id, study_instance_uid, accession_number, actor, action,
                               series_instance_uid, sop_instance_uid, detail, created_at
                          FROM tech_svc.pacs_access_log
                         WHERE study_id = :studyId
                         ORDER BY created_at DESC, id
                         LIMIT :limit
                        """)
                .param("studyId", studyId).param("limit", limit)
                .query(PACS_ACCESS_MAPPER).list();
    }

    // ---------------------------------------------------------------- 设备与质控（G35）

    private static final RowMapper<Device> DEVICE_MAPPER = (rs, i) -> new Device(
            rs.getObject("id", UUID.class), rs.getString("device_no"), rs.getString("name"),
            rs.getString("modality"), rs.getObject("dept_id", UUID.class),
            rs.getString("location"), rs.getString("manufacturer"), rs.getString("model"),
            rs.getString("serial_no"), rs.getString("status"),
            rs.getDate("installed_at") == null ? null : rs.getDate("installed_at").toLocalDate(),
            rs.getLong("version"), rs.getTimestamp("created_at").toInstant());

    private static final RowMapper<DeviceQc> DEVICE_QC_MAPPER = (rs, i) -> new DeviceQc(
            rs.getObject("id", UUID.class), rs.getObject("device_id", UUID.class),
            rs.getString("device_no"), rs.getString("device_name"), rs.getString("qc_type"),
            rs.getString("result"), rs.getString("item_name"), rs.getString("expected_value"),
            rs.getString("actual_value"), rs.getString("unit"), rs.getString("performed_by"),
            rs.getTimestamp("performed_at").toInstant(), rs.getString("note"),
            rs.getTimestamp("created_at").toInstant());

    private static final RowMapper<ReportTemplate> TEMPLATE_MAPPER = (rs, i) -> new ReportTemplate(
            rs.getObject("id", UUID.class), rs.getString("code"), rs.getString("name"),
            rs.getString("modality"), rs.getString("schema_json"), rs.getString("status"),
            rs.getString("created_by"), rs.getTimestamp("created_at").toInstant(),
            rs.getString("published_by"),
            rs.getTimestamp("published_at") == null ? null : rs.getTimestamp("published_at").toInstant(),
            rs.getLong("version"));

    public String nextDeviceNo() {
        return nextNo("tech_svc.device_no_seq", "DEV");
    }

    public void insertDevice(Device d) {
        jdbc.sql("""
                        INSERT INTO tech_svc.device
                            (id, device_no, name, modality, dept_id, location, manufacturer,
                             model, serial_no, status, installed_at)
                        VALUES (:id, :no, :name, :modality, CAST(:deptId AS uuid),
                                CAST(:location AS text), CAST(:manufacturer AS text),
                                CAST(:model AS text), CAST(:serialNo AS text), :status,
                                CAST(:installedAt AS date))
                        """)
                .param("id", d.id()).param("no", d.deviceNo()).param("name", d.name())
                .param("modality", d.modality()).param("deptId", d.deptId())
                .param("location", d.location()).param("manufacturer", d.manufacturer())
                .param("model", d.model()).param("serialNo", d.serialNo())
                .param("status", d.status()).param("installedAt", d.installedAt()).update();
    }

    public Optional<Device> findDevice(UUID id) {
        return jdbc.sql("""
                        SELECT id, device_no, name, modality, dept_id, location, manufacturer,
                               model, serial_no, status, installed_at, version, created_at
                          FROM tech_svc.device WHERE id = ?
                        """).param(id).query(DEVICE_MAPPER).optional();
    }

    public Optional<Device> lockDevice(UUID id) {
        return jdbc.sql("""
                        SELECT id, device_no, name, modality, dept_id, location, manufacturer,
                               model, serial_no, status, installed_at, version, created_at
                          FROM tech_svc.device WHERE id = ? FOR UPDATE
                        """).param(id).query(DEVICE_MAPPER).optional();
    }

    public List<Device> listDevices(String keyword, String modality, String status, int limit) {
        String pattern = keyword == null || keyword.isBlank() ? null : "%" + keyword.trim() + "%";
        return jdbc.sql("""
                        SELECT id, device_no, name, modality, dept_id, location, manufacturer,
                               model, serial_no, status, installed_at, version, created_at
                          FROM tech_svc.device
                         WHERE (CAST(:pattern AS text) IS NULL OR name ILIKE CAST(:pattern AS text)
                                OR device_no ILIKE CAST(:pattern AS text)
                                OR COALESCE(serial_no, '') ILIKE CAST(:pattern AS text))
                           AND (CAST(:modality AS text) IS NULL
                                OR modality = CAST(:modality AS text))
                           AND (CAST(:status AS text) IS NULL
                                OR status = CAST(:status AS text))
                         ORDER BY modality, device_no
                         LIMIT :limit
                        """)
                .param("pattern", pattern).param("modality", modality).param("status", status)
                .param("limit", limit).query(DEVICE_MAPPER).list();
    }

    public boolean updateDeviceStatus(UUID id, String status, long expectedVersion) {
        return jdbc.sql("""
                        UPDATE tech_svc.device
                           SET status = :status, version = version + 1, updated_at = now()
                         WHERE id = :id AND version = :version
                        """)
                .param("status", status).param("id", id).param("version", expectedVersion)
                .update() > 0;
    }

    public void insertDeviceQc(DeviceQc qc) {
        jdbc.sql("""
                        INSERT INTO tech_svc.device_qc
                            (id, device_id, qc_type, result, item_name, expected_value,
                             actual_value, unit, performed_by, performed_at, note)
                        VALUES (:id, :deviceId, :qcType, :result, :itemName,
                                CAST(:expected AS text), CAST(:actual AS text), CAST(:unit AS text),
                                :performedBy,
                                COALESCE(CAST(:performedAt AS timestamptz), now()),
                                CAST(:note AS text))
                        """)
                .param("id", qc.id()).param("deviceId", qc.deviceId())
                .param("qcType", qc.qcType()).param("result", qc.result())
                .param("itemName", qc.itemName()).param("expected", qc.expectedValue())
                .param("actual", qc.actualValue()).param("unit", qc.unit())
                .param("performedBy", qc.performedBy())
                .param("performedAt", qc.performedAt() == null ? null
                        : qc.performedAt().atZone(BusinessTime.zone()).toOffsetDateTime())
                .param("note", qc.note()).update();
    }

    public List<DeviceQc> listDeviceQc(UUID deviceId, int limit) {
        return jdbc.sql("""
                        SELECT q.id, q.device_id, d.device_no, d.name AS device_name, q.qc_type,
                               q.result, q.item_name, q.expected_value, q.actual_value, q.unit,
                               q.performed_by, q.performed_at, q.note, q.created_at
                          FROM tech_svc.device_qc q
                          JOIN tech_svc.device d ON d.id = q.device_id
                         WHERE q.device_id = :deviceId
                         ORDER BY q.performed_at DESC, q.created_at DESC
                         LIMIT :limit
                        """)
                .param("deviceId", deviceId).param("limit", limit)
                .query(DEVICE_QC_MAPPER).list();
    }

    /** 最近一次质控结果（无记录返回空），用于设备列表 FAIL 提示。 */
    public Optional<String> latestDeviceQcResult(UUID deviceId) {
        return jdbc.sql("""
                        SELECT result FROM tech_svc.device_qc
                         WHERE device_id = ?
                         ORDER BY performed_at DESC, created_at DESC
                         LIMIT 1
                        """).param(deviceId).query(String.class).optional();
    }

    public List<DeviceStatusCount> deviceStatusCounts() {
        return jdbc.sql("""
                        SELECT status, count(*) AS cnt FROM tech_svc.device GROUP BY status
                        """)
                .query((rs, i) -> new DeviceStatusCount(rs.getString("status"),
                        rs.getInt("cnt"))).list();
    }

    public int countDeviceQcFailSince(OffsetDateTime from) {
        Integer count = jdbc.sql("""
                        SELECT count(*) FROM tech_svc.device_qc
                         WHERE result = 'FAIL' AND performed_at >= :fromTs
                        """).param("fromTs", from).query(Integer.class).single();
        return count == null ? 0 : count;
    }

    // ---------------------------------------------------------------- 结构化报告模板（G35）

    public void insertReportTemplate(ReportTemplate t) {
        jdbc.sql("""
                        INSERT INTO tech_svc.report_template
                            (id, code, name, modality, schema_json, status, created_by)
                        VALUES (:id, :code, :name, :modality, CAST(:schema AS jsonb), :status,
                                :createdBy)
                        """)
                .param("id", t.id()).param("code", t.code()).param("name", t.name())
                .param("modality", t.modality()).param("schema", t.schemaJson())
                .param("status", t.status()).param("createdBy", t.createdBy()).update();
    }

    public Optional<ReportTemplate> findReportTemplate(UUID id) {
        return jdbc.sql("""
                        SELECT id, code, name, modality, schema_json, status, created_by,
                               created_at, published_by, published_at, version
                          FROM tech_svc.report_template WHERE id = ?
                        """).param(id).query(TEMPLATE_MAPPER).optional();
    }

    public Optional<ReportTemplate> findReportTemplateByCode(String code) {
        return jdbc.sql("""
                        SELECT id, code, name, modality, schema_json, status, created_by,
                               created_at, published_by, published_at, version
                          FROM tech_svc.report_template WHERE code = ?
                        """).param(code).query(TEMPLATE_MAPPER).optional();
    }

    public Optional<ReportTemplate> lockReportTemplate(UUID id) {
        return jdbc.sql("""
                        SELECT id, code, name, modality, schema_json, status, created_by,
                               created_at, published_by, published_at, version
                          FROM tech_svc.report_template WHERE id = ? FOR UPDATE
                        """).param(id).query(TEMPLATE_MAPPER).optional();
    }

    public List<ReportTemplate> listReportTemplates(String status, String modality, int limit) {
        return jdbc.sql("""
                        SELECT id, code, name, modality, schema_json, status, created_by,
                               created_at, published_by, published_at, version
                          FROM tech_svc.report_template
                         WHERE (CAST(:status AS text) IS NULL OR status = CAST(:status AS text))
                           AND (CAST(:modality AS text) IS NULL
                                OR modality = CAST(:modality AS text))
                         ORDER BY modality, code
                         LIMIT :limit
                        """)
                .param("status", status).param("modality", modality).param("limit", limit)
                .query(TEMPLATE_MAPPER).list();
    }

    public boolean publishReportTemplate(UUID id, String actor, long expectedVersion) {
        return jdbc.sql("""
                        UPDATE tech_svc.report_template
                           SET status = 'PUBLISHED', published_by = :actor, published_at = now(),
                               version = version + 1
                         WHERE id = :id AND status = 'DRAFT' AND version = :version
                        """)
                .param("actor", actor).param("id", id).param("version", expectedVersion)
                .update() > 0;
    }

    public boolean disableReportTemplate(UUID id, long expectedVersion) {
        return jdbc.sql("""
                        UPDATE tech_svc.report_template
                           SET status = 'DISABLED', version = version + 1
                         WHERE id = :id AND status = 'PUBLISHED' AND version = :version
                        """)
                .param("id", id).param("version", expectedVersion).update() > 0;
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
