package com.medassist.clinical.repo;

import com.medassist.clinical.domain.ClinicalModel.Admission;
import com.medassist.clinical.domain.ClinicalModel.AdmissionPeriodSummary;
import com.medassist.clinical.domain.ClinicalModel.AnesthesiaRecord;
import com.medassist.clinical.domain.ClinicalModel.AnesthesiaVital;
import com.medassist.clinical.domain.ClinicalModel.Bed;
import com.medassist.clinical.domain.ClinicalModel.Charge;
import com.medassist.clinical.domain.ClinicalModel.ChargeItemDef;
import com.medassist.clinical.domain.ClinicalModel.ChargeLine;
import com.medassist.clinical.domain.ClinicalModel.ChargeReceipt;
import com.medassist.clinical.domain.ClinicalModel.Consultation;
import com.medassist.clinical.domain.ClinicalModel.DailyAdmissionTotal;
import com.medassist.clinical.domain.ClinicalModel.DailyDispenseRequest;
import com.medassist.clinical.domain.ClinicalModel.DailyInHospitalTotal;
import com.medassist.clinical.domain.ClinicalModel.DailyPaidTotal;
import com.medassist.clinical.domain.ClinicalModel.Dept;
import com.medassist.clinical.domain.ClinicalModel.DeptPaidTotal;
import com.medassist.clinical.domain.ClinicalModel.Deposit;
import com.medassist.clinical.domain.ClinicalModel.DiagnosisDef;
import com.medassist.clinical.domain.ClinicalModel.IntraopNursing;
import com.medassist.clinical.domain.ClinicalModel.MethodPaidTotal;
import com.medassist.clinical.domain.ClinicalModel.NursingExecution;
import com.medassist.clinical.domain.ClinicalModel.NursingExecutionCorrection;
import com.medassist.clinical.domain.ClinicalModel.OperatingRoom;
import com.medassist.clinical.domain.ClinicalModel.OperationCatalog;
import com.medassist.clinical.domain.ClinicalModel.OrderRefund;
import com.medassist.clinical.domain.ClinicalModel.NursingCoverageRow;
import com.medassist.clinical.domain.ClinicalModel.NursingSchedule;
import com.medassist.clinical.domain.ClinicalModel.NursingShift;
import com.medassist.clinical.domain.ClinicalModel.NursingWorkloadRow;
import com.medassist.clinical.domain.ClinicalModel.OutpatientPaidSummary;
import com.medassist.clinical.domain.ClinicalModel.PathwayInstance;
import com.medassist.clinical.domain.ClinicalModel.PathwayNode;
import com.medassist.clinical.domain.ClinicalModel.PathwayTask;
import com.medassist.clinical.domain.ClinicalModel.PathwayTemplate;
import com.medassist.clinical.domain.ClinicalModel.PharmacyChargeLink;
import com.medassist.clinical.domain.ClinicalModel.PreopDiscussion;
import com.medassist.clinical.domain.ClinicalModel.PreopVisit;
import com.medassist.clinical.domain.ClinicalModel.ReceiptPush;
import com.medassist.clinical.domain.ClinicalModel.ReceiptPushLedger;
import com.medassist.clinical.domain.ClinicalModel.ReceiptPushSummary;
import com.medassist.clinical.domain.ClinicalModel.ReportDelivery;
import com.medassist.clinical.domain.ClinicalModel.ReportRecipientGroup;
import com.medassist.clinical.domain.ClinicalModel.ReportRecipientGroupMember;
import com.medassist.clinical.domain.ClinicalModel.ReportSnapshot;
import com.medassist.clinical.domain.ClinicalModel.ReportSubscription;
import com.medassist.clinical.domain.ClinicalModel.RoomDayUsage;
import com.medassist.clinical.domain.ClinicalModel.RoomDayUsageRow;
import com.medassist.clinical.domain.ClinicalModel.Slot;
import com.medassist.clinical.domain.ClinicalModel.SurgeryBoardRow;
import com.medassist.clinical.domain.ClinicalModel.SurgeryCase;
import com.medassist.clinical.domain.ClinicalModel.SurgeryChargeRule;
import com.medassist.clinical.domain.ClinicalModel.SurgeryConsent;
import com.medassist.clinical.domain.ClinicalModel.SurgeryLevel;
import com.medassist.clinical.domain.ClinicalModel.SurgerySchedule;
import com.medassist.clinical.domain.ClinicalModel.TransferTask;
import com.medassist.clinical.domain.ClinicalModel.Visit;
import com.medassist.clinical.domain.ClinicalModel.VisitDiagnosis;
import com.medassist.clinical.domain.ClinicalModel.VisitOrder;
import com.medassist.clinical.domain.ClinicalModel.VisitTransfer;
import com.medassist.clinical.domain.ClinicalModel.Ward;
import com.medassist.clinical.domain.ClinicalModel.WardAdmissionRow;
import com.medassist.clinical.domain.ClinicalModel.WardMedReceipt;
import com.medassist.clinical.domain.ClinicalModel.WardMedReturn;
import com.medassist.clinical.domain.ClinicalModel.WardStockBalance;
import com.medassist.clinical.domain.ClinicalModel.WardStockTxn;
import com.medassist.common.time.BusinessTime;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * 临床核心仓储（JdbcClient 显式 SQL）。
 *
 * <p>关键并发语义：
 * <ul>
 *   <li>{@link #lockSlot} / {@link #lockVisit} / {@link #lockOpenCharge} 使用
 *       {@code SELECT ... FOR UPDATE}，必须在事务内调用；</li>
 *   <li>{@link #occupySlot} 用 {@code booked < total} 作为数据库级守卫，
 *       即使应用层判断被绕过也不会超卖；</li>
 *   <li>{@link #insertChargeItem} 依赖 {@code source_key} 唯一索引实现"同一医嘱/同一天只计一次"。</li>
 * </ul>
 */
@Repository
public class ClinicalRepository {

    private static final RowMapper<Dept> DEPT_MAPPER = (rs, i) -> new Dept(
            rs.getObject("id", UUID.class), rs.getString("dept_code"), rs.getString("name"),
            rs.getString("type"), rs.getBoolean("enabled"));

    private static final RowMapper<ChargeItemDef> ITEM_MAPPER = (rs, i) -> new ChargeItemDef(
            rs.getObject("id", UUID.class), rs.getString("item_code"), rs.getString("name"),
            rs.getString("category"), rs.getString("unit"), rs.getBigDecimal("price"),
            rs.getBoolean("enabled"));

    private static final RowMapper<Slot> SLOT_MAPPER = (rs, i) -> new Slot(
            rs.getObject("id", UUID.class), rs.getObject("dept_id", UUID.class),
            rs.getString("doctor_id"), rs.getDate("work_date").toLocalDate(),
            rs.getString("period"), rs.getInt("total"), rs.getInt("booked"), rs.getLong("version"));

    private static final RowMapper<Visit> VISIT_MAPPER = (rs, i) -> new Visit(
            rs.getObject("id", UUID.class), rs.getString("visit_no"),
            rs.getObject("patient_id", UUID.class), rs.getString("patient_no"),
            rs.getObject("dept_id", UUID.class), rs.getString("doctor_id"),
            rs.getObject("slot_id", UUID.class), rs.getString("visit_type"),
            rs.getString("status"), rs.getDate("visit_date").toLocalDate(), rs.getLong("version"));

    private static final RowMapper<VisitOrder> ORDER_MAPPER = (rs, i) -> new VisitOrder(
            rs.getObject("id", UUID.class), rs.getString("order_no"),
            rs.getObject("visit_id", UUID.class), rs.getString("order_type"),
            rs.getString("item_code"), rs.getString("item_name"), rs.getString("dose"),
            rs.getString("frequency"), rs.getInt("frequency_times"), rs.getInt("quantity"),
            rs.getString("unit"),
            rs.getBigDecimal("unit_price"), rs.getBigDecimal("amount"),
            rs.getString("status"), rs.getString("ordered_by"), rs.getLong("version"),
            rs.getString("order_mode"),
            rs.getDate("start_date") == null ? null : rs.getDate("start_date").toLocalDate(),
            rs.getDate("stop_date") == null ? null : rs.getDate("stop_date").toLocalDate(),
            rs.getString("transfer_status"), rs.getString("external_type"),
            rs.getObject("external_id", UUID.class), rs.getString("external_no"),
            rs.getString("transfer_error"));

    private static final RowMapper<DiagnosisDef> DIAG_DEF_MAPPER = (rs, i) -> new DiagnosisDef(
            rs.getObject("id", UUID.class), rs.getString("code"), rs.getString("name"),
            rs.getString("category"), rs.getBoolean("enabled"), rs.getLong("version"));

    private static final RowMapper<VisitDiagnosis> VISIT_DIAG_MAPPER = (rs, i) -> new VisitDiagnosis(
            rs.getObject("id", UUID.class), rs.getObject("visit_id", UUID.class),
            rs.getString("code"), rs.getString("name"), rs.getBoolean("primary_flag"),
            rs.getInt("sort_no"), rs.getString("diagnosed_by"),
            rs.getTimestamp("diagnosed_at").toInstant(), rs.getLong("version"));

    private static final RowMapper<Charge> CHARGE_MAPPER = (rs, i) -> new Charge(
            rs.getObject("id", UUID.class), rs.getString("charge_no"),
            rs.getObject("visit_id", UUID.class), rs.getBigDecimal("total_amount"),
            rs.getString("status"), rs.getString("payment_method"),
            rs.getTimestamp("paid_at") == null ? null : rs.getTimestamp("paid_at").toInstant(),
            rs.getLong("version"));

    private final JdbcClient jdbc;

    public ClinicalRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    // ---------------------------------------------------------------- 单号

    public String nextVisitNo() {
        return nextNo("clinical_svc.visit_no_seq", "V");
    }

    public String nextOrderNo() {
        return nextNo("clinical_svc.order_no_seq", "O");
    }

    public String nextChargeNo() {
        return nextNo("clinical_svc.charge_no_seq", "C");
    }

    public String nextReceiptNo() {
        return nextNo("clinical_svc.charge_receipt_no_seq", "RP");
    }

    private String nextNo(String sequence, String prefix) {
        // 序列名走绑定参数（CAST 为 regclass），避免把标识符拼进 SQL 文本
        Long seq = jdbc.sql("SELECT nextval(CAST(:seq AS regclass))")
                .param("seq", sequence).query(Long.class).single();
        return prefix + BusinessTime.today().format(DateTimeFormatter.BASIC_ISO_DATE)
                + String.format("%06d", seq);
    }

    // ---------------------------------------------------------------- 主数据

    public Optional<Dept> findDept(UUID id) {
        return jdbc.sql("SELECT id, dept_code, name, type, enabled FROM clinical_svc.md_dept WHERE id = ?")
                .param(id).query(DEPT_MAPPER).optional();
    }

    public Optional<Dept> findDeptByCode(String deptCode) {
        return jdbc.sql("SELECT id, dept_code, name, type, enabled FROM clinical_svc.md_dept WHERE dept_code = ?")
                .param(deptCode).query(DEPT_MAPPER).optional();
    }

    /** 启用科室列表（控制台下拉用）。 */
    public List<Dept> listDepartments() {
        return jdbc.sql("""
                        SELECT id, dept_code, name, type, enabled FROM clinical_svc.md_dept
                         WHERE enabled ORDER BY dept_code
                        """).query(DEPT_MAPPER).list();
    }

    /**
     * 收费项目检索（医嘱开立选项目用）。
     *
     * <p>只返回启用项，且只允许按编码/名称模糊匹配；价格字段用于前端展示，
     * 实际计价仍由服务端按 {@code item_code} 重新读取主数据，前端传价格无效。
     */
    public List<ChargeItemDef> searchChargeItems(String keyword, String category, int limit) {
        String like = (keyword == null || keyword.isBlank()) ? null : "%" + keyword.trim() + "%";
        String cat = (category == null || category.isBlank()) ? null : category.trim().toUpperCase();
        return jdbc.sql("""
                        SELECT id, item_code, name, category, unit, price, enabled
                          FROM clinical_svc.md_charge_item
                         WHERE enabled
                           AND (CAST(:cat AS text) IS NULL OR category = CAST(:cat AS text))
                           AND (CAST(:like AS text) IS NULL
                                OR item_code ILIKE CAST(:like AS text)
                                OR name ILIKE CAST(:like AS text))
                         ORDER BY item_code
                         LIMIT :limit
                        """)
                .param("cat", cat).param("like", like).param("limit", Math.max(1, Math.min(limit, 200)))
                .query(ITEM_MAPPER).list();
    }

    public Optional<ChargeItemDef> findChargeItem(String itemCode) {
        return jdbc.sql("""
                        SELECT id, item_code, name, category, unit, price, enabled
                          FROM clinical_svc.md_charge_item WHERE item_code = ?
                        """)
                .param(itemCode).query(ITEM_MAPPER).optional();
    }

    // ---------------------------------------------------------------- 号源

    public Optional<Slot> findSlot(UUID id) {
        return jdbc.sql("""
                        SELECT id, dept_id, doctor_id, work_date, period, total, booked, version
                          FROM clinical_svc.reg_slot WHERE id = ?
                        """)
                .param(id).query(SLOT_MAPPER).optional();
    }

    /** 加行锁读取号源；必须在事务内调用。 */
    public Optional<Slot> lockSlot(UUID id) {
        return jdbc.sql("""
                        SELECT id, dept_id, doctor_id, work_date, period, total, booked, version
                          FROM clinical_svc.reg_slot WHERE id = ? FOR UPDATE
                        """)
                .param(id).query(SLOT_MAPPER).optional();
    }

    /** 占号（CAS）：booked+1，且必须 booked &lt; total、version 未变。 */
    public boolean occupySlot(UUID slotId, long expectedVersion) {
        return jdbc.sql("""
                        UPDATE clinical_svc.reg_slot
                           SET booked = booked + 1, version = version + 1, updated_at = now()
                         WHERE id = :id AND version = :version AND booked < total
                        """)
                .param("id", slotId).param("version", expectedVersion).update() > 0;
    }

    /** 取消挂号时释放号源（CAS：booked &gt; 0 才减，避免负数）。 */
    public boolean releaseSlot(UUID slotId) {
        return jdbc.sql("""
                        UPDATE clinical_svc.reg_slot
                           SET booked = booked - 1, version = version + 1, updated_at = now()
                         WHERE id = :id AND booked > 0
                        """)
                .param("id", slotId).update() > 0;
    }

    public void insertSlot(Slot slot) {
        jdbc.sql("""
                        INSERT INTO clinical_svc.reg_slot
                            (id, dept_id, doctor_id, work_date, period, total, booked, version)
                        VALUES (:id, :deptId, :doctorId, :workDate, :period, :total, 0, 0)
                        """)
                .param("id", slot.id()).param("deptId", slot.deptId())
                .param("doctorId", slot.doctorId()).param("workDate", slot.workDate())
                .param("period", slot.period()).param("total", slot.total()).update();
    }

    // ---------------------------------------------------------------- 就诊

    public Optional<Visit> findActiveVisit(UUID patientId, UUID deptId, LocalDate visitDate) {
        return jdbc.sql("""
                        SELECT id, visit_no, patient_id, patient_no, dept_id, doctor_id, slot_id,
                               visit_type, status, visit_date, version
                          FROM clinical_svc.visit
                         WHERE patient_id = :patientId AND dept_id = :deptId
                           AND visit_date = :visitDate AND status IN ('REGISTERED', 'IN_TREATMENT')
                        """)
                .param("patientId", patientId).param("deptId", deptId)
                .param("visitDate", visitDate).query(VISIT_MAPPER).optional();
    }

    public Optional<Visit> findVisit(UUID id) {
        return jdbc.sql("""
                        SELECT id, visit_no, patient_id, patient_no, dept_id, doctor_id, slot_id,
                               visit_type, status, visit_date, version
                          FROM clinical_svc.visit WHERE id = ?
                        """)
                .param(id).query(VISIT_MAPPER).optional();
    }

    /**
     * 按患者列出最近就诊（控制台"患者上下文"用）。
     *
     * <p>只读查询，按就诊日期倒序；不返回已取消记录（CANCELLED 对临床无意义，
     * 但退号审计仍可在就诊详情/审计流里追溯）。
     */
    public List<Visit> listVisitsByPatient(UUID patientId, int limit) {
        return jdbc.sql("""
                        SELECT id, visit_no, patient_id, patient_no, dept_id, doctor_id, slot_id,
                               visit_type, status, visit_date, version
                          FROM clinical_svc.visit
                         WHERE patient_id = :patientId AND status <> 'CANCELLED'
                         ORDER BY visit_date DESC, registered_at DESC
                         LIMIT :limit
                        """)
                .param("patientId", patientId).param("limit", Math.max(1, Math.min(limit, 100)))
                .query(VISIT_MAPPER).list();
    }

    /** 按科室 + 日期列号源（挂号工作台选号用；空日期不查，避免全表扫描）。 */
    public List<Slot> listSlots(UUID deptId, LocalDate workDate) {
        return jdbc.sql("""
                        SELECT id, dept_id, doctor_id, work_date, period, total, booked, version
                          FROM clinical_svc.reg_slot
                         WHERE dept_id = :deptId AND work_date = :workDate
                         ORDER BY period, doctor_id
                        """)
                .param("deptId", deptId).param("workDate", workDate)
                .query(SLOT_MAPPER).list();
    }

    /** 当日该科室的就诊列表（挂号工作台与日结核对用），按挂号先后排序。 */
    public List<Visit> listVisitsByDeptAndDate(UUID deptId, LocalDate visitDate) {
        return jdbc.sql("""
                        SELECT id, visit_no, patient_id, patient_no, dept_id, doctor_id, slot_id,
                               visit_type, status, visit_date, version
                          FROM clinical_svc.visit
                         WHERE dept_id = :deptId AND visit_date = :visitDate
                         ORDER BY registered_at, visit_no
                        """)
                .param("deptId", deptId).param("visitDate", visitDate)
                .query(VISIT_MAPPER).list();
    }

    /** 加行锁读取就诊；必须在事务内调用。 */
    public Optional<Visit> lockVisit(UUID id) {
        return jdbc.sql("""
                        SELECT id, visit_no, patient_id, patient_no, dept_id, doctor_id, slot_id,
                               visit_type, status, visit_date, version
                          FROM clinical_svc.visit WHERE id = ? FOR UPDATE
                        """)
                .param(id).query(VISIT_MAPPER).optional();
    }

    public void insertVisit(Visit v) {
        jdbc.sql("""
                        INSERT INTO clinical_svc.visit
                            (id, visit_no, patient_id, patient_no, dept_id, doctor_id, slot_id,
                             visit_type, status, visit_date, version)
                        VALUES (:id, :visitNo, :patientId, :patientNo, :deptId, :doctorId, :slotId,
                                :visitType, :status, :visitDate, 0)
                        """)
                .param("id", v.id()).param("visitNo", v.visitNo())
                .param("patientId", v.patientId()).param("patientNo", v.patientNo())
                .param("deptId", v.deptId()).param("doctorId", v.doctorId())
                .param("slotId", v.slotId()).param("visitType", v.visitType())
                .param("status", v.status()).param("visitDate", v.visitDate()).update();
    }

    /** 状态迁移（CAS）：只允许从 expectedStatus 迁移，防止并发下"取消后又被开始接诊"。 */
    public boolean updateVisitStatus(UUID id, String expectedStatus, String newStatus, long expectedVersion) {
        return jdbc.sql("""
                        UPDATE clinical_svc.visit
                           SET status = :newStatus, version = version + 1, updated_at = now(),
                               started_at  = CASE WHEN :newStatus = 'IN_TREATMENT' THEN now() ELSE started_at END,
                               finished_at = CASE WHEN :newStatus IN ('FINISHED', 'CANCELLED') THEN now() ELSE finished_at END
                         WHERE id = :id AND status = :expectedStatus AND version = :version
                        """)
                .param("newStatus", newStatus).param("id", id)
                .param("expectedStatus", expectedStatus).param("version", expectedVersion)
                .update() > 0;
    }

    /**
     * 变更主诊科室（CAS）。
     *
     * <p>只改 {@code dept_id}：医嘱、诊断、收费都挂在 visit 上，不随科室变更拆分；
     * 原科室的访问权由这次更新立即收回，历史归属留在 {@code visit_transfer}。
     */
    public boolean updateVisitDepartment(UUID id, UUID deptId, long expectedVersion) {
        return jdbc.sql("""
                        UPDATE clinical_svc.visit
                           SET dept_id = :deptId, version = version + 1, updated_at = now()
                         WHERE id = :id AND version = :version
                        """)
                .param("deptId", deptId).param("id", id).param("version", expectedVersion)
                .update() > 0;
    }

    // ---------------------------------------------------------------- 医嘱

    public void insertOrder(VisitOrder o) {
        jdbc.sql("""
                        INSERT INTO clinical_svc.visit_order
                            (id, order_no, visit_id, order_type, item_code, item_name, dose, frequency,
                             frequency_times, quantity, unit, unit_price, amount, status, ordered_by,
                             version, order_mode, start_date, transfer_status)
                        VALUES (:id, :orderNo, :visitId, :orderType, :itemCode, :itemName, :dose,
                                :frequency, :frequencyTimes, :quantity, :unit, :unitPrice, :amount,
                                :status, :orderedBy, 0, :orderMode, :startDate, :transferStatus)
                        """)
                .param("id", o.id()).param("orderNo", o.orderNo()).param("visitId", o.visitId())
                .param("orderType", o.orderType()).param("itemCode", o.itemCode())
                .param("itemName", o.itemName()).param("dose", o.dose())
                .param("frequency", o.frequency()).param("frequencyTimes", o.frequencyTimes())
                .param("quantity", o.quantity())
                .param("unit", o.unit()).param("unitPrice", o.unitPrice())
                .param("amount", o.amount()).param("status", o.status())
                .param("orderedBy", o.orderedBy())
                .param("orderMode", o.orderMode())
                .param("startDate", o.startDate())
                .param("transferStatus", o.transferStatus() == null
                        ? VisitOrder.TRANSFER_NONE : o.transferStatus()).update();
    }

    // ---- 医嘱开立幂等（V33）

    /** 查幂等键首次落库时生成的医嘱 ID 列表；未命中返回 empty。 */
    public Optional<List<UUID>> findIdempotentOrderIds(UUID visitId, String actor,
                                                       String idempotencyKey) {
        return jdbc.sql("""
                        SELECT order_ids FROM clinical_svc.order_idempotency
                         WHERE visit_id = :visitId AND actor = :actor
                           AND idempotency_key = :key
                        """)
                .param("visitId", visitId).param("actor", actor).param("key", idempotencyKey)
                .query((rs, i) -> {
                    java.sql.Array array = rs.getArray("order_ids");
                    if (array == null) {
                        return List.<UUID>of();
                    }
                    Object[] raw = (Object[]) array.getArray();
                    List<UUID> ids = new ArrayList<>(raw.length);
                    for (Object o : raw) {
                        ids.add(o instanceof UUID u ? u : UUID.fromString(String.valueOf(o)));
                    }
                    return ids;
                })
                .optional();
    }

    /**
     * 记录幂等键 → 医嘱 ID 的结果快照。
     *
     * <p>并发重放时由唯一约束兜底：第二个请求插入失败会抛
     * {@code DataIntegrityViolationException}，服务层捕获后改读首次结果。
     */
    public void insertIdempotency(UUID visitId, String actor, String idempotencyKey,
                                  List<UUID> orderIds) {
        jdbc.sql("""
                        INSERT INTO clinical_svc.order_idempotency
                            (id, visit_id, actor, idempotency_key, order_ids)
                        VALUES (:id, :visitId, :actor, :key, CAST(:orderIds AS uuid[]))
                        """)
                .param("id", UUID.randomUUID()).param("visitId", visitId)
                .param("actor", actor).param("key", idempotencyKey)
                .param("orderIds", uuidArrayLiteral(orderIds)).update();
    }

    /**
     * 更新下游转送状态（转送成功/失败/重试）。
     *
     * <p>不走期望状态 CAS：转送是下游副作用回写，允许覆盖（PENDING → TRANSFERRED/FAILED，
     * FAILED → PENDING → …）；医嘱本身的医疗状态仍由 status 的 CAS 守护。
     */
    public boolean updateOrderTransfer(UUID orderId, String transferStatus, String externalType,
                                       UUID externalId, String externalNo, String error) {
        return jdbc.sql("""
                        UPDATE clinical_svc.visit_order
                           SET transfer_status = :transferStatus,
                               external_type = CAST(:externalType AS text),
                               external_id = CAST(:externalId AS uuid),
                               external_no = CAST(:externalNo AS text),
                               transfer_error = CAST(:error AS text),
                               version = version + 1, updated_at = now()
                         WHERE id = :id
                        """)
                .param("transferStatus", transferStatus)
                .param("externalType", externalType)
                .param("externalId", externalId)
                .param("externalNo", externalNo)
                .param("error", error)
                .param("id", orderId).update() > 0;
    }

    /**
     * 转送回写（成功）：带"医嘱仍有效"守卫。
     *
     * <p>转送在开嘱事务提交后才调用下游，此时 visit 行锁已释放；撤销/停嘱事务可能已把医嘱
     * 置 CANCELLED 或写入 stop_date。此时不能再回写 TRANSFERRED（否则撤销侧会因
     * {@code transferred()} 为假而跳过作废，处方仍可发药 —— 第六轮 MF-01）。
     *
     * @return false 表示医嘱已撤销/已停嘱/已被他人转送，调用方必须反向作废刚建立的下游单据
     */
    public boolean markOrderTransferred(UUID orderId, String externalType, UUID externalId,
                                        String externalNo) {
        return jdbc.sql("""
                        UPDATE clinical_svc.visit_order
                           SET transfer_status = 'TRANSFERRED',
                               external_type = :externalType,
                               external_id = :externalId,
                               external_no = :externalNo,
                               transfer_error = NULL,
                               version = version + 1, updated_at = now()
                         WHERE id = :id
                           AND status = 'PLACED' AND stop_date IS NULL
                           AND transfer_status = 'PENDING'
                        """)
                .param("externalType", externalType).param("externalId", externalId)
                .param("externalNo", externalNo).param("id", orderId)
                .update() > 0;
    }

    /** 按医嘱 ID 重读（转送提交后以库里最新状态为准，旧对象可能已被撤销/停嘱）。 */
    public Optional<VisitOrder> findOrderById(UUID orderId) {
        return jdbc.sql("SELECT * FROM clinical_svc.visit_order WHERE id = ?")
                .param(orderId).query(ORDER_MAPPER).optional();
    }

    /**
     * 加行锁读取医嘱；必须在事务内调用。
     *
     * <p>退药冲减与结算必须落在同一把行锁上：否则结算按旧净额入账后，
     * 退药侧再记 applied，医嘱已 CHARGED 不再扣减 → 患者多付且退款记录悬空。
     */
    public Optional<VisitOrder> lockOrderById(UUID orderId) {
        return jdbc.sql("SELECT * FROM clinical_svc.visit_order WHERE id = ? FOR UPDATE")
                .param(orderId).query(ORDER_MAPPER).optional();
    }

    /** 登记转送补偿任务；同一医嘱已有待处理任务时忽略（唯一索引兜底）。 */
    public void insertTransferTask(UUID id, UUID orderId, UUID visitId, String channel,
                                   String reason, String actor) {
        jdbc.sql("""
                        INSERT INTO clinical_svc.order_transfer_task
                            (id, order_id, visit_id, action, channel, reason, actor, status, attempts)
                        VALUES (:id, :orderId, :visitId, 'CANCEL', :channel, :reason, :actor,
                                'PENDING', 0)
                        ON CONFLICT DO NOTHING
                        """)
                .param("id", id).param("orderId", orderId).param("visitId", visitId)
                .param("channel", channel).param("reason", reason).param("actor", actor)
                .update();
    }

    private static final RowMapper<TransferTask> TRANSFER_TASK_MAPPER = (rs, i) -> new TransferTask(
            rs.getObject("id", UUID.class), rs.getObject("order_id", UUID.class),
            rs.getObject("visit_id", UUID.class), rs.getString("action"), rs.getString("channel"),
            rs.getString("reason"), rs.getString("actor"), rs.getString("status"),
            rs.getInt("attempts"), rs.getString("last_error"),
            rs.getTimestamp("created_at") == null ? null : rs.getTimestamp("created_at").toInstant());

    /**
     * 抢占待重试的补偿任务（P2-7：`FOR UPDATE SKIP LOCKED` + 租约）。
     *
     * <p>为什么必须抢占：旧实现是裸 SELECT，多副本会拿到同一批任务，
     * N 个副本各失败一次就把 attempts 放大 N 倍，约 3 轮误判 FAILED。
     * 现在同一行只会被一个实例租到；实例崩溃后租约到期自动回到可抢占状态。
     *
     * <p>`attempts` 只在真正失败时由持有租约的实例 +1（见 {@link #failTransferTask}）。
     */
    public List<TransferTask> claimPendingTransferTasks(String workerId, int limit,
                                                        int leaseSeconds) {
        return jdbc.sql("""
                        WITH picked AS (
                            SELECT id FROM clinical_svc.order_transfer_task
                             WHERE status = 'PENDING'
                               AND (locked_until IS NULL OR locked_until < now())
                             ORDER BY created_at
                             LIMIT :limit
                             FOR UPDATE SKIP LOCKED
                        )
                        UPDATE clinical_svc.order_transfer_task t
                           SET locked_until = now() + interval '1 second'
                                              * CAST(:leaseSeconds AS double precision),
                               locked_by = :worker,
                               updated_at = now()
                          FROM picked
                         WHERE t.id = picked.id
                        RETURNING t.id, t.order_id, t.visit_id, t.action, t.channel, t.reason,
                                  t.actor, t.status, t.attempts, t.last_error, t.created_at
                        """)
                .param("worker", workerId).param("limit", limit)
                .param("leaseSeconds", leaseSeconds)
                .query(TRANSFER_TASK_MAPPER).list();
    }

    public boolean completeTransferTask(UUID id) {
        return jdbc.sql("""
                        UPDATE clinical_svc.order_transfer_task
                           SET status = 'DONE', attempts = attempts + 1, last_error = NULL,
                               locked_until = NULL, locked_by = NULL, updated_at = now()
                         WHERE id = :id AND status = 'PENDING'
                        """)
                .param("id", id).update() > 0;
    }

    /** 重试失败：仍留 PENDING 等下一轮；超过上限由 worker 置 FAILED（人工介入）。 */
    public boolean failTransferTask(UUID id, String error, int maxAttempts) {
        return jdbc.sql("""
                        UPDATE clinical_svc.order_transfer_task
                           SET attempts = attempts + 1,
                               last_error = :error,
                               status = CASE WHEN attempts + 1 >= :maxAttempts THEN 'FAILED'
                                             ELSE 'PENDING' END,
                               locked_until = NULL,
                               locked_by = NULL,
                               updated_at = now()
                         WHERE id = :id AND status = 'PENDING'
                        """)
                .param("error", error).param("maxAttempts", maxAttempts)
                .param("id", id).update() > 0;
    }

    /** 停嘱（CAS）：仅 LONG + PLACED 可停；停嘱日仍计费，次日起不再计费。 */
    public boolean stopLongOrder(UUID orderId, UUID visitId, LocalDate stopDate,
                                 String operator, String reason) {
        return jdbc.sql("""
                        UPDATE clinical_svc.visit_order
                           SET stop_date = :stopDate, stopped_by = :operator, stop_reason = :reason,
                               version = version + 1, updated_at = now()
                         WHERE id = :id AND visit_id = :visitId
                           AND order_mode = 'LONG' AND status = 'PLACED' AND stop_date IS NULL
                        """)
                .param("stopDate", stopDate).param("operator", operator)
                .param("reason", reason).param("id", orderId).param("visitId", visitId)
                .update() > 0;
    }

    /** 某天可计费的长嘱（未停或停嘱日 &gt;= 计费日）。 */
    public List<VisitOrder> listBillableLongOrders(UUID visitId, LocalDate date) {
        return jdbc.sql("""
                        SELECT * FROM clinical_svc.visit_order
                         WHERE visit_id = :visitId AND order_mode = 'LONG' AND status = 'PLACED'
                           AND start_date <= :date AND (stop_date IS NULL OR stop_date >= :date)
                         ORDER BY created_at
                        """)
                .param("visitId", visitId).param("date", date)
                .query(ORDER_MAPPER).list();
    }

    /** 加行锁读取某天可计费的长嘱；必须在事务内调用（防并发停嘱/撤销）。 */
    public List<VisitOrder> lockBillableLongOrders(UUID visitId, LocalDate date) {
        return jdbc.sql("""
                        SELECT * FROM clinical_svc.visit_order
                         WHERE visit_id = :visitId AND order_mode = 'LONG' AND status = 'PLACED'
                           AND start_date <= :date AND (stop_date IS NULL OR stop_date >= :date)
                         ORDER BY created_at
                           FOR UPDATE
                        """)
                .param("visitId", visitId).param("date", date)
                .query(ORDER_MAPPER).list();
    }

    /**
     * 当日"应计未计"的长嘱（出院前必须为空，费用看板用于计算缺口）。
     *
     * <p>护理执行记录为 REFUSED / OMITTED 的医嘱视为不应计费，不计入缺口。
     */
    public List<VisitOrder> listUnbilledDailyOrders(UUID visitId, LocalDate date) {
        return jdbc.sql("""
                        SELECT o.* FROM clinical_svc.visit_order o
                         WHERE o.visit_id = :visitId AND o.order_mode = 'LONG' AND o.status = 'PLACED'
                           AND o.start_date <= :date AND (o.stop_date IS NULL OR o.stop_date >= :date)
                           AND NOT EXISTS (
                                 SELECT 1 FROM clinical_svc.charge_item ci
                                   JOIN clinical_svc.charge c ON c.id = ci.charge_id
                                  WHERE c.visit_id = o.visit_id
                                    AND ci.source_key = 'DAILY:' || o.id::text || ':' || :dateText)
                           AND NOT EXISTS (
                                 SELECT 1 FROM clinical_svc.nursing_execution ne
                                   LEFT JOIN clinical_svc.nursing_execution_correction nc
                                          ON nc.execution_id = ne.id
                                  WHERE ne.order_id = o.id AND ne.exec_date = :date
                                    AND coalesce(nc.to_status, ne.status) <> 'EXECUTED')
                         ORDER BY o.created_at
                        """)
                .param("visitId", visitId).param("date", date)
                .param("dateText", date.toString())
                .query(ORDER_MAPPER).list();
    }

    /** 当日"应计未计"的长嘱条数（出院前必须为 0）。 */
    public int countUnbilledDailyOrders(UUID visitId, LocalDate date) {
        return listUnbilledDailyOrders(visitId, date).size();
    }

    /**
     * 区间内（含首尾）"应计未计"的长嘱条数：**出院门禁**用。
     *
     * <p>为什么不能只查当天：历史某日漏跑按日计费时，出院当天可能是 0，
     * 但出院后就诊结束、`dailyBill` 又拒绝补计 → 这笔钱永久收不到。
     * 用 generate_series 展开每一天，与单日判定口径完全一致（含护理 REFUSED/OMITTED 豁免）。
     */
    public int countUnbilledDailyOrders(UUID visitId, LocalDate from, LocalDate to) {
        Long n = jdbc.sql("""
                        SELECT count(*)
                          FROM generate_series(CAST(:fromDate AS date), CAST(:toDate AS date),
                                               interval '1 day') AS d(day)
                          JOIN clinical_svc.visit_order o
                            ON o.visit_id = :visitId
                           AND o.order_mode = 'LONG' AND o.status = 'PLACED'
                           AND o.start_date <= d.day::date
                           AND (o.stop_date IS NULL OR o.stop_date >= d.day::date)
                         WHERE NOT EXISTS (
                                 SELECT 1 FROM clinical_svc.charge_item ci
                                   JOIN clinical_svc.charge c ON c.id = ci.charge_id
                                  WHERE c.visit_id = o.visit_id
                                    AND ci.source_key =
                                        'DAILY:' || o.id::text || ':' || d.day::date::text)
                           AND NOT EXISTS (
                                 SELECT 1 FROM clinical_svc.nursing_execution ne
                                   LEFT JOIN clinical_svc.nursing_execution_correction nc
                                          ON nc.execution_id = ne.id
                                  WHERE ne.order_id = o.id AND ne.exec_date = d.day::date
                                    AND coalesce(nc.to_status, ne.status) <> 'EXECUTED')
                        """)
                .param("visitId", visitId).param("fromDate", from).param("toDate", to)
                .query(Long.class).single();
        return n == null ? 0 : n.intValue();
    }

    /** 某医嘱某日是否已按日计费（护理补记非 EXECUTED 前必须确认未收费）。 */
    public boolean existsDailyCharge(UUID orderId, LocalDate date) {
        Long n = jdbc.sql("SELECT count(*) FROM clinical_svc.charge_item WHERE source_key = ?")
                .param("DAILY:" + orderId + ":" + date).query(Long.class).single();
        return n != null && n > 0;
    }

    public Optional<VisitOrder> findOrder(UUID visitId, UUID orderId) {
        return jdbc.sql("""
                        SELECT * FROM clinical_svc.visit_order WHERE id = :orderId AND visit_id = :visitId
                        """)
                .param("orderId", orderId).param("visitId", visitId)
                .query(ORDER_MAPPER).optional();
    }

    public List<VisitOrder> listOrders(UUID visitId, String status) {
        if (status == null || status.isBlank()) {
            return jdbc.sql("""
                            SELECT * FROM clinical_svc.visit_order
                             WHERE visit_id = ? ORDER BY created_at
                            """).param(visitId).query(ORDER_MAPPER).list();
        }
        return jdbc.sql("""
                        SELECT * FROM clinical_svc.visit_order
                         WHERE visit_id = :visitId AND status = :status ORDER BY created_at
                        """).param("visitId", visitId).param("status", status)
                .query(ORDER_MAPPER).list();
    }

    /**
     * 加行锁读取待计费的临时医嘱；必须在事务内调用。
     *
     * <p>结算时对 pending 医嘱加锁，避免"结算进行中该医嘱被并发撤销却仍被收费"。
     */
    public List<VisitOrder> lockPlacedTempOrders(UUID visitId) {
        return jdbc.sql("""
                        SELECT * FROM clinical_svc.visit_order
                         WHERE visit_id = :visitId AND status = 'PLACED' AND order_mode = 'TEMP'
                         ORDER BY created_at
                           FOR UPDATE
                        """)
                .param("visitId", visitId).query(ORDER_MAPPER).list();
    }

    /** 医嘱置为已计费（CAS：仅 PLACED 可迁移）。返回成功条数。 */
    public int markOrdersCharged(List<UUID> orderIds) {
        int n = 0;
        for (UUID id : orderIds) {
            n += jdbc.sql("""
                            UPDATE clinical_svc.visit_order
                               SET status = 'CHARGED', version = version + 1, updated_at = now()
                             WHERE id = :id AND status = 'PLACED'
                            """).param("id", id).update();
        }
        return n;
    }

    /**
     * 撤销临时医嘱（P1-7：长嘱一律拒绝，库级兜底）。
     *
     * <p>长嘱按日计费不会把 {@code status} 改成 CHARGED，所以"只看 status='PLACED'"
     * 会让已计费多日的长嘱也能被撤销。服务层已提前提示改用停嘱，
     * 这里再补一条 {@code order_mode <> 'LONG'}，防止应用层被绕过。
     */
    public boolean cancelOrder(UUID orderId, UUID visitId) {
        return jdbc.sql("""
                        UPDATE clinical_svc.visit_order
                           SET status = 'CANCELLED', version = version + 1, updated_at = now()
                         WHERE id = :id AND visit_id = :visitId AND status = 'PLACED'
                           AND order_mode <> 'LONG'
                        """)
                .param("id", orderId).param("visitId", visitId).update() > 0;
    }

    // ---------------------------------------------------------------- 诊断

    public Optional<DiagnosisDef> findDiagnosisByCode(String code) {
        return jdbc.sql("""
                        SELECT id, code, name, category, enabled, version
                          FROM clinical_svc.md_diagnosis WHERE code = ?
                        """).param(code).query(DIAG_DEF_MAPPER).optional();
    }

    /** 诊断检索（只列启用项）：keyword 匹配编码或名称。 */
    public List<DiagnosisDef> listDiagnosisDefs(String keyword, int limit) {
        String pattern = keyword == null || keyword.isBlank() ? null : "%" + keyword.trim() + "%";
        return jdbc.sql("""
                        SELECT id, code, name, category, enabled, version
                          FROM clinical_svc.md_diagnosis
                         WHERE enabled = true
                           AND (CAST(:pattern AS text) IS NULL
                                OR code ILIKE CAST(:pattern AS text)
                                OR name ILIKE CAST(:pattern AS text))
                         ORDER BY code
                         LIMIT :limit
                        """)
                .param("pattern", pattern).param("limit", limit)
                .query(DIAG_DEF_MAPPER).list();
    }

    /** 就诊诊断（主诊断优先，其次按录入顺序）。 */
    public List<VisitDiagnosis> listVisitDiagnoses(UUID visitId) {
        return jdbc.sql("""
                        SELECT id, visit_id, code, name, primary_flag, sort_no,
                               diagnosed_by, diagnosed_at, version
                          FROM clinical_svc.visit_diagnosis
                         WHERE visit_id = ?
                         ORDER BY primary_flag DESC, sort_no, diagnosed_at
                        """).param(visitId).query(VISIT_DIAG_MAPPER).list();
    }

    public Optional<VisitDiagnosis> findVisitDiagnosis(UUID visitId, UUID id) {
        return jdbc.sql("""
                        SELECT id, visit_id, code, name, primary_flag, sort_no,
                               diagnosed_by, diagnosed_at, version
                          FROM clinical_svc.visit_diagnosis
                         WHERE visit_id = :visitId AND id = :id
                        """)
                .param("visitId", visitId).param("id", id)
                .query(VISIT_DIAG_MAPPER).optional();
    }

    public boolean existsPrimaryDiagnosis(UUID visitId) {
        Long n = jdbc.sql("""
                        SELECT count(*) FROM clinical_svc.visit_diagnosis
                         WHERE visit_id = ? AND primary_flag
                        """).param(visitId).query(Long.class).single();
        return n != null && n > 0;
    }

    public int nextDiagnosisSortNo(UUID visitId) {
        Integer max = jdbc.sql("""
                        SELECT COALESCE(max(sort_no), 0) FROM clinical_svc.visit_diagnosis
                         WHERE visit_id = ?
                        """).param(visitId).query(Integer.class).single();
        return (max == null ? 0 : max) + 1;
    }

    public void insertVisitDiagnosis(VisitDiagnosis d) {
        jdbc.sql("""
                        INSERT INTO clinical_svc.visit_diagnosis
                            (id, visit_id, code, name, primary_flag, sort_no,
                             diagnosed_by, version)
                        VALUES (:id, :visitId, :code, :name, :primaryFlag, :sortNo,
                                :diagnosedBy, 0)
                        """)
                .param("id", d.id()).param("visitId", d.visitId()).param("code", d.code())
                .param("name", d.name()).param("primaryFlag", d.primaryFlag())
                .param("sortNo", d.sortNo()).param("diagnosedBy", d.diagnosedBy()).update();
    }

    /** 清空主诊断标记（换主诊断时先清再设，事务内保证仍然只有一条）。 */
    public boolean clearPrimaryDiagnosis(UUID visitId) {
        return jdbc.sql("""
                        UPDATE clinical_svc.visit_diagnosis
                           SET primary_flag = false, version = version + 1
                         WHERE visit_id = :visitId AND primary_flag
                        """).param("visitId", visitId).update() > 0;
    }

    public boolean setPrimaryDiagnosis(UUID visitId, UUID id) {
        return jdbc.sql("""
                        UPDATE clinical_svc.visit_diagnosis
                           SET primary_flag = true, version = version + 1
                         WHERE visit_id = :visitId AND id = :id
                        """)
                .param("visitId", visitId).param("id", id).update() > 0;
    }

    public boolean deleteVisitDiagnosis(UUID visitId, UUID id) {
        return jdbc.sql("""
                        DELETE FROM clinical_svc.visit_diagnosis
                         WHERE visit_id = :visitId AND id = :id
                        """)
                .param("visitId", visitId).param("id", id).update() > 0;
    }

    // ---------------------------------------------------------------- 收费

    /** 当前未结收费单（每个就诊最多一张，部分唯一索引兜底）。 */
    public Optional<Charge> findOpenCharge(UUID visitId) {
        return jdbc.sql("""
                        SELECT id, charge_no, visit_id, total_amount, status, payment_method, paid_at, version
                          FROM clinical_svc.charge
                         WHERE visit_id = ? AND status = 'UNPAID'
                         ORDER BY created_at DESC LIMIT 1
                        """).param(visitId).query(CHARGE_MAPPER).optional();
    }

    /** 加行锁读取未结收费单；必须在事务内调用。 */
    public Optional<Charge> lockOpenCharge(UUID visitId) {
        return jdbc.sql("""
                        SELECT id, charge_no, visit_id, total_amount, status, payment_method, paid_at, version
                          FROM clinical_svc.charge
                         WHERE visit_id = ? AND status = 'UNPAID'
                         ORDER BY created_at DESC LIMIT 1
                           FOR UPDATE
                        """).param(visitId).query(CHARGE_MAPPER).optional();
    }

    /** 最近一张收费单（结算幂等返回用；中途结算后可能有历史已结单）。 */
    public Optional<Charge> findLatestCharge(UUID visitId) {
        return jdbc.sql("""
                        SELECT id, charge_no, visit_id, total_amount, status, payment_method, paid_at, version
                          FROM clinical_svc.charge WHERE visit_id = ?
                         ORDER BY created_at DESC LIMIT 1
                        """).param(visitId).query(CHARGE_MAPPER).optional();
    }

    public Optional<Charge> findCharge(UUID id) {
        return jdbc.sql("""
                        SELECT id, charge_no, visit_id, total_amount, status, payment_method, paid_at, version
                          FROM clinical_svc.charge WHERE id = ?
                        """).param(id).query(CHARGE_MAPPER).optional();
    }

    /** 加行锁读取收费单；退药冲减前必须锁单，避免与并发结算互相踩踏。 */
    public Optional<Charge> lockCharge(UUID id) {
        return jdbc.sql("""
                        SELECT id, charge_no, visit_id, total_amount, status, payment_method, paid_at, version
                          FROM clinical_svc.charge WHERE id = ? FOR UPDATE
                        """).param(id).query(CHARGE_MAPPER).optional();
    }

    /**
     * 就诊单的费用明细行（只读投影，供医保域出结算清单）。
     *
     * <p>跨域不直查数据库，调用方必须走内网内部接口。
     *
     * <p>净额口径（第六轮 MF-04）：已结算收费单不允许改账，退药只登记
     * {@code pharmacy_charge.refund_pending_amount}；这里把它作为**负向虚拟行**输出，
     * 使清单与病案首页、收费单在"退药后"仍然对得上，而不是按毛额多申报。
     */
    public List<ChargeLine> listChargeLines(UUID visitId) {
        return jdbc.sql("""
                        SELECT t.charge_no, t.item_code, t.item_name, t.category, t.amount,
                               t.charge_status
                          FROM (
                               SELECT c.charge_no, ci.item_code, ci.item_name, ci.category,
                                      ci.amount, c.status AS charge_status, ci.created_at
                                 FROM clinical_svc.charge c
                                 JOIN clinical_svc.charge_item ci ON ci.charge_id = c.id
                                WHERE c.visit_id = :visitId
                               UNION ALL
                               -- 已结算退药的待退费：按该药原始项目出负行
                               SELECT c.charge_no, pc.drug_code, pc.drug_name, 'DRUG',
                                      (-pc.refund_pending_amount), c.status, pc.updated_at
                                 FROM clinical_svc.pharmacy_charge pc
                                 JOIN clinical_svc.charge c ON c.id = pc.charge_id
                                WHERE pc.visit_id = :visitId AND pc.refund_pending_amount > 0
                          ) t
                         ORDER BY t.created_at
                        """)
                .param("visitId", visitId)
                .query((rs, i) -> new ChargeLine(
                        rs.getString("charge_no"), rs.getString("item_code"),
                        rs.getString("item_name"), rs.getString("category"),
                        rs.getBigDecimal("amount"), rs.getString("charge_status")))
                .list();
    }

    /**
     * 病案首页费用汇总：按收费项目类别聚合（已退费单据不计入，与医保清单口径一致）。
     *
     * <p>只返回出现过的类别，未出现的类别由调用方补 0；
     * 金额在数据库内聚合，避免把全部明细拉到应用层再求和。
     *
     * <p>净额口径（第六轮 MF-04）：已结算退药的"待财务退费"在这里作为 DRUG 类的减项，
     * 否则首页金额会比清单多出未退金额，出院后无法纠正。
     */
    public Map<String, BigDecimal> sumChargeByCategory(UUID visitId) {
        List<Map.Entry<String, BigDecimal>> rows = jdbc.sql("""
                        SELECT t.category, COALESCE(sum(t.amount), 0) AS amount
                          FROM (
                               SELECT ci.category, ci.amount
                                 FROM clinical_svc.charge c
                                 JOIN clinical_svc.charge_item ci ON ci.charge_id = c.id
                                WHERE c.visit_id = :visitId AND c.status <> 'REFUNDED'
                               UNION ALL
                               SELECT 'DRUG', (-pc.refund_pending_amount)
                                 FROM clinical_svc.pharmacy_charge pc
                                 JOIN clinical_svc.charge c ON c.id = pc.charge_id
                                WHERE pc.visit_id = :visitId
                                  AND pc.refund_pending_amount > 0
                                  AND c.status <> 'REFUNDED'
                          ) t
                         GROUP BY t.category
                        """)
                .param("visitId", visitId)
                .query((rs, i) -> Map.entry(rs.getString("category"), rs.getBigDecimal("amount")))
                .list();
        Map<String, BigDecimal> summary = new LinkedHashMap<>();
        for (Map.Entry<String, BigDecimal> row : rows) {
            summary.put(row.getKey(), row.getValue());
        }
        return summary;
    }

    public List<Charge> listCharges(UUID visitId) {
        return jdbc.sql("""
                        SELECT id, charge_no, visit_id, total_amount, status, payment_method, paid_at, version
                          FROM clinical_svc.charge WHERE visit_id = ? ORDER BY created_at
                        """).param(visitId).query(CHARGE_MAPPER).list();
    }

    /** 是否存在"有金额的未结收费单"（出院前必须为 false）。 */
    public boolean hasUnsettledCharge(UUID visitId) {
        Long n = jdbc.sql("""
                        SELECT count(*) FROM clinical_svc.charge
                         WHERE visit_id = ? AND status = 'UNPAID' AND total_amount > 0
                        """).param(visitId).query(Long.class).single();
        return n != null && n > 0;
    }

    /** 未计费的临时医嘱条数（出院前必须为 0；长嘱不在此列，出院即视为停止）。 */
    public int countUnbilledTempOrders(UUID visitId) {
        Long n = jdbc.sql("""
                        SELECT count(*) FROM clinical_svc.visit_order
                         WHERE visit_id = ? AND status = 'PLACED' AND order_mode = 'TEMP'
                        """).param(visitId).query(Long.class).single();
        return n == null ? 0 : n.intValue();
    }

    /** 作废该就诊的未结收费单（取消挂号时调用：避免悬挂一张永远不能结算的挂号费单）。 */
    public int cancelOpenCharges(UUID visitId) {
        return jdbc.sql("""
                        UPDATE clinical_svc.charge
                           SET status = 'REFUNDED', version = version + 1, updated_at = now()
                         WHERE visit_id = :visitId AND status = 'UNPAID'
                        """).param("visitId", visitId).update();
    }

    // ---------------------------------------------------------------- 收费票据（G14）

    private static final RowMapper<ChargeReceipt> RECEIPT_MAPPER = (rs, i) -> new ChargeReceipt(
            rs.getObject("id", UUID.class), rs.getString("receipt_no"),
            rs.getObject("charge_id", UUID.class), rs.getObject("visit_id", UUID.class),
            rs.getBigDecimal("amount"), rs.getString("payment_method"), rs.getString("status"),
            rs.getString("issued_by"), rs.getTimestamp("issued_at").toInstant(),
            rs.getString("voided_by"),
            rs.getTimestamp("voided_at") == null ? null : rs.getTimestamp("voided_at").toInstant(),
            rs.getString("void_reason"),
            rs.getInt("reprint_count"), rs.getLong("version"),
            rs.getTimestamp("created_at").toInstant());

    public void insertReceipt(ChargeReceipt r) {
        jdbc.sql("""
                        INSERT INTO clinical_svc.charge_receipt
                            (id, receipt_no, charge_id, visit_id, amount, payment_method, status,
                             issued_by, version)
                        VALUES (:id, :no, :chargeId, :visitId, :amount,
                                CAST(:method AS text), :status, :issuedBy, 0)
                        """)
                .param("id", r.id()).param("no", r.receiptNo()).param("chargeId", r.chargeId())
                .param("visitId", r.visitId()).param("amount", r.amount())
                .param("method", r.paymentMethod()).param("status", r.status())
                .param("issuedBy", r.issuedBy()).update();
    }

    public Optional<ChargeReceipt> findReceipt(UUID id) {
        return jdbc.sql("""
                        SELECT id, receipt_no, charge_id, visit_id, amount, payment_method, status,
                               issued_by, issued_at, voided_by, voided_at, void_reason,
                               reprint_count, version, created_at
                          FROM clinical_svc.charge_receipt WHERE id = ?
                        """).param(id).query(RECEIPT_MAPPER).optional();
    }

    /** 加行锁读取票据；重打/作废前必须调用。 */
    public Optional<ChargeReceipt> lockReceipt(UUID id) {
        return jdbc.sql("""
                        SELECT id, receipt_no, charge_id, visit_id, amount, payment_method, status,
                               issued_by, issued_at, voided_by, voided_at, void_reason,
                               reprint_count, version, created_at
                          FROM clinical_svc.charge_receipt WHERE id = ? FOR UPDATE
                        """).param(id).query(RECEIPT_MAPPER).optional();
    }

    /** 收费单当前有效票据（部分唯一索引保证最多一张）。 */
    public Optional<ChargeReceipt> findActiveReceiptByCharge(UUID chargeId) {
        return jdbc.sql("""
                        SELECT id, receipt_no, charge_id, visit_id, amount, payment_method, status,
                               issued_by, issued_at, voided_by, voided_at, void_reason,
                               reprint_count, version, created_at
                          FROM clinical_svc.charge_receipt
                         WHERE charge_id = ? AND status = 'ISSUED'
                        """).param(chargeId).query(RECEIPT_MAPPER).optional();
    }

    public List<ChargeReceipt> listReceipts(UUID visitId, int limit) {
        return jdbc.sql("""
                        SELECT id, receipt_no, charge_id, visit_id, amount, payment_method, status,
                               issued_by, issued_at, voided_by, voided_at, void_reason,
                               reprint_count, version, created_at
                          FROM clinical_svc.charge_receipt
                         WHERE visit_id = :visitId
                         ORDER BY issued_at DESC, receipt_no DESC
                         LIMIT :limit
                        """).param("visitId", visitId).param("limit", limit)
                .query(RECEIPT_MAPPER).list();
    }

    /** 重打：只累加次数（CAS 版本号，作废后不可重打）。 */
    public boolean countReprint(UUID id, long expectedVersion) {
        return jdbc.sql("""
                        UPDATE clinical_svc.charge_receipt
                           SET reprint_count = reprint_count + 1, version = version + 1,
                               updated_at = now()
                         WHERE id = :id AND status = 'ISSUED' AND version = :version
                        """).param("id", id).param("version", expectedVersion).update() > 0;
    }

    /** 作废：必须写原因；作废后可为同一收费单换开新票。 */
    public boolean voidReceipt(UUID id, long expectedVersion, String actor, String reason) {
        return jdbc.sql("""
                        UPDATE clinical_svc.charge_receipt
                           SET status = 'VOID', voided_by = :actor, voided_at = now(),
                               void_reason = :reason, version = version + 1, updated_at = now()
                         WHERE id = :id AND status = 'ISSUED' AND version = :version
                        """)
                .param("actor", actor).param("reason", reason)
                .param("id", id).param("version", expectedVersion).update() > 0;
    }

    /**
     * 改约（G1）：换号源，同时更新医生与就诊日期。
     *
     * <p>CAS 三重条件：只允许 REGISTERED、版本未变；日期/医生由号源决定，
     * 因此客户端无法传入"号源 A + 医生 B"这类矛盾组合。
     */
    public boolean updateVisitSlot(UUID id, UUID slotId, String doctorId, LocalDate visitDate,
                                   long expectedVersion) {
        return jdbc.sql("""
                        UPDATE clinical_svc.visit
                           SET slot_id = :slotId, doctor_id = :doctorId, visit_date = :visitDate,
                               version = version + 1, updated_at = now()
                         WHERE id = :id AND status = 'REGISTERED' AND version = :version
                        """)
                .param("slotId", slotId).param("doctorId", doctorId).param("visitDate", visitDate)
                .param("id", id).param("version", expectedVersion).update() > 0;
    }

    // ---------------------------------------------------------------- 门诊日结（G1）

    /**
     * 门诊日结合计：当日已收笔数/金额，以及当日退费额。
     *
     * <p>统计口径按**收款时刻**（{@code paid_at}）落在业务日窗口内，与财务"当日实收"一致；
     * 退费额按 {@code status='REFUNDED'} 且 {@code updated_at} 落在窗口内估算，
     * 用于日结时提示"当班发生过退费"，不与实收相抵（相抵会掩盖资金流水）。
     */
    public OutpatientPaidSummary outpatientPaidSummary(OffsetDateTime from, OffsetDateTime to) {
        return jdbc.sql("""
                        SELECT count(DISTINCT v.id) FILTER (WHERE c.status = 'PAID') AS visits,
                               count(*) FILTER (WHERE c.status = 'PAID')            AS paid_charges,
                               COALESCE(sum(c.total_amount) FILTER (WHERE c.status = 'PAID'), 0) AS paid_amount,
                               COALESCE(sum(c.total_amount) FILTER (WHERE c.status = 'REFUNDED'), 0) AS refunded_amount
                          FROM clinical_svc.charge c
                          JOIN clinical_svc.visit v ON v.id = c.visit_id
                         WHERE v.visit_type = 'OUTPATIENT'
                           AND (
                                (c.status = 'PAID' AND c.paid_at >= :fromTs AND c.paid_at < :toTs)
                             OR (c.status = 'REFUNDED' AND c.updated_at >= :fromTs AND c.updated_at < :toTs)
                           )
                        """)
                .param("fromTs", from).param("toTs", to)
                .query((rs, i) -> new OutpatientPaidSummary(
                        rs.getInt("visits"), rs.getInt("paid_charges"),
                        rs.getBigDecimal("paid_amount"), rs.getBigDecimal("refunded_amount")))
                .single();
    }

    /**
     * 门诊实收趋势（G13）：按收款日聚合，退费按退费日聚合；缺日由服务层补 0。
     *
     * <p>{@code zone} 由服务层传入业务时区，保证与日结的"归日"口径一致。
     */
    public List<DailyPaidTotal> outpatientPaidTrend(OffsetDateTime from, OffsetDateTime to,
                                                    String zone) {
        return jdbc.sql("""
                        SELECT day,
                               sum(visits)::int AS visits,
                               sum(paid_charges)::int AS paid_charges,
                               COALESCE(sum(paid_amount), 0) AS paid_amount,
                               COALESCE(sum(refunded_amount), 0) AS refunded_amount
                          FROM (
                                SELECT (c.paid_at AT TIME ZONE CAST(:zone AS text))::date AS day,
                                       count(DISTINCT v.id) AS visits,
                                       count(*) AS paid_charges,
                                       sum(c.total_amount) AS paid_amount,
                                       0::numeric AS refunded_amount
                                  FROM clinical_svc.charge c
                                  JOIN clinical_svc.visit v ON v.id = c.visit_id
                                 WHERE v.visit_type = 'OUTPATIENT'
                                   AND c.status = 'PAID'
                                   AND c.paid_at >= :fromTs AND c.paid_at < :toTs
                                 GROUP BY 1
                                UNION ALL
                                SELECT (c.updated_at AT TIME ZONE CAST(:zone AS text))::date AS day,
                                       0 AS visits,
                                       0 AS paid_charges,
                                       0::numeric AS paid_amount,
                                       sum(c.total_amount) AS refunded_amount
                                  FROM clinical_svc.charge c
                                  JOIN clinical_svc.visit v ON v.id = c.visit_id
                                 WHERE v.visit_type = 'OUTPATIENT'
                                   AND c.status = 'REFUNDED'
                                   AND c.updated_at >= :fromTs AND c.updated_at < :toTs
                                 GROUP BY 1
                          ) t
                         GROUP BY day
                         ORDER BY day
                        """)
                .param("fromTs", from).param("toTs", to).param("zone", zone)
                .query((rs, i) -> new DailyPaidTotal(
                        rs.getDate("day").toLocalDate(), rs.getInt("visits"),
                        rs.getInt("paid_charges"), rs.getBigDecimal("paid_amount"),
                        rs.getBigDecimal("refunded_amount")))
                .list();
    }

    // ---------------------------------------------------------------- 住院周转趋势（G16）

    /**
     * 住院周转趋势：按业务日聚合入院数、出院数与出院者平均住院日。
     *
     * <p>入/出院分开归日（入院按 {@code admitted_at}、出院按 {@code discharged_at}），
     * 因此同一天可能既有入也有出；平均住院日只对当日出院者计算，单位为天。
     */
    public List<DailyAdmissionTotal> dailyAdmissionTrend(OffsetDateTime from, OffsetDateTime to,
                                                         String zone) {
        return jdbc.sql("""
                        SELECT day,
                               sum(admitted_count)::int AS admitted_count,
                               sum(discharged_count)::int AS discharged_count,
                               CASE WHEN sum(discharged_count) = 0 THEN 0
                                    ELSE sum(avg_stay_days * discharged_count) / sum(discharged_count)
                                END AS avg_stay_days
                          FROM (
                                SELECT (a.admitted_at AT TIME ZONE CAST(:zone AS text))::date AS day,
                                       count(*)::int AS admitted_count,
                                       0 AS discharged_count,
                                       0::numeric AS avg_stay_days
                                  FROM clinical_svc.admission a
                                 WHERE a.admitted_at >= :fromTs AND a.admitted_at < :toTs
                                 GROUP BY 1
                                UNION ALL
                                SELECT (a.discharged_at AT TIME ZONE CAST(:zone AS text))::date AS day,
                                       0 AS admitted_count,
                                       count(*)::int AS discharged_count,
                                       avg(EXTRACT(EPOCH FROM (a.discharged_at - a.admitted_at)) / 86400.0)
                                           AS avg_stay_days
                                  FROM clinical_svc.admission a
                                 WHERE a.status = 'DISCHARGED'
                                   AND a.discharged_at >= :fromTs AND a.discharged_at < :toTs
                                 GROUP BY 1
                          ) t
                         GROUP BY day
                         ORDER BY day
                        """)
                .param("fromTs", from).param("toTs", to).param("zone", zone)
                .query((rs, i) -> new DailyAdmissionTotal(
                        rs.getDate("day").toLocalDate(), rs.getInt("admitted_count"),
                        rs.getInt("discharged_count"), rs.getBigDecimal("avg_stay_days")))
                .list();
    }

    /**
     * 住院周转趋势：每个业务日结束时刻的在院人数。
     *
     * <p>在院口径 = 入院时刻早于次日零点，且未出院或出院时刻不早于次日零点；
     * 用 generate_series 展开区间内每一天，避免按天循环查库。
     */
    public List<DailyInHospitalTotal> dailyInHospitalTrend(OffsetDateTime from, OffsetDateTime to,
                                                           String zone) {
        return jdbc.sql("""
                        SELECT (gs AT TIME ZONE CAST(:zone AS text))::date AS day,
                               (SELECT count(*)
                                  FROM clinical_svc.admission a
                                 WHERE a.admitted_at < gs + interval '1 day'
                                   AND (a.discharged_at IS NULL
                                        OR a.discharged_at >= gs + interval '1 day'))::int
                                   AS in_hospital_count
                          FROM generate_series(CAST(:fromTs AS timestamptz),
                                               CAST(:toTs AS timestamptz) - interval '1 day',
                                               interval '1 day') gs
                         ORDER BY 1
                        """)
                .param("fromTs", from).param("toTs", to).param("zone", zone)
                .query((rs, i) -> new DailyInHospitalTotal(
                        rs.getDate("day").toLocalDate(), rs.getInt("in_hospital_count")))
                .list();
    }

    /** 住院周转趋势：区间入院/出院合计（用于上一等长周期环比）。 */
    public AdmissionPeriodSummary admissionPeriodSummary(OffsetDateTime from, OffsetDateTime to) {
        return jdbc.sql("""
                        SELECT count(*) FILTER (WHERE admitted_at >= :fromTs AND admitted_at < :toTs)::int
                                   AS admitted_count,
                               count(*) FILTER (WHERE status = 'DISCHARGED'
                                                  AND discharged_at >= :fromTs
                                                  AND discharged_at < :toTs)::int
                                   AS discharged_count
                          FROM clinical_svc.admission
                        """)
                .param("fromTs", from).param("toTs", to)
                .query((rs, i) -> new AdmissionPeriodSummary(
                        rs.getInt("admitted_count"), rs.getInt("discharged_count")))
                .single();
    }

    /** 可用床位数（不含停用床），用于计算床位使用率。 */
    public int countUsableBeds() {
        Long n = jdbc.sql("SELECT count(*) FROM clinical_svc.bed WHERE status <> 'DISABLED'")
                .query(Long.class).single();
        return n == null ? 0 : n.intValue();
    }

    // ---------------------------------------------------------------- 运营日快照（G17）

    /**
     * 写入/覆盖单日单指标快照。
     *
     * <p>唯一键 {@code (snapshot_date, metric)}：定时任务重复跑、手动补采同一天都只覆盖，
     * 不会产生重复行；多副本并发执行也由数据库唯一约束收敛。
     */
    public void upsertReportSnapshot(UUID id, LocalDate day, String metric, BigDecimal value,
                                     String detail, String source) {
        jdbc.sql("""
                        INSERT INTO clinical_svc.report_snapshot
                            (id, snapshot_date, metric, value, detail, source)
                        VALUES (:id, :day, :metric, :value, CAST(:detail AS jsonb), :source)
                        ON CONFLICT (snapshot_date, metric) DO UPDATE
                           SET value = EXCLUDED.value,
                               detail = EXCLUDED.detail,
                               source = EXCLUDED.source,
                               updated_at = now()
                        """)
                .param("id", id).param("day", day).param("metric", metric)
                .param("value", value).param("detail", detail).param("source", source)
                .update();
    }

    /** 快照查询：metric 为空返回全部；按日期倒序、指标正序。 */
    public List<ReportSnapshot> listReportSnapshots(LocalDate from, LocalDate to, String metric) {
        String metricKey = (metric == null || metric.isBlank()) ? null : metric.trim();
        return jdbc.sql("""
                        SELECT snapshot_date, metric, value, detail::text AS detail,
                               source, updated_at
                          FROM clinical_svc.report_snapshot
                         WHERE snapshot_date >= :from AND snapshot_date <= :to
                           AND (CAST(:metric AS text) IS NULL OR metric = CAST(:metric AS text))
                         ORDER BY snapshot_date DESC, metric
                        """)
                .param("from", from).param("to", to).param("metric", metricKey)
                .query((rs, i) -> new ReportSnapshot(
                        rs.getDate("snapshot_date").toLocalDate(), rs.getString("metric"),
                        rs.getBigDecimal("value"), rs.getString("detail"),
                        rs.getString("source"), rs.getTimestamp("updated_at").toInstant()))
                .list();
    }

    // ---------------------------------------------------------------- 报表订阅与投递（G19）

    private static final RowMapper<ReportSubscription> SUBSCRIPTION_MAPPER = (rs, i) ->
            new ReportSubscription(
                    rs.getObject("id", UUID.class), rs.getString("name"),
                    rs.getString("report_code"), rs.getString("channel"),
                    rs.getString("target"), rs.getTime("send_time").toLocalTime(),
                    rs.getObject("recipient_group_id", UUID.class),
                    rs.getBoolean("enabled"), rs.getLong("version"),
                    rs.getString("created_by"),
                    rs.getTimestamp("updated_at").toInstant());

    private static final String SUBSCRIPTION_COLUMNS = """
            id, name, report_code, channel, target, send_time, recipient_group_id,
            enabled, version, created_by, updated_at
            """;

    private static final RowMapper<ReportDelivery> DELIVERY_MAPPER = (rs, i) ->
            new ReportDelivery(
                    rs.getObject("id", UUID.class),
                    rs.getObject("subscription_id", UUID.class),
                    rs.getString("subscription_name"),
                    rs.getString("report_code"),
                    rs.getDate("report_date").toLocalDate(), rs.getString("status"),
                    rs.getInt("attempts"), rs.getString("last_error"),
                    rs.getTimestamp("created_at").toInstant(),
                    rs.getTimestamp("sent_at") == null ? null : rs.getTimestamp("sent_at").toInstant());

    public List<ReportSubscription> listReportSubscriptions() {
        return jdbc.sql("SELECT " + SUBSCRIPTION_COLUMNS + """
                          FROM clinical_svc.report_subscription
                         ORDER BY enabled DESC, send_time, name
                        """).query(SUBSCRIPTION_MAPPER).list();
    }

    public Optional<ReportSubscription> findReportSubscription(UUID id) {
        return jdbc.sql("SELECT " + SUBSCRIPTION_COLUMNS
                        + " FROM clinical_svc.report_subscription WHERE id = ?")
                .param(id).query(SUBSCRIPTION_MAPPER).optional();
    }

    public void insertReportSubscription(ReportSubscription s) {
        jdbc.sql("""
                        INSERT INTO clinical_svc.report_subscription
                            (id, name, report_code, channel, target, send_time,
                             recipient_group_id, enabled, created_by)
                        VALUES (:id, :name, :code, :channel, :target, :sendTime,
                                CAST(:groupId AS uuid), :enabled, :actor)
                        """)
                .param("id", s.id()).param("name", s.name()).param("code", s.reportCode())
                .param("channel", s.channel()).param("target", s.target())
                .param("sendTime", s.sendTime()).param("groupId", s.recipientGroupId())
                .param("enabled", s.enabled()).param("actor", s.createdBy())
                .update();
    }

    /** 更新订阅；返回 false 表示版本已被他人修改（CAS 失败）。 */
    public boolean updateReportSubscription(UUID id, String name, String channel, String target,
                                            java.time.LocalTime sendTime, UUID recipientGroupId,
                                            boolean enabled, long version) {
        return jdbc.sql("""
                        UPDATE clinical_svc.report_subscription
                           SET name = :name, channel = :channel, target = :target,
                               send_time = :sendTime, recipient_group_id = CAST(:groupId AS uuid),
                               enabled = :enabled, version = version + 1, updated_at = now()
                         WHERE id = :id AND version = :version
                        """)
                .param("id", id).param("name", name).param("channel", channel)
                .param("target", target).param("sendTime", sendTime)
                .param("groupId", recipientGroupId)
                .param("enabled", enabled).param("version", version)
                .update() > 0;
    }

    // ---------------------------------------------------------------- 收件人分组（G25）

    private static final RowMapper<ReportRecipientGroup> GROUP_MAPPER = (rs, i) ->
            new ReportRecipientGroup(
                    rs.getObject("id", UUID.class), rs.getString("code"), rs.getString("name"),
                    rs.getString("description"), rs.getBoolean("enabled"), rs.getLong("version"),
                    rs.getString("created_by"), rs.getTimestamp("updated_at").toInstant());

    private static final RowMapper<ReportRecipientGroupMember> GROUP_MEMBER_MAPPER = (rs, i) ->
            new ReportRecipientGroupMember(
                    rs.getObject("id", UUID.class), rs.getObject("group_id", UUID.class),
                    rs.getString("email"), rs.getString("display_name"),
                    rs.getBoolean("enabled"), rs.getTimestamp("created_at").toInstant());

    public List<ReportRecipientGroup> listReportRecipientGroups() {
        return jdbc.sql("""
                        SELECT id, code, name, description, enabled, version, created_by, updated_at
                          FROM clinical_svc.report_recipient_group
                         ORDER BY enabled DESC, code
                        """).query(GROUP_MAPPER).list();
    }

    public Optional<ReportRecipientGroup> findReportRecipientGroup(UUID id) {
        return jdbc.sql("""
                        SELECT id, code, name, description, enabled, version, created_by, updated_at
                          FROM clinical_svc.report_recipient_group WHERE id = ?
                        """).param(id).query(GROUP_MAPPER).optional();
    }

    public void insertReportRecipientGroup(ReportRecipientGroup g) {
        jdbc.sql("""
                        INSERT INTO clinical_svc.report_recipient_group
                            (id, code, name, description, enabled, created_by)
                        VALUES (:id, :code, :name, :description, :enabled, :actor)
                        """)
                .param("id", g.id()).param("code", g.code()).param("name", g.name())
                .param("description", g.description()).param("enabled", g.enabled())
                .param("actor", g.createdBy()).update();
    }

    public boolean updateReportRecipientGroup(UUID id, String name, String description,
                                              boolean enabled, long version) {
        return jdbc.sql("""
                        UPDATE clinical_svc.report_recipient_group
                           SET name = :name, description = :description, enabled = :enabled,
                               version = version + 1, updated_at = now()
                         WHERE id = :id AND version = :version
                        """)
                .param("id", id).param("name", name).param("description", description)
                .param("enabled", enabled).param("version", version).update() > 0;
    }

    public List<ReportRecipientGroupMember> listReportRecipientGroupMembers(UUID groupId) {
        return jdbc.sql("""
                        SELECT id, group_id, email, display_name, enabled, created_at
                          FROM clinical_svc.report_recipient_group_member
                         WHERE group_id = ?
                         ORDER BY enabled DESC, email
                        """).param(groupId).query(GROUP_MEMBER_MAPPER).list();
    }

    /** 有效成员邮箱（投递时实时解析，新增/停用成员下一次投递自动生效）。 */
    public List<String> listEnabledReportRecipientEmails(UUID groupId) {
        return jdbc.sql("""
                        SELECT email
                          FROM clinical_svc.report_recipient_group_member
                         WHERE group_id = ? AND enabled
                         ORDER BY email
                        """).param(groupId).query(String.class).list();
    }

    public void deleteReportRecipientGroupMembers(UUID groupId) {
        jdbc.sql("DELETE FROM clinical_svc.report_recipient_group_member WHERE group_id = ?")
                .param(groupId).update();
    }

    public void insertReportRecipientGroupMember(ReportRecipientGroupMember m) {
        jdbc.sql("""
                        INSERT INTO clinical_svc.report_recipient_group_member
                            (id, group_id, email, display_name, enabled)
                        VALUES (:id, :groupId, :email, :displayName, :enabled)
                        """)
                .param("id", m.id()).param("groupId", m.groupId()).param("email", m.email())
                .param("displayName", m.displayName()).param("enabled", m.enabled())
                .update();
    }

    /** 投递占位：同一订阅同一天唯一，返回 false 表示已存在（幂等）。 */
    public boolean insertReportDeliveryIfAbsent(UUID id, UUID subscriptionId, String reportCode,
                                                LocalDate reportDate, String payload) {
        return jdbc.sql("""
                        INSERT INTO clinical_svc.report_delivery
                            (id, subscription_id, report_code, report_date, status, payload)
                        VALUES (:id, :subId, :code, :day, 'PENDING', CAST(:payload AS jsonb))
                        ON CONFLICT (subscription_id, report_date, report_code) DO NOTHING
                        """)
                .param("id", id).param("subId", subscriptionId).param("code", reportCode)
                .param("day", reportDate).param("payload", payload)
                .update() > 0;
    }

    public Optional<ReportDelivery> findReportDelivery(UUID subscriptionId, String reportCode,
                                                       LocalDate reportDate) {
        return jdbc.sql("""
                        SELECT d.id, d.subscription_id, s.name AS subscription_name,
                               d.report_code, d.report_date, d.status, d.attempts,
                               d.last_error, d.created_at, d.sent_at
                          FROM clinical_svc.report_delivery d
                          JOIN clinical_svc.report_subscription s ON s.id = d.subscription_id
                         WHERE d.subscription_id = :subId AND d.report_code = :code
                           AND d.report_date = :day
                        """)
                .param("subId", subscriptionId).param("code", reportCode).param("day", reportDate)
                .query(DELIVERY_MAPPER).optional();
    }

    /** 待投递/失败可重试的投递（attempts 小于上限），按创建时间升序。 */
    public List<ReportDelivery> listRetryableReportDeliveries(int maxAttempts, int limit) {
        return jdbc.sql("""
                        SELECT d.id, d.subscription_id, s.name AS subscription_name,
                               d.report_code, d.report_date, d.status, d.attempts,
                               d.last_error, d.created_at, d.sent_at
                          FROM clinical_svc.report_delivery d
                          JOIN clinical_svc.report_subscription s ON s.id = d.subscription_id
                         WHERE d.status IN ('PENDING', 'FAILED')
                           AND d.attempts < :maxAttempts
                           AND s.enabled
                         ORDER BY d.created_at
                         LIMIT :limit
                        """)
                .param("maxAttempts", maxAttempts).param("limit", limit)
                .query(DELIVERY_MAPPER).list();
    }

    public List<ReportDelivery> listReportDeliveries(String status, int limit) {
        String statusKey = (status == null || status.isBlank()) ? null : status.trim().toUpperCase();
        return jdbc.sql("""
                        SELECT d.id, d.subscription_id, s.name AS subscription_name,
                               d.report_code, d.report_date, d.status, d.attempts,
                               d.last_error, d.created_at, d.sent_at
                          FROM clinical_svc.report_delivery d
                          JOIN clinical_svc.report_subscription s ON s.id = d.subscription_id
                         WHERE (CAST(:status AS text) IS NULL OR d.status = CAST(:status AS text))
                         ORDER BY d.report_date DESC, d.created_at DESC
                         LIMIT :limit
                        """)
                .param("status", statusKey).param("limit", limit)
                .query(DELIVERY_MAPPER).list();
    }

    /** 回写投递结果：成功置 SENT 并记录 sent_at；失败累加 attempts 与错误。 */
    public void markReportDelivery(UUID id, String status, int attempts, String lastError) {
        jdbc.sql("""
                        UPDATE clinical_svc.report_delivery
                           SET status = :status, attempts = :attempts, last_error = :error,
                               sent_at = CASE WHEN :status = 'SENT' THEN now() ELSE sent_at END,
                               updated_at = now()
                         WHERE id = :id
                        """)
                .param("id", id).param("status", status).param("attempts", attempts)
                .param("error", lastError).update();
    }

    /** 读取已固化的投递内容（重试时必须复用首次生成的正文，避免口径漂移）。 */
    public String reportDeliveryPayload(UUID id) {
        return jdbc.sql("SELECT payload::text FROM clinical_svc.report_delivery WHERE id = ?")
                .param(id).query(String.class).optional().orElse(null);
    }

    // ---------------------------------------------------------------- 电子票据推送（G21 / G24）

    private static final RowMapper<ReceiptPush> RECEIPT_PUSH_MAPPER = (rs, i) ->
            new ReceiptPush(
                    rs.getObject("id", UUID.class),
                    rs.getObject("receipt_id", UUID.class),
                    rs.getString("platform"), rs.getString("vendor"),
                    rs.getString("message_type"), rs.getString("template_version"),
                    rs.getObject("original_push_id", UUID.class),
                    rs.getString("status"), rs.getInt("attempts"),
                    rs.getString("external_no"), rs.getString("last_error"),
                    rs.getTimestamp("pushed_at") == null ? null
                            : rs.getTimestamp("pushed_at").toInstant());

    private static final RowMapper<ReceiptPushLedger> RECEIPT_PUSH_LEDGER_MAPPER = (rs, i) ->
            new ReceiptPushLedger(
                    rs.getObject("id", UUID.class),
                    rs.getObject("receipt_id", UUID.class),
                    rs.getString("receipt_no"), rs.getBigDecimal("amount"),
                    rs.getString("platform"), rs.getString("vendor"),
                    rs.getString("message_type"), rs.getString("template_version"),
                    rs.getString("status"), rs.getInt("attempts"),
                    rs.getString("external_no"), rs.getString("last_error"),
                    rs.getTimestamp("created_at").toInstant(),
                    rs.getTimestamp("pushed_at") == null ? null
                            : rs.getTimestamp("pushed_at").toInstant());

    private static final String RECEIPT_PUSH_COLUMNS = """
            id, receipt_id, platform, vendor, message_type, template_version, original_push_id,
            status, attempts, external_no, last_error, pushed_at
            """;

    /** 推送占位：同一票据 + 平台 + 报文类型唯一，返回 false 表示已存在（幂等）。 */
    public boolean insertReceiptPushIfAbsent(UUID id, UUID receiptId, String platform,
                                             String vendor, String messageType,
                                             String templateVersion, UUID originalPushId,
                                             String payload) {
        return jdbc.sql("""
                        INSERT INTO clinical_svc.receipt_push
                            (id, receipt_id, platform, vendor, message_type, template_version,
                             original_push_id, status, payload)
                        VALUES (:id, :receiptId, :platform, :vendor, :messageType, :templateVersion,
                                CAST(:originalPushId AS uuid), 'PENDING', CAST(:payload AS jsonb))
                        ON CONFLICT (receipt_id, platform, message_type) DO NOTHING
                        """)
                .param("id", id).param("receiptId", receiptId).param("platform", platform)
                .param("vendor", vendor).param("messageType", messageType)
                .param("templateVersion", templateVersion).param("originalPushId", originalPushId)
                .param("payload", payload)
                .update() > 0;
    }

    /** 默认查开具报文（历史调用方无需感知红冲）。 */
    public Optional<ReceiptPush> findReceiptPush(UUID receiptId, String platform) {
        return findReceiptPush(receiptId, platform, ReceiptPush.ISSUE);
    }

    public Optional<ReceiptPush> findReceiptPush(UUID receiptId, String platform,
                                                 String messageType) {
        return jdbc.sql("SELECT " + RECEIPT_PUSH_COLUMNS + """
                          FROM clinical_svc.receipt_push
                         WHERE receipt_id = :receiptId AND platform = :platform
                           AND message_type = :messageType
                        """)
                .param("receiptId", receiptId).param("platform", platform)
                .param("messageType", messageType)
                .query(RECEIPT_PUSH_MAPPER).optional();
    }

    public List<ReceiptPush> listReceiptPushes(UUID receiptId) {
        return jdbc.sql("SELECT " + RECEIPT_PUSH_COLUMNS + """
                          FROM clinical_svc.receipt_push
                         WHERE receipt_id = ?
                         ORDER BY created_at DESC
                        """).param(receiptId).query(RECEIPT_PUSH_MAPPER).list();
    }

    /**
     * 对账台账：按推送时间区间 + 状态/报文类型/厂商筛选。
     *
     * <p>JOIN 票据表取票号与金额快照：推送记录本身只存报文，财务对账需要票面信息。
     */
    public List<ReceiptPushLedger> listReceiptPushLedger(OffsetDateTime from, OffsetDateTime to,
                                                         String status, String messageType,
                                                         String vendor, int limit) {
        return jdbc.sql("""
                        SELECT p.id, p.receipt_id, r.receipt_no, r.amount, p.platform, p.vendor,
                               p.message_type, p.template_version, p.status, p.attempts,
                               p.external_no, p.last_error, p.created_at, p.pushed_at
                          FROM clinical_svc.receipt_push p
                          JOIN clinical_svc.charge_receipt r ON r.id = p.receipt_id
                         WHERE p.created_at >= :fromTs AND p.created_at < :toTs
                           AND (CAST(:status AS text) IS NULL OR p.status = CAST(:status AS text))
                           AND (CAST(:messageType AS text) IS NULL
                                OR p.message_type = CAST(:messageType AS text))
                           AND (CAST(:vendor AS text) IS NULL OR p.vendor = CAST(:vendor AS text))
                         ORDER BY p.created_at DESC
                         LIMIT :limit
                        """)
                .param("fromTs", from).param("toTs", to)
                .param("status", status).param("messageType", messageType)
                .param("vendor", vendor).param("limit", limit)
                .query(RECEIPT_PUSH_LEDGER_MAPPER).list();
    }

    /** 期间汇总：按状态计数；金额只统计开具且成功的报文，红冲不计入实收。 */
    public ReceiptPushSummary summarizeReceiptPushes(OffsetDateTime from, OffsetDateTime to) {
        return jdbc.sql("""
                        SELECT count(*) AS total,
                               count(*) FILTER (WHERE p.status = 'SENT') AS sent,
                               count(*) FILTER (WHERE p.status = 'FAILED') AS failed,
                               count(*) FILTER (WHERE p.status = 'PENDING') AS pending,
                               count(*) FILTER (WHERE p.message_type = 'RED_FLUSH')
                                   AS red_flush_count,
                               COALESCE(sum(r.amount) FILTER (
                                   WHERE p.message_type = 'ISSUE' AND p.status = 'SENT'), 0)
                                   AS sent_amount
                          FROM clinical_svc.receipt_push p
                          JOIN clinical_svc.charge_receipt r ON r.id = p.receipt_id
                         WHERE p.created_at >= :fromTs AND p.created_at < :toTs
                        """)
                .param("fromTs", from).param("toTs", to)
                .query((rs, i) -> new ReceiptPushSummary(
                        rs.getLong("total"), rs.getLong("sent"), rs.getLong("failed"),
                        rs.getLong("pending"), rs.getLong("red_flush_count"),
                        rs.getBigDecimal("sent_amount"))).single();
    }

    public void markReceiptPush(UUID id, String status, int attempts, String externalNo,
                                String lastError) {
        jdbc.sql("""
                        UPDATE clinical_svc.receipt_push
                           SET status = :status, attempts = :attempts,
                               external_no = :externalNo, last_error = :error,
                               pushed_at = CASE WHEN :status = 'SENT' THEN now() ELSE pushed_at END,
                               updated_at = now()
                         WHERE id = :id
                        """)
                .param("id", id).param("status", status).param("attempts", attempts)
                .param("externalNo", externalNo).param("error", lastError).update();
    }

    public String receiptPushPayload(UUID id) {
        return jdbc.sql("SELECT payload::text FROM clinical_svc.receipt_push WHERE id = ?")
                .param(id).query(String.class).optional().orElse(null);
    }

    /** 门诊日结：按科室分账（当日已收）。 */
    public List<DeptPaidTotal> outpatientPaidByDept(OffsetDateTime from, OffsetDateTime to) {
        return jdbc.sql("""
                        SELECT d.id AS dept_id, d.dept_code, d.name AS dept_name,
                               count(DISTINCT v.id) AS visits,
                               COALESCE(sum(c.total_amount), 0) AS amount
                          FROM clinical_svc.charge c
                          JOIN clinical_svc.visit v ON v.id = c.visit_id
                          JOIN clinical_svc.md_dept d ON d.id = v.dept_id
                         WHERE v.visit_type = 'OUTPATIENT'
                           AND c.status = 'PAID'
                           AND c.paid_at >= :fromTs AND c.paid_at < :toTs
                         GROUP BY d.id, d.dept_code, d.name
                         ORDER BY amount DESC, d.dept_code
                        """)
                .param("fromTs", from).param("toTs", to)
                .query((rs, i) -> new DeptPaidTotal(
                        rs.getObject("dept_id", UUID.class), rs.getString("dept_code"),
                        rs.getString("dept_name"), rs.getInt("visits"), rs.getBigDecimal("amount")))
                .list();
    }

    /** 门诊日结：按支付方式分账（当日已收）。 */
    public List<MethodPaidTotal> outpatientPaidByMethod(OffsetDateTime from, OffsetDateTime to) {
        return jdbc.sql("""
                        SELECT COALESCE(c.payment_method, 'UNKNOWN') AS method,
                               count(*) AS charges,
                               COALESCE(sum(c.total_amount), 0) AS amount
                          FROM clinical_svc.charge c
                          JOIN clinical_svc.visit v ON v.id = c.visit_id
                         WHERE v.visit_type = 'OUTPATIENT'
                           AND c.status = 'PAID'
                           AND c.paid_at >= :fromTs AND c.paid_at < :toTs
                         GROUP BY 1
                         ORDER BY amount DESC
                        """)
                .param("fromTs", from).param("toTs", to)
                .query((rs, i) -> new MethodPaidTotal(
                        rs.getString("method"), rs.getInt("charges"), rs.getBigDecimal("amount")))
                .list();
    }

    public void insertCharge(Charge c) {
        jdbc.sql("""
                        INSERT INTO clinical_svc.charge
                            (id, charge_no, visit_id, total_amount, status, version)
                        VALUES (:id, :chargeNo, :visitId, :total, :status, 0)
                        """)
                .param("id", c.id()).param("chargeNo", c.chargeNo())
                .param("visitId", c.visitId()).param("total", c.totalAmount())
                .param("status", c.status()).update();
    }

    /**
     * 费用明细（幂等键 source_key）：
     * 一次性医嘱 {@code ORDER:{orderId}}、按日计费 {@code DAILY:{orderId}:{date}}。
     */
    public boolean insertChargeItem(UUID id, UUID chargeId, UUID orderId, String sourceKey,
                                    String itemCode, String itemName, String category, BigDecimal amount) {
        return jdbc.sql("""
                        INSERT INTO clinical_svc.charge_item
                            (id, charge_id, order_id, source_key, item_code, item_name, category, amount)
                        VALUES (:id, :chargeId, CAST(:orderId AS uuid), :sourceKey, :itemCode,
                                :itemName, :category, :amount)
                        ON CONFLICT (source_key) DO NOTHING
                        """)
                .param("id", id).param("chargeId", chargeId).param("orderId", orderId)
                .param("sourceKey", sourceKey)
                .param("itemCode", itemCode).param("itemName", itemName)
                .param("category", category).param("amount", amount).update() > 0;
    }

    /**
     * 结算：累加金额并置为 PAID（CAS：仅 UNPAID + 版本一致）。
     *
     * <p>用"增量累加"而不是"覆盖写总额"，避免并发下两笔结算互相覆盖；
     * 幂等由调用方先查 charge.status 保证。
     */
    public boolean markChargePaid(UUID chargeId, BigDecimal addAmount, String paymentMethod,
                                  long expectedVersion) {
        return jdbc.sql("""
                        UPDATE clinical_svc.charge
                           SET total_amount = total_amount + :add, status = 'PAID',
                               payment_method = :method, paid_at = now(),
                               version = version + 1, updated_at = now()
                         WHERE id = :id AND version = :version AND status = 'UNPAID'
                        """)
                .param("add", addAmount).param("method", paymentMethod)
                .param("id", chargeId).param("version", expectedVersion).update() > 0;
    }

    public BigDecimal sumChargedItems(UUID chargeId) {
        return jdbc.sql("SELECT COALESCE(sum(amount), 0) FROM clinical_svc.charge_item WHERE charge_id = ?")
                .param(chargeId).query(BigDecimal.class).single();
    }

    /** 某就诊某日按日计费（DAILY）的金额合计，用于费用看板对账。 */
    public BigDecimal sumDailyBilled(UUID visitId, LocalDate date) {
        return jdbc.sql("""
                        SELECT COALESCE(sum(ci.amount), 0)
                          FROM clinical_svc.charge_item ci
                          JOIN clinical_svc.charge c ON c.id = ci.charge_id
                         WHERE c.visit_id = :visitId AND ci.source_key LIKE 'DAILY:%:' || :date
                        """)
                .param("visitId", visitId).param("date", date.toString())
                .query(BigDecimal.class).single();
    }

    /** 挂号费明细（没有对应医嘱，order_id 为 NULL，source_key=REG:{id}）。 */
    public void insertChargeItemWithoutOrder(UUID id, UUID chargeId, String itemCode,
                                             String itemName, String category, BigDecimal amount) {
        jdbc.sql("""
                        INSERT INTO clinical_svc.charge_item
                            (id, charge_id, order_id, source_key, item_code, item_name, category, amount)
                        VALUES (:id, :chargeId, NULL, :sourceKey, :itemCode, :itemName, :category, :amount)
                        """)
                .param("id", id).param("chargeId", chargeId).param("sourceKey", "REG:" + id)
                .param("itemCode", itemCode)
                .param("itemName", itemName).param("category", category)
                .param("amount", amount).update();
    }

    /** 未结算状态下累加收费单金额（按日计费使用；CAS 防并发丢更新）。 */
    public boolean addChargeAmount(UUID chargeId, BigDecimal addAmount, long expectedVersion) {
        return jdbc.sql("""
                        UPDATE clinical_svc.charge
                           SET total_amount = total_amount + :add, version = version + 1, updated_at = now()
                         WHERE id = :id AND version = :version AND status = 'UNPAID'
                        """)
                .param("add", addAmount).param("id", chargeId)
                .param("version", expectedVersion).update() > 0;
    }

    /** 收费明细（order_id 可能为空，调用方按可空处理）。 */
    public List<Object[]> listChargeItems(UUID chargeId) {
        return jdbc.sql("""
                        SELECT order_id, item_code, item_name, category, amount
                          FROM clinical_svc.charge_item WHERE charge_id = ? ORDER BY created_at
                        """)
                .param(chargeId)
                .query((rs, i) -> new Object[]{
                        rs.getObject("order_id", UUID.class),
                        rs.getString("item_code"),
                        rs.getString("item_name"),
                        rs.getString("category"),
                        rs.getBigDecimal("amount")})
                .list();
    }

    // ---------------------------------------------------------------- 住院（ADT）

    private static final RowMapper<Ward> WARD_MAPPER = (rs, i) -> new Ward(
            rs.getObject("id", UUID.class), rs.getString("code"), rs.getString("name"),
            rs.getObject("dept_id", UUID.class), rs.getBoolean("enabled"));

    private static final RowMapper<Bed> BED_MAPPER = (rs, i) -> new Bed(
            rs.getObject("id", UUID.class), rs.getObject("ward_id", UUID.class),
            rs.getString("bed_no"), rs.getString("status"), rs.getLong("version"));

    private static final RowMapper<Admission> ADMISSION_MAPPER = (rs, i) -> new Admission(
            rs.getObject("id", UUID.class), rs.getString("admission_no"),
            rs.getObject("visit_id", UUID.class), rs.getObject("patient_id", UUID.class),
            rs.getString("patient_no"), rs.getObject("ward_id", UUID.class),
            rs.getObject("bed_id", UUID.class), rs.getString("attending_doctor"),
            rs.getString("status"), rs.getTimestamp("admitted_at").toInstant(),
            rs.getTimestamp("discharged_at") == null ? null : rs.getTimestamp("discharged_at").toInstant(),
            rs.getLong("version"));

    public Optional<Ward> findWard(UUID id) {
        return jdbc.sql("SELECT id, code, name, dept_id, enabled FROM clinical_svc.ward WHERE id = ?")
                .param(id).query(WARD_MAPPER).optional();
    }

    public Optional<Ward> findWardByCode(String code) {
        return jdbc.sql("SELECT id, code, name, dept_id, enabled FROM clinical_svc.ward WHERE code = ?")
                .param(code).query(WARD_MAPPER).optional();
    }

    /** 启用病区列表（控制台选择病区用；不做业务判断，仅按编码排序）。 */
    public List<Ward> listWards() {
        return jdbc.sql("""
                        SELECT id, code, name, dept_id, enabled FROM clinical_svc.ward
                         WHERE enabled ORDER BY code
                        """).query(WARD_MAPPER).list();
    }

    public void insertWard(Ward w) {
        jdbc.sql("""
                        INSERT INTO clinical_svc.ward (id, code, name, dept_id, enabled, version)
                        VALUES (:id, :code, :name, :deptId, true, 0)
                        """)
                .param("id", w.id()).param("code", w.code())
                .param("name", w.name()).param("deptId", w.deptId()).update();
    }

    public void insertBed(UUID id, UUID wardId, String bedNo) {
        jdbc.sql("INSERT INTO clinical_svc.bed (id, ward_id, bed_no, status, version) VALUES (?, ?, ?, 'FREE', 0)")
                .param(id).param(wardId).param(bedNo).update();
    }

    public List<Bed> listBeds(UUID wardId) {
        return jdbc.sql("""
                        SELECT id, ward_id, bed_no, status, version FROM clinical_svc.bed
                         WHERE ward_id = ? ORDER BY bed_no
                        """).param(wardId).query(BED_MAPPER).list();
    }

    public Optional<Bed> findBed(UUID id) {
        return jdbc.sql("SELECT id, ward_id, bed_no, status, version FROM clinical_svc.bed WHERE id = ?")
                .param(id).query(BED_MAPPER).optional();
    }

    /** 加行锁读取床位；必须在事务内调用。 */
    public Optional<Bed> lockBed(UUID id) {
        return jdbc.sql("SELECT id, ward_id, bed_no, status, version FROM clinical_svc.bed WHERE id = ? FOR UPDATE")
                .param(id).query(BED_MAPPER).optional();
    }

    /** 占床（CAS：仅 FREE 可占）。 */
    public boolean occupyBed(UUID bedId, long expectedVersion) {
        return jdbc.sql("""
                        UPDATE clinical_svc.bed
                           SET status = 'OCCUPIED', version = version + 1, updated_at = now()
                         WHERE id = :id AND version = :version AND status = 'FREE'
                        """)
                .param("id", bedId).param("version", expectedVersion).update() > 0;
    }

    /** 腾床（CAS：仅 OCCUPIED 可释放）。 */
    public boolean freeBed(UUID bedId, long expectedVersion) {
        return jdbc.sql("""
                        UPDATE clinical_svc.bed
                           SET status = 'FREE', version = version + 1, updated_at = now()
                         WHERE id = :id AND version = :version AND status = 'OCCUPIED'
                        """)
                .param("id", bedId).param("version", expectedVersion).update() > 0;
    }

    public String nextAdmissionNo() {
        Long seq = jdbc.sql("SELECT nextval(CAST(:seq AS regclass))")
                .param("seq", "clinical_svc.admission_no_seq").query(Long.class).single();
        return "A" + BusinessTime.today().format(DateTimeFormatter.BASIC_ISO_DATE)
                + String.format("%06d", seq);
    }

    public void insertAdmission(Admission a) {
        jdbc.sql("""
                        INSERT INTO clinical_svc.admission
                            (id, admission_no, visit_id, patient_id, patient_no, ward_id, bed_id,
                             attending_doctor, status, version)
                        VALUES (:id, :admissionNo, :visitId, :patientId, :patientNo, :wardId, :bedId,
                                :doctor, :status, 0)
                        """)
                .param("id", a.id()).param("admissionNo", a.admissionNo())
                .param("visitId", a.visitId()).param("patientId", a.patientId())
                .param("patientNo", a.patientNo()).param("wardId", a.wardId())
                .param("bedId", a.bedId()).param("doctor", a.attendingDoctor())
                .param("status", a.status()).update();
    }

    public Optional<Admission> findAdmission(UUID id) {
        return jdbc.sql("""
                        SELECT id, admission_no, visit_id, patient_id, patient_no, ward_id, bed_id,
                               attending_doctor, status, admitted_at, discharged_at, version
                          FROM clinical_svc.admission WHERE id = ?
                        """).param(id).query(ADMISSION_MAPPER).optional();
    }

    /** 加行锁读取住院登记；必须在事务内调用。 */
    public Optional<Admission> lockAdmission(UUID id) {
        return jdbc.sql("""
                        SELECT id, admission_no, visit_id, patient_id, patient_no, ward_id, bed_id,
                               attending_doctor, status, admitted_at, discharged_at, version
                          FROM clinical_svc.admission WHERE id = ? FOR UPDATE
                        """).param(id).query(ADMISSION_MAPPER).optional();
    }

    /**
     * 加行锁读取该就诊的在院登记；必须在事务内调用。
     *
     * <p>预交金的一切写操作（存入/退还/结算抵扣）都必须先拿这把锁，
     * 否则"读余额→校验→插负流水"在并发下会把余额做成负数。
     * 锁顺序约定：visit → admission → bed（与 settle/dailyBill/discharge 保持一致）。
     */
    public Optional<Admission> lockActiveAdmissionByVisit(UUID visitId) {
        return jdbc.sql("""
                        SELECT id, admission_no, visit_id, patient_id, patient_no, ward_id, bed_id,
                               attending_doctor, status, admitted_at, discharged_at, version
                          FROM clinical_svc.admission
                         WHERE visit_id = :visitId AND status IN ('ADMITTED', 'IN_HOSPITAL')
                           FOR UPDATE
                        """).param("visitId", visitId).query(ADMISSION_MAPPER).optional();
    }

    public Optional<Admission> findActiveAdmissionByVisit(UUID visitId) {
        return jdbc.sql("""
                        SELECT id, admission_no, visit_id, patient_id, patient_no, ward_id, bed_id,
                               attending_doctor, status, admitted_at, discharged_at, version
                          FROM clinical_svc.admission
                         WHERE visit_id = :visitId AND status IN ('ADMITTED', 'IN_HOSPITAL')
                        """).param("visitId", visitId).query(ADMISSION_MAPPER).optional();
    }

    /**
     * 按就诊号查最新一次住院（含已出院）。
     *
     * <p>供病历域做书写时限校验：出院后补录病历仍需拿到入院时间。
     */
    public Optional<Admission> findLatestAdmissionByVisit(UUID visitId) {
        return jdbc.sql("""
                        SELECT id, admission_no, visit_id, patient_id, patient_no, ward_id, bed_id,
                               attending_doctor, status, admitted_at, discharged_at, version
                          FROM clinical_svc.admission
                         WHERE visit_id = :visitId
                         ORDER BY admitted_at DESC
                         LIMIT 1
                        """).param("visitId", visitId).query(ADMISSION_MAPPER).optional();
    }

    public Optional<Admission> findActiveAdmissionByPatient(UUID patientId) {
        return jdbc.sql("""
                        SELECT id, admission_no, visit_id, patient_id, patient_no, ward_id, bed_id,
                               attending_doctor, status, admitted_at, discharged_at, version
                          FROM clinical_svc.admission
                         WHERE patient_id = :patientId AND status IN ('ADMITTED', 'IN_HOSPITAL')
                        """).param("patientId", patientId).query(ADMISSION_MAPPER).optional();
    }

    /** 更新床位（CAS）；用于分床/转床。首次分床同时把 ADMITTED 提升为 IN_HOSPITAL。 */
    public boolean updateAdmissionBed(UUID id, UUID bedId, long expectedVersion) {
        return jdbc.sql("""
                        UPDATE clinical_svc.admission
                           SET bed_id = :bedId,
                               status = CASE WHEN status = 'ADMITTED' THEN 'IN_HOSPITAL' ELSE status END,
                               version = version + 1, updated_at = now()
                         WHERE id = :id AND version = :version AND status IN ('ADMITTED', 'IN_HOSPITAL')
                        """)
                .param("bedId", bedId).param("id", id).param("version", expectedVersion).update() > 0;
    }

    /**
     * 转科同时换床：病区与床位一起更新（CAS）。
     *
     * <p>只改 bed_id 会让 ADT 视图继续显示原病区，护理站按 ward_id 取在院列表时
     * 会把患者留在旧病区；转科必须整条搬过去。
     */
    public boolean updateAdmissionWardAndBed(UUID id, UUID wardId, UUID bedId,
                                             long expectedVersion) {
        return jdbc.sql("""
                        UPDATE clinical_svc.admission
                           SET ward_id = :wardId, bed_id = :bedId,
                               status = CASE WHEN status = 'ADMITTED' THEN 'IN_HOSPITAL' ELSE status END,
                               version = version + 1, updated_at = now()
                         WHERE id = :id AND version = :version AND status IN ('ADMITTED', 'IN_HOSPITAL')
                        """)
                .param("wardId", wardId).param("bedId", bedId)
                .param("id", id).param("version", expectedVersion).update() > 0;
    }

    /** 住院状态迁移（CAS）。 */
    public boolean updateAdmissionStatus(UUID id, String expected, String next, long expectedVersion) {
        return jdbc.sql("""
                        UPDATE clinical_svc.admission
                           SET status = :next, version = version + 1, updated_at = now(),
                               discharged_at = CASE WHEN :next = 'DISCHARGED' THEN now() ELSE discharged_at END
                         WHERE id = :id AND status = :expected AND version = :version
                        """)
                .param("next", next).param("id", id)
                .param("expected", expected).param("version", expectedVersion).update() > 0;
    }

    public void logBedTransfer(UUID admissionId, UUID fromBedId, UUID toBedId,
                               String reason, String operator) {
        jdbc.sql("""
                        INSERT INTO clinical_svc.bed_transfer
                            (admission_id, from_bed_id, to_bed_id, reason, operator)
                        VALUES (:admissionId, :fromBedId, :toBedId, :reason, :operator)
                        """)
                .param("admissionId", admissionId).param("fromBedId", fromBedId)
                .param("toBedId", toBedId).param("reason", reason)
                .param("operator", operator).update();
    }

    /** 在院登记（ADMITTED + IN_HOSPITAL）；wardId 为空时返回全院。 */
    public List<Admission> listActiveAdmissions(UUID wardId) {
        if (wardId == null) {
            return jdbc.sql("""
                            SELECT id, admission_no, visit_id, patient_id, patient_no, ward_id, bed_id,
                                   attending_doctor, status, admitted_at, discharged_at, version
                              FROM clinical_svc.admission
                             WHERE status IN ('ADMITTED', 'IN_HOSPITAL')
                             ORDER BY admitted_at DESC
                            """).query(ADMISSION_MAPPER).list();
        }
        return jdbc.sql("""
                        SELECT id, admission_no, visit_id, patient_id, patient_no, ward_id, bed_id,
                               attending_doctor, status, admitted_at, discharged_at, version
                          FROM clinical_svc.admission
                         WHERE ward_id = :wardId AND status IN ('ADMITTED', 'IN_HOSPITAL')
                         ORDER BY admitted_at DESC
                        """).param("wardId", wardId).query(ADMISSION_MAPPER).list();
    }

    public List<Admission> listAdmissions(UUID wardId, String status) {
        if (wardId == null) {
            return jdbc.sql("""
                            SELECT id, admission_no, visit_id, patient_id, patient_no, ward_id, bed_id,
                                   attending_doctor, status, admitted_at, discharged_at, version
                              FROM clinical_svc.admission WHERE status = :status
                             ORDER BY admitted_at DESC
                            """).param("status", status).query(ADMISSION_MAPPER).list();
        }
        return jdbc.sql("""
                        SELECT id, admission_no, visit_id, patient_id, patient_no, ward_id, bed_id,
                               attending_doctor, status, admitted_at, discharged_at, version
                          FROM clinical_svc.admission
                         WHERE ward_id = :wardId AND status = :status
                         ORDER BY admitted_at DESC
                        """).param("wardId", wardId).param("status", status)
                .query(ADMISSION_MAPPER).list();
    }

    // ---------------------------------------------------------------- 预交金

    private static final RowMapper<Deposit> DEPOSIT_MAPPER = (rs, i) -> new Deposit(
            rs.getObject("id", UUID.class), rs.getObject("admission_id", UUID.class),
            rs.getBigDecimal("amount"), rs.getString("kind"), rs.getString("method"),
            rs.getString("operator"), rs.getString("remark"),
            rs.getTimestamp("created_at").toInstant());

    public void insertDeposit(UUID id, UUID admissionId, BigDecimal signedAmount, String kind,
                              String method, String operator, String remark) {
        jdbc.sql("""
                        INSERT INTO clinical_svc.deposit
                            (id, admission_id, amount, kind, method, operator, remark)
                        VALUES (:id, :admissionId, :amount, :kind, :method, :operator, :remark)
                        """)
                .param("id", id).param("admissionId", admissionId)
                .param("amount", signedAmount).param("kind", kind)
                .param("method", method).param("operator", operator)
                .param("remark", remark).update();
    }

    /** 预交金余额（正数=可用余额）。 */
    public BigDecimal depositBalance(UUID admissionId) {
        return jdbc.sql("SELECT COALESCE(sum(amount), 0) FROM clinical_svc.deposit WHERE admission_id = ?")
                .param(admissionId).query(BigDecimal.class).single();
    }

    public List<Deposit> listDeposits(UUID admissionId) {
        return jdbc.sql("""
                        SELECT id, admission_id, amount, kind, method, operator, remark, created_at
                          FROM clinical_svc.deposit WHERE admission_id = ? ORDER BY created_at
                        """).param(admissionId).query(DEPOSIT_MAPPER).list();
    }

    // ---------------------------------------------------------------- 护理执行

    private static final RowMapper<NursingExecution> NURSING_MAPPER = (rs, i) -> new NursingExecution(
            rs.getObject("id", UUID.class), rs.getObject("admission_id", UUID.class),
            rs.getObject("visit_id", UUID.class), rs.getObject("order_id", UUID.class),
            rs.getDate("exec_date").toLocalDate(), rs.getString("status"),
            rs.getString("executor"), rs.getString("note"),
            rs.getTimestamp("created_at").toInstant());

    public void insertNursingExecution(NursingExecution e) {
        jdbc.sql("""
                        INSERT INTO clinical_svc.nursing_execution
                            (id, admission_id, visit_id, order_id, exec_date, status, executor, note)
                        VALUES (:id, :admissionId, :visitId, :orderId, :execDate, :status, :executor, :note)
                        """)
                .param("id", e.id()).param("admissionId", e.admissionId())
                .param("visitId", e.visitId()).param("orderId", e.orderId())
                .param("execDate", e.execDate()).param("status", e.status())
                .param("executor", e.executor()).param("note", e.note()).update();
    }

    public Optional<NursingExecution> findNursingExecution(UUID orderId, LocalDate date) {
        return jdbc.sql("""
                        SELECT ne.id, ne.admission_id, ne.visit_id, ne.order_id, ne.exec_date,
                               coalesce(nc.to_status, ne.status) AS status,
                               ne.executor, ne.note, ne.created_at
                          FROM clinical_svc.nursing_execution ne
                          LEFT JOIN clinical_svc.nursing_execution_correction nc
                                 ON nc.execution_id = ne.id
                         WHERE ne.order_id = :orderId AND ne.exec_date = :date
                        """)
                .param("orderId", orderId).param("date", date)
                .query(NURSING_MAPPER).optional();
    }

    /** 某就诊的护理执行记录；date 为空时返回全部。 */
    public List<NursingExecution> listNursingExecutions(UUID visitId, LocalDate date) {
        if (date == null) {
            return jdbc.sql("""
                            SELECT ne.id, ne.admission_id, ne.visit_id, ne.order_id, ne.exec_date,
                                   coalesce(nc.to_status, ne.status) AS status,
                                   ne.executor, ne.note, ne.created_at
                              FROM clinical_svc.nursing_execution ne
                              LEFT JOIN clinical_svc.nursing_execution_correction nc
                                     ON nc.execution_id = ne.id
                             WHERE ne.visit_id = ? ORDER BY ne.exec_date DESC, ne.created_at DESC
                            """).param(visitId).query(NURSING_MAPPER).list();
        }
        return jdbc.sql("""
                        SELECT ne.id, ne.admission_id, ne.visit_id, ne.order_id, ne.exec_date,
                               coalesce(nc.to_status, ne.status) AS status,
                               ne.executor, ne.note, ne.created_at
                          FROM clinical_svc.nursing_execution ne
                          LEFT JOIN clinical_svc.nursing_execution_correction nc
                                 ON nc.execution_id = ne.id
                         WHERE ne.visit_id = :visitId AND ne.exec_date = :date
                         ORDER BY ne.created_at DESC
                        """)
                .param("visitId", visitId).param("date", date)
                .query(NURSING_MAPPER).list();
    }

    /** 按主键读执行记录；返回的 status 为"有效状态"（已更正则取更正后状态）。 */
    public Optional<NursingExecution> findNursingExecutionById(UUID executionId) {
        return jdbc.sql("""
                        SELECT ne.id, ne.admission_id, ne.visit_id, ne.order_id, ne.exec_date,
                               coalesce(nc.to_status, ne.status) AS status,
                               ne.executor, ne.note, ne.created_at
                          FROM clinical_svc.nursing_execution ne
                          LEFT JOIN clinical_svc.nursing_execution_correction nc
                                 ON nc.execution_id = ne.id
                         WHERE ne.id = ?
                        """)
                .param(executionId).query(NURSING_MAPPER).optional();
    }

    /** 该执行记录是否已更正过（唯一键 ux_nec_one_correction 的友好前置判断）。 */
    public boolean existsNursingCorrection(UUID executionId) {
        Long n = jdbc.sql(
                        "SELECT count(*) FROM clinical_svc.nursing_execution_correction WHERE execution_id = ?")
                .param(executionId).query(Long.class).single();
        return n != null && n > 0;
    }

    public void insertNursingCorrection(NursingExecutionCorrection c) {
        jdbc.sql("""
                        INSERT INTO clinical_svc.nursing_execution_correction
                            (id, execution_id, admission_id, visit_id, order_id, exec_date,
                             from_status, to_status, reason, corrected_by)
                        VALUES (:id, :executionId, :admissionId, :visitId, :orderId, :execDate,
                                :fromStatus, :toStatus, :reason, :correctedBy)
                        """)
                .param("id", c.id()).param("executionId", c.executionId())
                .param("admissionId", c.admissionId()).param("visitId", c.visitId())
                .param("orderId", c.orderId()).param("execDate", c.execDate())
                .param("fromStatus", c.fromStatus()).param("toStatus", c.toStatus())
                .param("reason", c.reason()).param("correctedBy", c.correctedBy()).update();
    }

    // ---------------------------------------------------------------- 药房发药费用（N2 收尾）

    private static final RowMapper<PharmacyChargeLink> PHARMACY_CHARGE_MAPPER = (rs, i) ->
            new PharmacyChargeLink(
                    rs.getObject("dispense_item_id", UUID.class),
                    rs.getObject("visit_id", UUID.class),
                    rs.getObject("charge_id", UUID.class),
                    rs.getObject("charge_item_id", UUID.class),
                    rs.getString("drug_code"), rs.getString("drug_name"),
                    rs.getBigDecimal("amount"), rs.getBigDecimal("refunded_amount"),
                    rs.getBigDecimal("refund_pending_amount"), rs.getBigDecimal("refund_done_amount"));

    private static final String PHARMACY_CHARGE_COLUMNS =
            "dispense_item_id, visit_id, charge_id, charge_item_id, drug_code, drug_name, "
                    + "amount, refunded_amount, refund_pending_amount, refund_done_amount";

    public Optional<PharmacyChargeLink> findPharmacyCharge(UUID dispenseItemId) {
        return jdbc.sql("SELECT " + PHARMACY_CHARGE_COLUMNS
                        + " FROM clinical_svc.pharmacy_charge WHERE dispense_item_id = ?")
                .param(dispenseItemId).query(PHARMACY_CHARGE_MAPPER).optional();
    }

    /** 加行锁读取药房费用关联；退药冲减必须走这里（并发退同一明细时串行）。 */
    public Optional<PharmacyChargeLink> lockPharmacyCharge(UUID dispenseItemId) {
        return jdbc.sql("SELECT " + PHARMACY_CHARGE_COLUMNS
                        + " FROM clinical_svc.pharmacy_charge WHERE dispense_item_id = ? FOR UPDATE")
                .param(dispenseItemId).query(PHARMACY_CHARGE_MAPPER).optional();
    }

    public void insertPharmacyCharge(UUID dispenseItemId, UUID visitId, UUID chargeId,
                                     UUID chargeItemId, String drugCode, String drugName,
                                     BigDecimal amount) {
        jdbc.sql("""
                        INSERT INTO clinical_svc.pharmacy_charge
                            (dispense_item_id, visit_id, charge_id, charge_item_id,
                             drug_code, drug_name, amount)
                        VALUES (:dispenseItemId, :visitId, :chargeId, :chargeItemId,
                                :drugCode, :drugName, :amount)
                        ON CONFLICT (dispense_item_id) DO NOTHING
                        """)
                .param("dispenseItemId", dispenseItemId).param("visitId", visitId)
                .param("chargeId", chargeId).param("chargeItemId", chargeItemId)
                .param("drugCode", drugCode).param("drugName", drugName)
                .param("amount", amount).update();
    }

    /** 未结算收费单上的退药冲减：累计已冲减金额（数据库约束保证不超过原金额）。 */
    public boolean addPharmacyRefundApplied(UUID dispenseItemId, BigDecimal delta) {
        return jdbc.sql("""
                        UPDATE clinical_svc.pharmacy_charge
                           SET refunded_amount = refunded_amount + :delta, updated_at = now()
                         WHERE dispense_item_id = :id
                           AND refunded_amount + refund_pending_amount + refund_done_amount + :delta <= amount
                        """)
                .param("delta", delta).param("id", dispenseItemId).update() > 0;
    }

    /** 已结算收费单：登记待财务退费金额（不改账）。 */
    public boolean addPharmacyRefundPending(UUID dispenseItemId, BigDecimal delta) {
        return jdbc.sql("""
                        UPDATE clinical_svc.pharmacy_charge
                           SET refund_pending_amount = refund_pending_amount + :delta, updated_at = now()
                         WHERE dispense_item_id = :id
                           AND refunded_amount + refund_pending_amount + refund_done_amount + :delta <= amount
                        """)
                .param("delta", delta).param("id", dispenseItemId).update() > 0;
    }

    /** 财务确认退费：待退费 → 已退费（金额不得超过待退费余额）。 */
    public boolean completePharmacyRefund(UUID dispenseItemId, BigDecimal amount) {
        return jdbc.sql("""
                        UPDATE clinical_svc.pharmacy_charge
                           SET refund_pending_amount = refund_pending_amount - :amount,
                               refund_done_amount = refund_done_amount + :amount,
                               updated_at = now()
                         WHERE dispense_item_id = :id AND refund_pending_amount >= :amount
                        """)
                .param("amount", amount).param("id", dispenseItemId).update() > 0;
    }

    /** 待财务退费的药房费用（按更新时间倒序）。 */
    public List<PharmacyChargeLink> listPendingPharmacyRefunds(int limit) {
        return jdbc.sql("SELECT " + PHARMACY_CHARGE_COLUMNS
                        + " FROM clinical_svc.pharmacy_charge"
                        + " WHERE refund_pending_amount > 0 ORDER BY updated_at DESC LIMIT ?")
                .param(limit).query(PHARMACY_CHARGE_MAPPER).list();
    }

    // ---------------------------------------------------------------- 医嘱退药冲减（B3 / V23）

    private static final RowMapper<OrderRefund> ORDER_REFUND_MAPPER = (rs, i) -> new OrderRefund(
            rs.getObject("id", UUID.class), rs.getObject("order_id", UUID.class),
            rs.getObject("dispense_item_id", UUID.class), rs.getObject("visit_id", UUID.class),
            rs.getBigDecimal("amount"), rs.getBigDecimal("applied_amount"),
            rs.getBigDecimal("pending_amount"), rs.getBigDecimal("done_amount"));

    private static final String ORDER_REFUND_COLUMNS =
            "id, order_id, dispense_item_id, visit_id, amount, applied_amount, pending_amount, done_amount";

    /** 加行锁读取退药冲减登记；同一发药明细的并发退药在这里串行。 */
    public Optional<OrderRefund> lockOrderRefund(UUID dispenseItemId) {
        return jdbc.sql("SELECT " + ORDER_REFUND_COLUMNS
                        + " FROM clinical_svc.order_refund WHERE dispense_item_id = ? FOR UPDATE")
                .param(dispenseItemId).query(ORDER_REFUND_MAPPER).optional();
    }

    /** 首次退药登记（幂等键 dispense_item_id，并发同一明细只留一条）。 */
    public void insertOrderRefund(UUID id, UUID orderId, UUID dispenseItemId, UUID visitId,
                                  BigDecimal amount, String reason, String actor,
                                  LocalDate supplyDate) {
        jdbc.sql("""
                        INSERT INTO clinical_svc.order_refund
                            (id, order_id, dispense_item_id, visit_id, amount, reason, created_by,
                             supply_date)
                        VALUES (:id, :orderId, :dispenseItemId, :visitId, :amount, :reason, :actor,
                                :supplyDate)
                        ON CONFLICT (dispense_item_id) DO NOTHING
                        """)
                .param("id", id).param("orderId", orderId).param("dispenseItemId", dispenseItemId)
                .param("visitId", visitId).param("amount", amount)
                .param("reason", reason).param("actor", actor)
                .param("supplyDate", supplyDate).update();
    }

    /** 未结算已直接冲减的金额合计：结算时从该医嘱金额中扣除。 */
    public BigDecimal sumAppliedOrderRefund(UUID orderId) {
        BigDecimal sum = jdbc.sql("""
                        SELECT coalesce(sum(applied_amount), 0)
                          FROM clinical_svc.order_refund WHERE order_id = ?
                        """)
                .param(orderId).query(BigDecimal.class).single();
        return sum == null ? BigDecimal.ZERO : sum;
    }

    /**
     * 某医嘱（长嘱按供药日期）已处理的退费合计：applied + pending + done。
     *
     * <p>P1-9：退费上限此前只按"单条发药明细金额"封顶，同一医嘱多条明细累计仍可能超过
     * 医嘱计费金额（药品价与收费项目价漂移时会超收退回，最终把 charge.total_amount 冲成负数）。
     * 这里给出"该医嘱已处理总额"，供服务层做**医嘱级**封顶。
     *
     * @param supplyDate 长嘱传供药日期（只统计当天），临时医嘱传 null（统计整单）
     */
    public BigDecimal sumOrderRefundHandled(UUID orderId, LocalDate supplyDate) {
        // 不用 "CAST(:x AS date) IS NULL OR ..." 的单条 SQL：JdbcClient 传 null 时
        // JDBC 无法推断参数类型（PG 报 "can't infer the SQL type"），会把正常退药打成 500。
        String sql = supplyDate == null
                ? """
                  SELECT coalesce(sum(applied_amount + pending_amount + done_amount), 0)
                    FROM clinical_svc.order_refund WHERE order_id = ?
                  """
                : """
                  SELECT coalesce(sum(applied_amount + pending_amount + done_amount), 0)
                    FROM clinical_svc.order_refund WHERE order_id = ? AND supply_date = ?
                  """;
        JdbcClient.StatementSpec spec = jdbc.sql(sql).param(orderId);
        if (supplyDate != null) {
            spec = spec.param(supplyDate);
        }
        BigDecimal sum = spec.query(BigDecimal.class).single();
        return sum == null ? BigDecimal.ZERO : sum;
    }

    /**
     * 某长嘱某日已登记但尚未消耗的冲减金额（按日计费时从当日金额里扣除）。
     *
     * <p>长嘱退药走的是"先记 applied、当日计费时扣减"这条路径：如果这里漏扣，
     * applied 就永远没有消费方，退药等于只回库存不退钱（P0-1 的根因）。
     */
    public BigDecimal sumAppliedOrderRefund(UUID orderId, LocalDate supplyDate) {
        BigDecimal sum = jdbc.sql("""
                        SELECT coalesce(sum(applied_amount), 0)
                          FROM clinical_svc.order_refund
                         WHERE order_id = ? AND supply_date = ?
                        """)
                .param(orderId).param(supplyDate).query(BigDecimal.class).single();
        return sum == null ? BigDecimal.ZERO : sum;
    }

    /** 按按日计费的幂等键定位收费单（长嘱退药冲减用）。 */
    public Optional<UUID> findChargeIdByDailyItem(UUID orderId, LocalDate supplyDate) {
        return jdbc.sql("SELECT charge_id FROM clinical_svc.charge_item WHERE source_key = ?")
                .param("DAILY:" + orderId + ":" + supplyDate).query(UUID.class).optional();
    }

    /** 某长嘱某日的按日计费明细（红冲需要原金额与项目信息）。 */
    public Optional<DailyCharge> findDailyCharge(UUID orderId, LocalDate serviceDate) {
        return jdbc.sql("""
                        SELECT charge_id, amount, item_code, item_name, category
                          FROM clinical_svc.charge_item WHERE source_key = ?
                        """)
                .param("DAILY:" + orderId + ":" + serviceDate)
                .query((rs, rowNum) -> new DailyCharge(
                        rs.getObject("charge_id", UUID.class), rs.getBigDecimal("amount"),
                        rs.getString("item_code"), rs.getString("item_name"),
                        rs.getString("category")))
                .optional();
    }

    /** 该日是否已经红冲过（幂等键 {@code DAILY_RETURN:{orderId}:{date}}）。 */
    public boolean existsDailyReturn(UUID orderId, LocalDate serviceDate) {
        Long n = jdbc.sql("""
                        SELECT count(*) FROM clinical_svc.charge_item WHERE source_key = ?
                        """).param("DAILY_RETURN:" + orderId + ":" + serviceDate)
                .query(Long.class).single();
        return n != null && n > 0;
    }

    /** 按日计费明细（红冲时按原金额取负）。 */
    public record DailyCharge(UUID chargeId, BigDecimal amount, String itemCode,
                              String itemName, String category) {
    }

    /** 该医嘱已生成的费用明细所属收费单（未结算时为空）。 */
    public Optional<UUID> findChargeIdByOrderItem(UUID orderId) {
        return jdbc.sql("SELECT charge_id FROM clinical_svc.charge_item WHERE source_key = ?")
                .param("ORDER:" + orderId).query(UUID.class).optional();
    }

    /** 未结算：直接冲减（结算时扣减该医嘱金额）。 */
    public boolean addOrderRefundApplied(UUID dispenseItemId, BigDecimal delta) {
        return jdbc.sql("""
                        UPDATE clinical_svc.order_refund
                           SET applied_amount = applied_amount + :delta, updated_at = now()
                         WHERE dispense_item_id = :id
                           AND applied_amount + pending_amount + done_amount + :delta <= amount
                        """)
                .param("delta", delta).param("id", dispenseItemId).update() > 0;
    }

    /** 已结算：登记待财务退费（不改账）。 */
    public boolean addOrderRefundPending(UUID dispenseItemId, BigDecimal delta) {
        return jdbc.sql("""
                        UPDATE clinical_svc.order_refund
                           SET pending_amount = pending_amount + :delta, updated_at = now()
                         WHERE dispense_item_id = :id
                           AND applied_amount + pending_amount + done_amount + :delta <= amount
                        """)
                .param("delta", delta).param("id", dispenseItemId).update() > 0;
    }

    /** 财务确认退费：待退费 → 已退费。 */
    public boolean completeOrderRefund(UUID dispenseItemId, BigDecimal amount) {
        return jdbc.sql("""
                        UPDATE clinical_svc.order_refund
                           SET pending_amount = pending_amount - :amount,
                               done_amount = done_amount + :amount,
                               updated_at = now()
                         WHERE dispense_item_id = :id AND pending_amount >= :amount
                        """)
                .param("amount", amount).param("id", dispenseItemId).update() > 0;
    }

    /** 待财务退费的医嘱退药（财务工作台）。 */
    public List<OrderRefund> listPendingOrderRefunds(int limit) {
        return jdbc.sql("SELECT " + ORDER_REFUND_COLUMNS
                        + " FROM clinical_svc.order_refund"
                        + " WHERE pending_amount > 0 ORDER BY updated_at DESC LIMIT ?")
                .param(limit).query(ORDER_REFUND_MAPPER).list();
    }

    /**
     * 全额退药且尚未计费：医嘱直接作废，避免结算时又按原金额收一次。
     *
     * <p>只在"没有 ORDER: 费用明细"时调用；已计费的场景走冲减/待退费。
     */
    public boolean cancelUnchargedOrder(UUID orderId) {
        return jdbc.sql("""
                        UPDATE clinical_svc.visit_order
                           SET status = 'CANCELLED', version = version + 1, updated_at = now()
                         WHERE id = :orderId AND status = 'PLACED'
                        """)
                .param("orderId", orderId).update() > 0;
    }

    // ---------------------------------------------------------------- 病区备药对账（V25）

    private static final RowMapper<DailyDispenseRequest> DISPENSE_REQUEST_MAPPER = (rs, i) ->
            new DailyDispenseRequest(
                    rs.getObject("id", UUID.class), rs.getObject("order_id", UUID.class),
                    rs.getObject("visit_id", UUID.class), rs.getDate("supply_date").toLocalDate(),
                    rs.getInt("quantity"), rs.getObject("prescription_id", UUID.class),
                    rs.getString("prescription_no"), rs.getString("status"),
                    rs.getString("error_message"));

    private static final RowMapper<WardMedReceipt> WARD_RECEIPT_MAPPER = (rs, i) ->
            new WardMedReceipt(
                    rs.getObject("id", UUID.class), rs.getObject("request_id", UUID.class),
                    rs.getObject("order_id", UUID.class), rs.getObject("visit_id", UUID.class),
                    rs.getObject("dispense_id", UUID.class), rs.getDate("supply_date").toLocalDate(),
                    rs.getInt("quantity"), rs.getInt("returned_quantity"), rs.getString("status"),
                    rs.getString("received_by"));

    /**
     * 请领台账 upsert（{@code order_id + supply_date} 幂等）。
     *
     * <p>失败重试不能把已成功的处方信息抹掉：若库里已有 prescription_id，
     * 后续 FAILED 只记录错误、不降级状态（药房其实已经建单，只是本次响应失败）。
     */
    public void upsertDailyDispenseRequest(UUID id, UUID orderId, UUID visitId, LocalDate supplyDate,
                                           int quantity, UUID prescriptionId, String prescriptionNo,
                                           String status, String error, String actor) {
        jdbc.sql("""
                        INSERT INTO clinical_svc.daily_dispense_request
                            (id, order_id, visit_id, supply_date, quantity, prescription_id,
                             prescription_no, status, error_message, created_by)
                        VALUES (:id, :orderId, :visitId, :supplyDate, :quantity,
                                CAST(:prescriptionId AS uuid), CAST(:prescriptionNo AS text),
                                :status, CAST(:error AS text), :actor)
                        ON CONFLICT (order_id, supply_date) DO UPDATE SET
                            quantity = EXCLUDED.quantity,
                            prescription_id = coalesce(EXCLUDED.prescription_id,
                                                       daily_dispense_request.prescription_id),
                            prescription_no = coalesce(EXCLUDED.prescription_no,
                                                       daily_dispense_request.prescription_no),
                            status = CASE WHEN EXCLUDED.status = 'REQUESTED'
                                               OR daily_dispense_request.prescription_id IS NOT NULL
                                          THEN 'REQUESTED' ELSE 'FAILED' END,
                            error_message = CASE WHEN EXCLUDED.status = 'REQUESTED'
                                                      OR daily_dispense_request.prescription_id IS NOT NULL
                                                 THEN NULL ELSE EXCLUDED.error_message END,
                            updated_at = now()
                        """)
                .param("id", id).param("orderId", orderId).param("visitId", visitId)
                .param("supplyDate", supplyDate).param("quantity", quantity)
                .param("prescriptionId", prescriptionId).param("prescriptionNo", prescriptionNo)
                .param("status", status).param("error", error).param("actor", actor)
                .update();
    }

    public Optional<DailyDispenseRequest> findDailyDispenseRequest(UUID orderId, LocalDate supplyDate) {
        return jdbc.sql("""
                        SELECT id, order_id, visit_id, supply_date, quantity, prescription_id,
                               prescription_no, status, error_message
                          FROM clinical_svc.daily_dispense_request
                         WHERE order_id = ? AND supply_date = ?
                        """).param(orderId).param(supplyDate).query(DISPENSE_REQUEST_MAPPER).optional();
    }

    public List<DailyDispenseRequest> listDailyDispenseRequests(UUID visitId, LocalDate supplyDate) {
        return jdbc.sql("""
                        SELECT id, order_id, visit_id, supply_date, quantity, prescription_id,
                               prescription_no, status, error_message
                          FROM clinical_svc.daily_dispense_request
                         WHERE visit_id = ? AND supply_date = ?
                         ORDER BY created_at
                        """).param(visitId).param(supplyDate).query(DISPENSE_REQUEST_MAPPER).list();
    }

    /** 病区收药（按 dispense_id 幂等；并发重复提交只留一条）。 */
    public void insertWardReceipt(UUID id, UUID requestId, UUID orderId, UUID visitId,
                                  UUID dispenseId, LocalDate supplyDate, int quantity,
                                  String receivedBy) {
        jdbc.sql("""
                        INSERT INTO clinical_svc.ward_med_receipt
                            (id, request_id, order_id, visit_id, dispense_id, supply_date,
                             quantity, received_by)
                        VALUES (:id, CAST(:requestId AS uuid), :orderId, :visitId, :dispenseId,
                                :supplyDate, :quantity, :receivedBy)
                        ON CONFLICT (dispense_id) DO NOTHING
                        """)
                .param("id", id).param("requestId", requestId).param("orderId", orderId)
                .param("visitId", visitId).param("dispenseId", dispenseId)
                .param("supplyDate", supplyDate).param("quantity", quantity)
                .param("receivedBy", receivedBy).update();
    }

    public Optional<WardMedReceipt> findWardReceiptByDispense(UUID dispenseId) {
        return jdbc.sql("""
                        SELECT id, request_id, order_id, visit_id, dispense_id, supply_date,
                               quantity, returned_quantity, status, received_by
                          FROM clinical_svc.ward_med_receipt WHERE dispense_id = ?
                        """).param(dispenseId).query(WARD_RECEIPT_MAPPER).optional();
    }

    public List<WardMedReceipt> listWardReceipts(UUID visitId, LocalDate supplyDate) {
        return jdbc.sql("""
                        SELECT id, request_id, order_id, visit_id, dispense_id, supply_date,
                               quantity, returned_quantity, status, received_by
                          FROM clinical_svc.ward_med_receipt
                         WHERE visit_id = ? AND supply_date = ?
                         ORDER BY received_at
                        """).param(visitId).param(supplyDate).query(WARD_RECEIPT_MAPPER).list();
    }

    /**
     * 该医嘱当日已累计收药数量。
     *
     * <p>收药是按发药单登记的多行记录，上限必须按"当日合计"判断，不能只看单行；
     * 并发窗口由 V27 触发器（order+date 事务级 advisory lock）兜底。
     */
    public int sumWardReceiptQuantity(UUID orderId, LocalDate supplyDate) {
        Integer sum = jdbc.sql("""
                        SELECT coalesce(sum(quantity), 0) FROM clinical_svc.ward_med_receipt
                         WHERE order_id = ? AND supply_date = ?
                        """).param(orderId).param(supplyDate).query(Integer.class).single();
        return sum == null ? 0 : sum;
    }

    // ---------------------------------------------------------------- 病区二级库存与退药（V26）

    private static final RowMapper<WardMedReturn> WARD_RETURN_MAPPER = (rs, i) ->
            new WardMedReturn(rs.getObject("id", UUID.class),
                    rs.getObject("receipt_id", UUID.class), rs.getObject("order_id", UUID.class),
                    rs.getObject("visit_id", UUID.class), rs.getDate("supply_date").toLocalDate(),
                    rs.getInt("quantity"), rs.getString("reason"), rs.getString("returned_by"));

    private static final RowMapper<WardStockTxn> WARD_STOCK_TXN_MAPPER = (rs, i) ->
            new WardStockTxn(rs.getObject("id", UUID.class), rs.getObject("visit_id", UUID.class),
                    rs.getObject("order_id", UUID.class), rs.getObject("receipt_id", UUID.class),
                    rs.getObject("return_id", UUID.class), rs.getString("drug_code"),
                    rs.getString("drug_name"), rs.getInt("quantity"), rs.getString("txn_type"),
                    rs.getDate("supply_date").toLocalDate(), rs.getString("operator"),
                    rs.getTimestamp("created_at").toInstant());

    /** 加行锁读取收药记录；退药必须在事务内先锁再加，避免并发超退。 */
    public Optional<WardMedReceipt> lockWardReceipt(UUID receiptId) {
        return jdbc.sql("""
                        SELECT id, request_id, order_id, visit_id, dispense_id, supply_date,
                               quantity, returned_quantity, status, received_by
                          FROM clinical_svc.ward_med_receipt WHERE id = ? FOR UPDATE
                        """).param(receiptId).query(WARD_RECEIPT_MAPPER).optional();
    }

    public Optional<WardMedReturn> findWardReturn(UUID returnId) {
        return jdbc.sql("""
                        SELECT id, receipt_id, order_id, visit_id, supply_date, quantity, reason,
                               returned_by
                          FROM clinical_svc.ward_med_return WHERE id = ?
                        """).param(returnId).query(WARD_RETURN_MAPPER).optional();
    }

    public void insertWardReturn(WardMedReturn ret) {
        jdbc.sql("""
                        INSERT INTO clinical_svc.ward_med_return
                            (id, receipt_id, order_id, visit_id, supply_date, quantity, reason,
                             returned_by)
                        VALUES (:id, :receiptId, :orderId, :visitId, :supplyDate, :quantity,
                                CAST(:reason AS text), :returnedBy)
                        """)
                .param("id", ret.id()).param("receiptId", ret.receiptId())
                .param("orderId", ret.orderId()).param("visitId", ret.visitId())
                .param("supplyDate", ret.supplyDate()).param("quantity", ret.quantity())
                .param("reason", ret.reason()).param("returnedBy", ret.returnedBy()).update();
    }

    /**
     * 累加已退数量（CAS）：{@code returned_quantity + q <= quantity} 是数据库级守卫，
     * 即使应用层校验被并发绕过也不会超退。
     */
    public boolean addReturnedQuantity(UUID receiptId, int quantity) {
        return jdbc.sql("""
                        UPDATE clinical_svc.ward_med_receipt
                           SET returned_quantity = returned_quantity + :qty,
                               status = CASE WHEN returned_quantity + :qty >= quantity
                                             THEN 'RETURNED' ELSE 'RECEIVED' END,
                               updated_at = now()
                         WHERE id = :id AND returned_quantity + :qty <= quantity
                        """)
                .param("id", receiptId).param("qty", quantity).update() > 0;
    }

    /** 库存流水（append-only）：同一收药/退药重复写入由唯一索引挡掉。 */
    public void insertWardStockTxn(WardStockTxn txn) {
        jdbc.sql("""
                        INSERT INTO clinical_svc.ward_stock_txn
                            (id, visit_id, order_id, receipt_id, return_id, drug_code, drug_name,
                             quantity, txn_type, supply_date, operator)
                        VALUES (:id, :visitId, :orderId, CAST(:receiptId AS uuid),
                                CAST(:returnId AS uuid), :drugCode, :drugName, :quantity,
                                :txnType, :supplyDate, :operator)
                        ON CONFLICT DO NOTHING
                        """)
                .param("id", txn.id()).param("visitId", txn.visitId())
                .param("orderId", txn.orderId()).param("receiptId", txn.receiptId())
                .param("returnId", txn.returnId()).param("drugCode", txn.drugCode())
                .param("drugName", txn.drugName()).param("quantity", txn.quantity())
                .param("txnType", txn.txnType()).param("supplyDate", txn.supplyDate())
                .param("operator", txn.operator()).update();
    }

    /** 病区二级库存余额（按药品汇总；余额为 0 的药品不返回）。 */
    public List<WardStockBalance> listWardStockBalances(UUID visitId) {
        return jdbc.sql("""
                        SELECT drug_code,
                               max(drug_name) AS drug_name,
                               sum(CASE WHEN txn_type = 'IN' THEN quantity ELSE 0 END) AS received,
                               sum(CASE WHEN txn_type = 'RETURN' THEN quantity ELSE 0 END) AS returned
                          FROM clinical_svc.ward_stock_txn
                         WHERE visit_id = ?
                         GROUP BY drug_code
                        HAVING sum(CASE WHEN txn_type = 'IN' THEN quantity ELSE 0 END)
                             - sum(CASE WHEN txn_type = 'RETURN' THEN quantity ELSE 0 END) <> 0
                         ORDER BY drug_code
                        """).param(visitId).query((rs, i) -> new WardStockBalance(
                        rs.getString("drug_code"), rs.getString("drug_name"),
                        rs.getInt("received"), rs.getInt("returned"))).list();
    }

    /** 最近的库存流水（默认 200 条），供病区盘点与问题追溯。 */
    public List<WardStockTxn> listWardStockTxns(UUID visitId) {
        return jdbc.sql("""
                        SELECT id, visit_id, order_id, receipt_id, return_id, drug_code, drug_name,
                               quantity, txn_type, supply_date, operator, created_at
                          FROM clinical_svc.ward_stock_txn
                         WHERE visit_id = ?
                         ORDER BY created_at DESC, id DESC
                         LIMIT 200
                        """).param(visitId).query(WARD_STOCK_TXN_MAPPER).list();
    }

    // ---------------------------------------------------------------- 手术麻醉（V15）

    private static final RowMapper<OperationCatalog> OPERATION_MAPPER = (rs, i) -> new OperationCatalog(
            rs.getString("code"), rs.getString("name"), rs.getString("category"),
            rs.getBoolean("enabled"), rs.getLong("version"));

    private static final RowMapper<SurgeryCase> SURGERY_MAPPER = (rs, i) -> new SurgeryCase(
            rs.getObject("id", UUID.class), rs.getString("case_no"),
            rs.getObject("visit_id", UUID.class), rs.getObject("admission_id", UUID.class),
            rs.getObject("patient_id", UUID.class), rs.getString("operation_code"),
            rs.getString("operation_name"), instantOf(rs, "planned_at"),
            instantOf(rs, "started_at"), instantOf(rs, "ended_at"),
            rs.getString("surgeon"), rs.getString("assistant"),
            rs.getString("anesthesia_type"), rs.getString("asa_grade"),
            rs.getString("incision_level"), rs.getString("status"),
            rs.getString("cancel_reason"), rs.getString("created_by"),
            rs.getLong("version"), instantOf(rs, "created_at"));

    private static java.time.Instant instantOf(java.sql.ResultSet rs, String column)
            throws java.sql.SQLException {
        java.sql.Timestamp ts = rs.getTimestamp(column);
        return ts == null ? null : ts.toInstant();
    }

    /** Instant → OffsetDateTime（JdbcClient 绑定 timestamptz 用 UTC 偏移，不做本地时区换算）。 */
    private static java.time.OffsetDateTime ts(java.time.Instant instant) {
        return instant == null ? null : java.time.OffsetDateTime.ofInstant(
                instant, java.time.ZoneOffset.UTC);
    }

    /** 手术编码搜索（编码前缀或名称包含；只搜启用项）。 */
    public List<OperationCatalog> searchOperationCatalog(String keyword, int limit) {
        String like = keyword == null || keyword.isBlank() ? null : "%" + keyword.trim() + "%";
        return jdbc.sql("""
                        SELECT code, name, category, enabled, version
                          FROM clinical_svc.operation_catalog
                         WHERE enabled
                           AND (CAST(:like AS text) IS NULL
                                OR code ILIKE CAST(:like AS text)
                                OR name ILIKE CAST(:like AS text))
                         ORDER BY code
                         LIMIT :limit
                        """)
                .param("like", like).param("limit", limit)
                .query(OPERATION_MAPPER).list();
    }

    public Optional<OperationCatalog> findOperation(String code) {
        return jdbc.sql("""
                        SELECT code, name, category, enabled, version
                          FROM clinical_svc.operation_catalog WHERE code = ?
                        """).param(code).query(OPERATION_MAPPER).optional();
    }

    /** 手术号 OPYYYYMMDD######。 */
    public String nextSurgeryCaseNo() {
        Long seq = jdbc.sql("SELECT nextval(CAST(:seq AS regclass))")
                .param("seq", "clinical_svc.surgery_case_no_seq").query(Long.class).single();
        return "OP" + BusinessTime.today().format(DateTimeFormatter.BASIC_ISO_DATE)
                + String.format("%06d", seq);
    }

    public void insertSurgeryCase(SurgeryCase c) {
        jdbc.sql("""
                        INSERT INTO clinical_svc.surgery_case
                            (id, case_no, visit_id, admission_id, patient_id, operation_code,
                             operation_name, planned_at, started_at, ended_at, surgeon, assistant,
                             anesthesia_type, asa_grade, incision_level, status, created_by, version)
                        VALUES (:id, :caseNo, :visitId, CAST(:admissionId AS uuid), :patientId,
                                :operationCode, :operationName, CAST(:plannedAt AS timestamptz),
                                CAST(:startedAt AS timestamptz), CAST(:endedAt AS timestamptz),
                                :surgeon, :assistant, :anesthesiaType, :asaGrade,
                                :incisionLevel, :status, :createdBy, 0)
                        """)
                .param("id", c.id()).param("caseNo", c.caseNo()).param("visitId", c.visitId())
                .param("admissionId", c.admissionId()).param("patientId", c.patientId())
                .param("operationCode", c.operationCode())
                .param("operationName", c.operationName()).param("plannedAt", ts(c.plannedAt()))
                .param("startedAt", ts(c.startedAt())).param("endedAt", ts(c.endedAt()))
                .param("surgeon", c.surgeon()).param("assistant", c.assistant())
                .param("anesthesiaType", c.anesthesiaType()).param("asaGrade", c.asaGrade())
                .param("incisionLevel", c.incisionLevel()).param("status", c.status())
                .param("createdBy", c.createdBy()).update();
    }

    public List<SurgeryCase> listSurgeryCases(UUID visitId) {
        return jdbc.sql("""
                        SELECT * FROM clinical_svc.surgery_case
                         WHERE visit_id = ? ORDER BY created_at
                        """).param(visitId).query(SURGERY_MAPPER).list();
    }

    /** 只取已完成手术（病案首页与 DRG 分组的取数口径）。 */
    public List<SurgeryCase> listCompletedSurgeryCases(UUID visitId) {
        return jdbc.sql("""
                        SELECT * FROM clinical_svc.surgery_case
                         WHERE visit_id = ? AND status = 'COMPLETED'
                         ORDER BY started_at, created_at
                        """).param(visitId).query(SURGERY_MAPPER).list();
    }

    public Optional<SurgeryCase> lockSurgeryCase(UUID id) {
        return jdbc.sql("SELECT * FROM clinical_svc.surgery_case WHERE id = ? FOR UPDATE")
                .param(id).query(SURGERY_MAPPER).optional();
    }

    /** 只读查询手术记录（排台/麻醉/护理的归属校验用）。 */
    public Optional<SurgeryCase> findSurgeryCase(UUID id) {
        return jdbc.sql("SELECT * FROM clinical_svc.surgery_case WHERE id = ?")
                .param(id).query(SURGERY_MAPPER).optional();
    }

    /**
     * 按手术病例查所属就诊的科室（行级权限校验用）。
     *
     * <p>返回 empty 有两种可能：病例不存在，或病例所属就诊没有科室。
     * 两者对调用方是同一个结论——不可见，因此不区分。
     */
    public Optional<UUID> findVisitDepartmentByCaseId(UUID caseId) {
        return jdbc.sql("""
                        SELECT v.dept_id
                          FROM clinical_svc.surgery_case c
                          JOIN clinical_svc.visit v ON v.id = c.visit_id
                         WHERE c.id = ?
                        """)
                .param(caseId)
                .query((rs, i) -> rs.getObject("dept_id", UUID.class))
                .optional();
    }

    /**
     * 按住院登记查所属就诊的科室（行级权限校验用）。
     *
     * <p>住院处的所有操作都以 admissionId 为入口，但科室归属在 visit 上，
     * 这里一次 JOIN 取到，避免"先查登记再查就诊"两次往返。
     */
    public Optional<UUID> findVisitDepartmentByAdmissionId(UUID admissionId) {
        return jdbc.sql("""
                        SELECT v.dept_id
                          FROM clinical_svc.admission a
                          JOIN clinical_svc.visit v ON v.id = a.visit_id
                         WHERE a.id = ?
                        """)
                .param(admissionId)
                .query((rs, i) -> rs.getObject("dept_id", UUID.class))
                .optional();
    }

    /** 病区所属科室（病区本身就归属某个科室，床位/在院列表按此判定可见性）。 */
    public Optional<UUID> findWardDepartment(UUID wardId) {
        return jdbc.sql("SELECT dept_id FROM clinical_svc.ward WHERE id = ?")
                .param(wardId)
                .query((rs, i) -> rs.getObject("dept_id", UUID.class))
                .optional();
    }

    /** 开始手术（CAS：仅 PLANNED）。 */
    public boolean startSurgery(UUID id, java.time.Instant startedAt, long expectedVersion) {
        return jdbc.sql("""
                        UPDATE clinical_svc.surgery_case
                           SET status = 'IN_PROGRESS', started_at = CAST(:startedAt AS timestamptz),
                               version = version + 1, updated_at = now()
                         WHERE id = :id AND status = 'PLANNED' AND version = :version
                        """)
                .param("startedAt", ts(startedAt)).param("id", id)
                .param("version", expectedVersion).update() > 0;
    }

    /** 完成手术（CAS：仅 IN_PROGRESS）。 */
    public boolean completeSurgery(UUID id, java.time.Instant endedAt, long expectedVersion) {
        return jdbc.sql("""
                        UPDATE clinical_svc.surgery_case
                           SET status = 'COMPLETED', ended_at = CAST(:endedAt AS timestamptz),
                               version = version + 1, updated_at = now()
                         WHERE id = :id AND status = 'IN_PROGRESS' AND version = :version
                        """)
                .param("endedAt", ts(endedAt)).param("id", id)
                .param("version", expectedVersion).update() > 0;
    }

    /** 取消手术（CAS：非终态可取消，必须写原因）。 */
    public boolean cancelSurgery(UUID id, String reason, long expectedVersion) {
        return jdbc.sql("""
                        UPDATE clinical_svc.surgery_case
                           SET status = 'CANCELLED', cancel_reason = :reason,
                               version = version + 1, updated_at = now()
                         WHERE id = :id AND status IN ('PLANNED', 'IN_PROGRESS')
                           AND version = :version
                        """)
                .param("reason", reason).param("id", id)
                .param("version", expectedVersion).update() > 0;
    }

    // ---------------------------------------------------------------- 手术间/排台/麻醉/术中护理（V16）

    private static final RowMapper<OperatingRoom> ROOM_MAPPER = (rs, i) -> new OperatingRoom(
            rs.getObject("id", UUID.class), rs.getString("code"), rs.getString("name"),
            rs.getObject("dept_id", UUID.class), rs.getString("location"), rs.getString("status"),
            rs.getLong("version"), instantOf(rs, "created_at"));

    private static final RowMapper<SurgerySchedule> SCHEDULE_MAPPER = (rs, i) -> new SurgerySchedule(
            rs.getObject("id", UUID.class), rs.getObject("surgery_case_id", UUID.class),
            rs.getObject("room_id", UUID.class), instantOf(rs, "planned_start"),
            instantOf(rs, "planned_end"), rs.getInt("sequence"), rs.getString("status"),
            rs.getString("cancel_reason"), rs.getString("created_by"), rs.getLong("version"),
            instantOf(rs, "created_at"));

    private static final RowMapper<AnesthesiaRecord> ANESTHESIA_MAPPER =
            (rs, i) -> new AnesthesiaRecord(
                    rs.getObject("id", UUID.class), rs.getObject("surgery_case_id", UUID.class),
                    rs.getString("method"), rs.getString("asa_grade"),
                    rs.getString("anesthesiologist"), rs.getString("airway"),
                    instantOf(rs, "started_at"), instantOf(rs, "ended_at"), rs.getString("notes"),
                    rs.getString("status"), rs.getString("signed_by"),
                    instantOf(rs, "signed_at"), rs.getString("created_by"),
                    rs.getLong("version"), instantOf(rs, "created_at"));

    private static final RowMapper<AnesthesiaVital> VITAL_MAPPER = (rs, i) -> new AnesthesiaVital(
            rs.getObject("id", UUID.class), rs.getObject("record_id", UUID.class),
            instantOf(rs, "recorded_at"),
            rs.getObject("heart_rate", Integer.class), rs.getObject("sbp", Integer.class),
            rs.getObject("dbp", Integer.class), rs.getObject("spo2", Integer.class),
            rs.getBigDecimal("etco2"), rs.getBigDecimal("temperature"), rs.getString("note"),
            instantOf(rs, "created_at"));

    private static final RowMapper<IntraopNursing> INTRAOP_NURSING_MAPPER =
            (rs, i) -> new IntraopNursing(
                    rs.getObject("id", UUID.class), rs.getObject("surgery_case_id", UUID.class),
                    rs.getString("scrub_nurse"), rs.getString("circulating_nurse"),
                    rs.getString("patient_position"), rs.getString("skin_prep"),
                    instantOf(rs, "tourniquet_start"), instantOf(rs, "tourniquet_end"),
                    rs.getInt("specimen_count"), rs.getString("notes"), rs.getString("status"),
                    rs.getString("signed_by"), instantOf(rs, "signed_at"),
                    rs.getString("created_by"), rs.getLong("version"),
                    instantOf(rs, "created_at"));

    // ---- 手术间

    public void insertOperatingRoom(OperatingRoom room) {
        jdbc.sql("""
                        INSERT INTO clinical_svc.operating_room
                            (id, code, name, dept_id, location, status, version)
                        VALUES (:id, :code, :name, CAST(:deptId AS uuid), :location, :status, 0)
                        """)
                .param("id", room.id()).param("code", room.code()).param("name", room.name())
                .param("deptId", room.deptId()).param("location", room.location())
                .param("status", room.status()).update();
    }

    public Optional<OperatingRoom> findOperatingRoom(UUID id) {
        return jdbc.sql("SELECT * FROM clinical_svc.operating_room WHERE id = ?")
                .param(id).query(ROOM_MAPPER).optional();
    }

    public Optional<OperatingRoom> findOperatingRoomByCode(String code) {
        return jdbc.sql("SELECT * FROM clinical_svc.operating_room WHERE code = ?")
                .param(code).query(ROOM_MAPPER).optional();
    }

    public List<OperatingRoom> listOperatingRooms(String status, int limit) {
        return jdbc.sql("""
                        SELECT * FROM clinical_svc.operating_room
                         WHERE (CAST(:status AS text) IS NULL OR status = CAST(:status AS text))
                         ORDER BY code LIMIT :limit
                        """)
                .param("status", status).param("limit", limit).query(ROOM_MAPPER).list();
    }

    public boolean updateOperatingRoomStatus(UUID id, String status, long expectedVersion) {
        return jdbc.sql("""
                        UPDATE clinical_svc.operating_room
                           SET status = :status, version = version + 1, updated_at = now()
                         WHERE id = :id AND version = :version
                        """)
                .param("status", status).param("id", id).param("version", expectedVersion)
                .update() > 0;
    }

    // ---- 排台

    /**
     * 同房间排台串行化（事务级 advisory lock）。
     *
     * <p>必须"单键按房间"：排台冲突的判定范围就是同一手术间，全局单键会把无关房间一起排队，
     * 按业务参数随意拼键又会让两个真正冲突的事务拿到不同锁（第六轮踩过）。
     */
    public void lockRoomForScheduling(UUID roomId) {
        jdbc.sql("SELECT pg_advisory_xact_lock(hashtextextended(:key, 0))")
                .param("key", "clinical:surgery-room:" + roomId).query((rs, i) -> 1).list();
    }

    /** 该手术当前未取消的排台。 */
    public Optional<SurgerySchedule> findActiveScheduleByCase(UUID surgeryCaseId) {
        return jdbc.sql("""
                        SELECT * FROM clinical_svc.surgery_schedule
                         WHERE surgery_case_id = ? AND status <> 'CANCELLED'
                         ORDER BY created_at DESC LIMIT 1
                        """).param(surgeryCaseId).query(SCHEDULE_MAPPER).optional();
    }

    /** 该手术最近一条排台（含已取消，用于历史展示）。 */
    public Optional<SurgerySchedule> findLatestScheduleByCase(UUID surgeryCaseId) {
        return jdbc.sql("""
                        SELECT * FROM clinical_svc.surgery_schedule
                         WHERE surgery_case_id = ? ORDER BY created_at DESC LIMIT 1
                        """).param(surgeryCaseId).query(SCHEDULE_MAPPER).optional();
    }

    /** 同房间时间重叠的排台数（半开区间：首尾相接不算冲突）。 */
    public long countOverlappingSchedules(UUID roomId, java.time.Instant start,
                                          java.time.Instant end, UUID excludeCaseId) {
        Long count = jdbc.sql("""
                        SELECT count(*) FROM clinical_svc.surgery_schedule
                         WHERE room_id = :roomId AND status <> 'CANCELLED'
                           AND planned_start < CAST(:endAt AS timestamptz)
                           AND planned_end > CAST(:startAt AS timestamptz)
                           AND (CAST(:exclude AS uuid) IS NULL
                                OR surgery_case_id <> CAST(:exclude AS uuid))
                        """)
                .param("roomId", roomId).param("startAt", ts(start))
                .param("endAt", ts(end)).param("exclude", excludeCaseId)
                .query(Long.class).single();
        return count == null ? 0 : count;
    }

    /** 当天该房间的接台序号（未取消排台的最大序号 + 1）。 */
    public int nextScheduleSequence(UUID roomId, java.time.Instant dayStart,
                                    java.time.Instant dayEnd) {
        Integer max = jdbc.sql("""
                        SELECT COALESCE(MAX(sequence), 0) FROM clinical_svc.surgery_schedule
                         WHERE room_id = :roomId AND status <> 'CANCELLED'
                           AND planned_start >= CAST(:dayStart AS timestamptz)
                           AND planned_start < CAST(:dayEnd AS timestamptz)
                        """)
                .param("roomId", roomId).param("dayStart", ts(dayStart)).param("dayEnd", ts(dayEnd))
                .query(Integer.class).single();
        return (max == null ? 0 : max) + 1;
    }

    public void insertSchedule(SurgerySchedule s) {
        jdbc.sql("""
                        INSERT INTO clinical_svc.surgery_schedule
                            (id, surgery_case_id, room_id, planned_start, planned_end, sequence,
                             status, created_by, version)
                        VALUES (:id, :caseId, :roomId, CAST(:plannedStart AS timestamptz),
                                CAST(:plannedEnd AS timestamptz), :sequence, :status, :createdBy, 0)
                        """)
                .param("id", s.id()).param("caseId", s.surgeryCaseId()).param("roomId", s.roomId())
                .param("plannedStart", ts(s.plannedStart()))
                .param("plannedEnd", ts(s.plannedEnd()))
                .param("sequence", s.sequence()).param("status", s.status())
                .param("createdBy", s.createdBy()).update();
    }

    public Optional<SurgerySchedule> lockScheduleByCase(UUID surgeryCaseId) {
        return jdbc.sql("""
                        SELECT * FROM clinical_svc.surgery_schedule
                         WHERE surgery_case_id = ? AND status <> 'CANCELLED' FOR UPDATE
                        """).param(surgeryCaseId).query(SCHEDULE_MAPPER).optional();
    }

    public boolean cancelSchedule(UUID id, String reason, long expectedVersion) {
        return jdbc.sql("""
                        UPDATE clinical_svc.surgery_schedule
                           SET status = 'CANCELLED', cancel_reason = :reason,
                               version = version + 1, updated_at = now()
                         WHERE id = :id AND status = 'SCHEDULED' AND version = :version
                        """)
                .param("reason", reason).param("id", id).param("version", expectedVersion)
                .update() > 0;
    }

    /** 手术开始 → 排台转进行中（无排台时静默跳过）。 */
    public void startScheduleByCase(UUID surgeryCaseId) {
        jdbc.sql("""
                        UPDATE clinical_svc.surgery_schedule
                           SET status = 'IN_PROGRESS', version = version + 1, updated_at = now()
                         WHERE surgery_case_id = :caseId AND status = 'SCHEDULED'
                        """).param("caseId", surgeryCaseId).update();
    }

    /** 手术完成 → 排台转已完成。 */
    public void completeScheduleByCase(UUID surgeryCaseId) {
        jdbc.sql("""
                        UPDATE clinical_svc.surgery_schedule
                           SET status = 'COMPLETED', version = version + 1, updated_at = now()
                         WHERE surgery_case_id = :caseId AND status IN ('SCHEDULED', 'IN_PROGRESS')
                        """).param("caseId", surgeryCaseId).update();
    }

    /** 手术取消 → 排台一并取消（必须留原因，与手术取消同因）。 */
    public void cancelScheduleByCase(UUID surgeryCaseId, String reason) {
        jdbc.sql("""
                        UPDATE clinical_svc.surgery_schedule
                           SET status = 'CANCELLED', cancel_reason = :reason,
                               version = version + 1, updated_at = now()
                         WHERE surgery_case_id = :caseId AND status = 'SCHEDULED'
                        """)
                .param("reason", reason).param("caseId", surgeryCaseId).update();
    }

    /** 手术间看板：某房间某天（含跨零点）的全部排台。 */
    public List<SurgerySchedule> listSchedulesByRoom(UUID roomId, java.time.Instant dayStart,
                                                     java.time.Instant dayEnd) {
        return jdbc.sql("""
                        SELECT * FROM clinical_svc.surgery_schedule
                         WHERE room_id = :roomId
                           AND planned_start < CAST(:dayEnd AS timestamptz)
                           AND planned_end > CAST(:dayStart AS timestamptz)
                         ORDER BY planned_start
                        """)
                .param("roomId", roomId).param("dayStart", ts(dayStart))
                .param("dayEnd", ts(dayEnd)).query(SCHEDULE_MAPPER).list();
    }

    // ---------------------------------------------------------------- 排台看板聚合（V4 评审）

    private static final RowMapper<SurgeryBoardRow> BOARD_ROW_MAPPER = (rs, i) -> new SurgeryBoardRow(
            rs.getObject("schedule_id", UUID.class),
            rs.getObject("room_id", UUID.class),
            rs.getString("room_code"),
            rs.getString("room_name"),
            rs.getString("room_location"),
            rs.getObject("surgery_case_id", UUID.class),
            rs.getString("case_no"),
            rs.getObject("visit_id", UUID.class),
            rs.getString("visit_no"),
            rs.getString("patient_no"),
            rs.getString("operation_code"),
            rs.getString("operation_name"),
            rs.getString("surgeon"),
            rs.getString("assistant"),
            rs.getString("anesthesia_type"),
            rs.getString("asa_grade"),
            rs.getString("incision_level"),
            rs.getString("case_status"),
            rs.getString("schedule_status"),
            rs.getObject("sequence", Integer.class),
            instantOf(rs, "planned_start"),
            instantOf(rs, "planned_end"),
            rs.getString("cancel_reason"),
            rs.getLong("schedule_version"),
            rs.getObject("surgery_level", Integer.class),
            rs.getString("level_approval_status"));

    /**
     * 某天全部房间的排台聚合（一条 SQL）。
     *
     * <p>看板要回答「哪间空着、下一台是什么、谁做、几级」——这些字段分散在
     * schedule / case / visit / room / level 五张表里，以前只回 {@code surgeryCaseId}，
     * 前端只能显示「第 N 台 + 病例号」。这里一次 JOIN 取全，前端按 roomId 分组即可。
     *
     * <p>排序在服务端固定：房间编码 → 计划开始 → 台次 → id，保证多端一致、可复现。
     */
    public List<SurgeryBoardRow> listBoardRows(java.time.Instant dayStart,
                                               java.time.Instant dayEnd,
                                               List<UUID> roomIds) {
        if (roomIds == null || roomIds.isEmpty()) {
            return List.of();
        }
        return jdbc.sql("""
                        SELECT s.id              AS schedule_id,
                               s.room_id         AS room_id,
                               r.code            AS room_code,
                               r.name            AS room_name,
                               r.location        AS room_location,
                               s.surgery_case_id AS surgery_case_id,
                               c.case_no         AS case_no,
                               c.visit_id        AS visit_id,
                               v.visit_no        AS visit_no,
                               v.patient_no      AS patient_no,
                               c.operation_code  AS operation_code,
                               c.operation_name  AS operation_name,
                               c.surgeon         AS surgeon,
                               c.assistant       AS assistant,
                               c.anesthesia_type AS anesthesia_type,
                               c.asa_grade       AS asa_grade,
                               c.incision_level  AS incision_level,
                               c.status          AS case_status,
                               s.status          AS schedule_status,
                               s.sequence        AS sequence,
                               s.planned_start   AS planned_start,
                               s.planned_end     AS planned_end,
                               s.cancel_reason   AS cancel_reason,
                               s.version         AS schedule_version,
                               lv.level          AS surgery_level,
                               lv.approval_status AS level_approval_status
                          FROM clinical_svc.surgery_schedule s
                          JOIN clinical_svc.surgery_case c ON c.id = s.surgery_case_id
                          JOIN clinical_svc.visit v ON v.id = c.visit_id
                          LEFT JOIN clinical_svc.operating_room r ON r.id = s.room_id
                          LEFT JOIN clinical_svc.surgery_level lv ON lv.surgery_case_id = c.id
                         WHERE s.planned_start < CAST(:dayEnd AS timestamptz)
                           AND s.planned_end > CAST(:dayStart AS timestamptz)
                           AND s.room_id = ANY(CAST(:roomIds AS uuid[]))
                         ORDER BY r.code, s.planned_start, s.sequence, s.id
                        """)
                .param("dayStart", ts(dayStart)).param("dayEnd", ts(dayEnd))
                .param("roomIds", uuidArrayLiteral(roomIds))
                .query(BOARD_ROW_MAPPER).list();
    }

    /** Postgres uuid[] 字面量：{a,b,c}。用于 ANY(CAST(:ids AS uuid[]))，空集合由调用方拦住。 */
    private static String uuidArrayLiteral(List<UUID> ids) {
        StringBuilder sb = new StringBuilder("{");
        for (int i = 0; i < ids.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(ids.get(i));
        }
        return sb.append('}').toString();
    }

    // ---- 麻醉记录

    public Optional<AnesthesiaRecord> findAnesthesiaByCase(UUID surgeryCaseId) {
        return jdbc.sql("SELECT * FROM clinical_svc.anesthesia_record WHERE surgery_case_id = ?")
                .param(surgeryCaseId).query(ANESTHESIA_MAPPER).optional();
    }

    public void insertAnesthesia(AnesthesiaRecord r) {
        jdbc.sql("""
                        INSERT INTO clinical_svc.anesthesia_record
                            (id, surgery_case_id, method, asa_grade, anesthesiologist, airway,
                             started_at, ended_at, notes, status, created_by, version)
                        VALUES (:id, :caseId, :method, :asaGrade, :anesthesiologist, :airway,
                                CAST(:startedAt AS timestamptz), CAST(:endedAt AS timestamptz),
                                :notes, 'DRAFT', :createdBy, 0)
                        """)
                .param("id", r.id()).param("caseId", r.surgeryCaseId()).param("method", r.method())
                .param("asaGrade", r.asaGrade()).param("anesthesiologist", r.anesthesiologist())
                .param("airway", r.airway()).param("startedAt", ts(r.startedAt()))
                .param("endedAt", ts(r.endedAt())).param("notes", r.notes())
                .param("createdBy", r.createdBy()).update();
    }

    public boolean updateAnesthesiaDraft(AnesthesiaRecord r, long expectedVersion) {
        return jdbc.sql("""
                        UPDATE clinical_svc.anesthesia_record
                           SET method = :method, asa_grade = :asaGrade,
                               anesthesiologist = :anesthesiologist, airway = :airway,
                               started_at = CAST(:startedAt AS timestamptz),
                               ended_at = CAST(:endedAt AS timestamptz), notes = :notes,
                               version = version + 1, updated_at = now()
                         WHERE id = :id AND status = 'DRAFT' AND version = :version
                        """)
                .param("method", r.method()).param("asaGrade", r.asaGrade())
                .param("anesthesiologist", r.anesthesiologist()).param("airway", r.airway())
                .param("startedAt", ts(r.startedAt())).param("endedAt", ts(r.endedAt()))
                .param("notes", r.notes()).param("id", r.id()).param("version", expectedVersion)
                .update() > 0;
    }

    public boolean signAnesthesia(UUID id, String signedBy, long expectedVersion) {
        return jdbc.sql("""
                        UPDATE clinical_svc.anesthesia_record
                           SET status = 'SIGNED', signed_by = :signedBy, signed_at = now(),
                               version = version + 1, updated_at = now()
                         WHERE id = :id AND status = 'DRAFT' AND version = :version
                        """)
                .param("signedBy", signedBy).param("id", id).param("version", expectedVersion)
                .update() > 0;
    }

    public void insertVital(AnesthesiaVital v, String createdBy) {
        jdbc.sql("""
                        INSERT INTO clinical_svc.anesthesia_vital
                            (id, record_id, recorded_at, heart_rate, sbp, dbp, spo2, etco2,
                             temperature, note, created_by)
                        VALUES (:id, :recordId, CAST(:recordedAt AS timestamptz),
                                CAST(:heartRate AS integer), CAST(:sbp AS integer),
                                CAST(:dbp AS integer), CAST(:spo2 AS integer),
                                CAST(:etco2 AS numeric), CAST(:temperature AS numeric),
                                :note, :createdBy)
                        """)
                .param("id", v.id()).param("recordId", v.recordId())
                .param("recordedAt", ts(v.recordedAt())).param("heartRate", v.heartRate())
                .param("sbp", v.sbp()).param("dbp", v.dbp()).param("spo2", v.spo2())
                .param("etco2", v.etco2()).param("temperature", v.temperature())
                .param("note", v.note()).param("createdBy", createdBy).update();
    }

    public List<AnesthesiaVital> listVitals(UUID recordId) {
        return jdbc.sql("""
                        SELECT * FROM clinical_svc.anesthesia_vital
                         WHERE record_id = ? ORDER BY recorded_at
                        """).param(recordId).query(VITAL_MAPPER).list();
    }

    public long countVitals(UUID recordId) {
        Long count = jdbc.sql("SELECT count(*) FROM clinical_svc.anesthesia_vital WHERE record_id = ?")
                .param(recordId).query(Long.class).single();
        return count == null ? 0 : count;
    }

    // ---- 术中护理

    public Optional<IntraopNursing> findNursingByCase(UUID surgeryCaseId) {
        return jdbc.sql("SELECT * FROM clinical_svc.intraop_nursing WHERE surgery_case_id = ?")
                .param(surgeryCaseId).query(INTRAOP_NURSING_MAPPER).optional();
    }

    public void insertNursing(IntraopNursing n) {
        jdbc.sql("""
                        INSERT INTO clinical_svc.intraop_nursing
                            (id, surgery_case_id, scrub_nurse, circulating_nurse, patient_position,
                             skin_prep, tourniquet_start, tourniquet_end, specimen_count, notes,
                             status, created_by, version)
                        VALUES (:id, :caseId, :scrubNurse, :circulatingNurse, :position,
                                :skinPrep, CAST(:tourniquetStart AS timestamptz),
                                CAST(:tourniquetEnd AS timestamptz), :specimenCount, :notes,
                                'DRAFT', :createdBy, 0)
                        """)
                .param("id", n.id()).param("caseId", n.surgeryCaseId())
                .param("scrubNurse", n.scrubNurse()).param("circulatingNurse", n.circulatingNurse())
                .param("position", n.patientPosition()).param("skinPrep", n.skinPrep())
                .param("tourniquetStart", ts(n.tourniquetStart()))
                .param("tourniquetEnd", ts(n.tourniquetEnd()))
                .param("specimenCount", n.specimenCount()).param("notes", n.notes())
                .param("createdBy", n.createdBy()).update();
    }

    public boolean updateNursingDraft(IntraopNursing n, long expectedVersion) {
        return jdbc.sql("""
                        UPDATE clinical_svc.intraop_nursing
                           SET scrub_nurse = :scrubNurse, circulating_nurse = :circulatingNurse,
                               patient_position = :position, skin_prep = :skinPrep,
                               tourniquet_start = CAST(:tourniquetStart AS timestamptz),
                               tourniquet_end = CAST(:tourniquetEnd AS timestamptz),
                               specimen_count = :specimenCount, notes = :notes,
                               version = version + 1, updated_at = now()
                         WHERE id = :id AND status = 'DRAFT' AND version = :version
                        """)
                .param("scrubNurse", n.scrubNurse()).param("circulatingNurse", n.circulatingNurse())
                .param("position", n.patientPosition()).param("skinPrep", n.skinPrep())
                .param("tourniquetStart", ts(n.tourniquetStart()))
                .param("tourniquetEnd", ts(n.tourniquetEnd()))
                .param("specimenCount", n.specimenCount()).param("notes", n.notes())
                .param("id", n.id()).param("version", expectedVersion).update() > 0;
    }

    public boolean signNursing(UUID id, String signedBy, long expectedVersion) {
        return jdbc.sql("""
                        UPDATE clinical_svc.intraop_nursing
                           SET status = 'SIGNED', signed_by = :signedBy, signed_at = now(),
                               version = version + 1, updated_at = now()
                         WHERE id = :id AND status = 'DRAFT' AND version = :version
                        """)
                .param("signedBy", signedBy).param("id", id).param("version", expectedVersion)
                .update() > 0;
    }

    // ---------------------------------------------------------------- 术前讨论 / 知情同意（V17）

    private static final RowMapper<PreopDiscussion> PREOP_MAPPER = (rs, i) -> new PreopDiscussion(
            rs.getObject("id", UUID.class), rs.getObject("surgery_case_id", UUID.class),
            instantOf(rs, "discussion_at"), rs.getString("moderator"),
            rs.getString("participants"), rs.getString("diagnosis_summary"),
            rs.getString("procedure_plan"), rs.getString("risks"),
            rs.getString("anesthesia_plan"), rs.getString("blood_preparation"),
            rs.getString("status"), rs.getString("signed_by"), instantOf(rs, "signed_at"),
            rs.getString("created_by"), rs.getLong("version"), instantOf(rs, "created_at"));

    private static final RowMapper<SurgeryConsent> CONSENT_MAPPER = (rs, i) -> new SurgeryConsent(
            rs.getObject("id", UUID.class), rs.getObject("surgery_case_id", UUID.class),
            rs.getString("consent_type"), rs.getString("signer_name"),
            rs.getString("signer_relation"), instantOf(rs, "signed_at"), rs.getString("witness"),
            rs.getString("notes"), instantOf(rs, "revoked_at"), rs.getString("revoke_reason"),
            rs.getString("created_by"), rs.getLong("version"), instantOf(rs, "created_at"));

    public Optional<PreopDiscussion> findPreopByCase(UUID surgeryCaseId) {
        return jdbc.sql("SELECT * FROM clinical_svc.preop_discussion WHERE surgery_case_id = ?")
                .param(surgeryCaseId).query(PREOP_MAPPER).optional();
    }

    public void insertPreop(PreopDiscussion d) {
        jdbc.sql("""
                        INSERT INTO clinical_svc.preop_discussion
                            (id, surgery_case_id, discussion_at, moderator, participants,
                             diagnosis_summary, procedure_plan, risks, anesthesia_plan,
                             blood_preparation, status, created_by, version)
                        VALUES (:id, :caseId, CAST(:discussionAt AS timestamptz), :moderator,
                                :participants, :diagnosisSummary, :procedurePlan, :risks,
                                :anesthesiaPlan, :bloodPreparation, 'DRAFT', :createdBy, 0)
                        """)
                .param("id", d.id()).param("caseId", d.surgeryCaseId())
                .param("discussionAt", ts(d.discussionAt())).param("moderator", d.moderator())
                .param("participants", d.participants())
                .param("diagnosisSummary", d.diagnosisSummary())
                .param("procedurePlan", d.procedurePlan()).param("risks", d.risks())
                .param("anesthesiaPlan", d.anesthesiaPlan())
                .param("bloodPreparation", d.bloodPreparation())
                .param("createdBy", d.createdBy()).update();
    }

    public boolean updatePreopDraft(PreopDiscussion d, long expectedVersion) {
        return jdbc.sql("""
                        UPDATE clinical_svc.preop_discussion
                           SET discussion_at = CAST(:discussionAt AS timestamptz),
                               moderator = :moderator, participants = :participants,
                               diagnosis_summary = :diagnosisSummary,
                               procedure_plan = :procedurePlan, risks = :risks,
                               anesthesia_plan = :anesthesiaPlan,
                               blood_preparation = :bloodPreparation,
                               version = version + 1, updated_at = now()
                         WHERE id = :id AND status = 'DRAFT' AND version = :version
                        """)
                .param("discussionAt", ts(d.discussionAt())).param("moderator", d.moderator())
                .param("participants", d.participants())
                .param("diagnosisSummary", d.diagnosisSummary())
                .param("procedurePlan", d.procedurePlan()).param("risks", d.risks())
                .param("anesthesiaPlan", d.anesthesiaPlan())
                .param("bloodPreparation", d.bloodPreparation())
                .param("id", d.id()).param("version", expectedVersion).update() > 0;
    }

    public boolean signPreop(UUID id, String signedBy, long expectedVersion) {
        return jdbc.sql("""
                        UPDATE clinical_svc.preop_discussion
                           SET status = 'SIGNED', signed_by = :signedBy, signed_at = now(),
                               version = version + 1, updated_at = now()
                         WHERE id = :id AND status = 'DRAFT' AND version = :version
                        """)
                .param("signedBy", signedBy).param("id", id).param("version", expectedVersion)
                .update() > 0;
    }

    public List<SurgeryConsent> listConsentsByCase(UUID surgeryCaseId) {
        return jdbc.sql("""
                        SELECT * FROM clinical_svc.surgery_consent
                         WHERE surgery_case_id = ? ORDER BY created_at DESC
                        """).param(surgeryCaseId).query(CONSENT_MAPPER).list();
    }

    public Optional<SurgeryConsent> findActiveConsent(UUID surgeryCaseId, String consentType) {
        return jdbc.sql("""
                        SELECT * FROM clinical_svc.surgery_consent
                         WHERE surgery_case_id = :caseId AND consent_type = :type
                           AND revoked_at IS NULL
                        """)
                .param("caseId", surgeryCaseId).param("type", consentType)
                .query(CONSENT_MAPPER).optional();
    }

    public void insertConsent(SurgeryConsent c) {
        jdbc.sql("""
                        INSERT INTO clinical_svc.surgery_consent
                            (id, surgery_case_id, consent_type, signer_name, signer_relation,
                             signed_at, witness, notes, created_by, version)
                        VALUES (:id, :caseId, :consentType, :signerName, :signerRelation,
                                CAST(:signedAt AS timestamptz), :witness, :notes, :createdBy, 0)
                        """)
                .param("id", c.id()).param("caseId", c.surgeryCaseId())
                .param("consentType", c.consentType()).param("signerName", c.signerName())
                .param("signerRelation", c.signerRelation())
                .param("signedAt", ts(c.signedAt())).param("witness", c.witness())
                .param("notes", c.notes()).param("createdBy", c.createdBy()).update();
    }

    public boolean revokeConsent(UUID id, String reason, long expectedVersion) {
        return jdbc.sql("""
                        UPDATE clinical_svc.surgery_consent
                           SET revoked_at = now(), revoke_reason = :reason,
                               version = version + 1, updated_at = now()
                         WHERE id = :id AND revoked_at IS NULL AND version = :version
                        """)
                .param("reason", reason).param("id", id).param("version", expectedVersion)
                .update() > 0;
    }

    /** 该手术当前有效的同意书类型（手术开始的硬门槛校验用）。 */
    public List<String> listActiveConsentTypes(UUID surgeryCaseId) {
        return jdbc.sql("""
                        SELECT consent_type FROM clinical_svc.surgery_consent
                         WHERE surgery_case_id = ? AND revoked_at IS NULL
                        """).param(surgeryCaseId).query(String.class).list();
    }

    // ---------------------------------------------------------------- 术前访视（V21）

    private static final RowMapper<PreopVisit> PREOP_VISIT_MAPPER = (rs, i) -> new PreopVisit(
            rs.getObject("id", UUID.class), rs.getObject("surgery_case_id", UUID.class),
            instantOf(rs, "visited_at"), rs.getString("visited_by"),
            rs.getString("anesthesia_plan"), rs.getString("asa_grade"),
            rs.getString("mallampati"), rs.getString("airway_note"), rs.getString("dentition"),
            rs.getString("anesthesia_history"), rs.getString("allergy_history"),
            rs.getObject("fasting_solid_hours", Integer.class),
            rs.getObject("fasting_clear_hours", Integer.class), rs.getString("conclusion"),
            rs.getString("risk_note"), rs.getString("status"), rs.getString("signed_by"),
            instantOf(rs, "signed_at"), rs.getString("created_by"), rs.getLong("version"),
            instantOf(rs, "created_at"));

    public Optional<PreopVisit> findPreopVisit(UUID surgeryCaseId) {
        return jdbc.sql("SELECT * FROM clinical_svc.preop_visit WHERE surgery_case_id = ?")
                .param(surgeryCaseId).query(PREOP_VISIT_MAPPER).optional();
    }

    public void insertPreopVisit(PreopVisit v) {
        jdbc.sql("""
                        INSERT INTO clinical_svc.preop_visit
                            (id, surgery_case_id, visited_at, visited_by, anesthesia_plan,
                             asa_grade, mallampati, airway_note, dentition, anesthesia_history,
                             allergy_history, fasting_solid_hours, fasting_clear_hours,
                             conclusion, risk_note, status, created_by, version)
                        VALUES (:id, :caseId, CAST(:visitedAt AS timestamptz), :visitedBy,
                                :anesthesiaPlan, :asaGrade, :mallampati, :airwayNote, :dentition,
                                :anesthesiaHistory, :allergyHistory,
                                CAST(:fastingSolid AS integer), CAST(:fastingClear AS integer),
                                :conclusion, :riskNote, 'DRAFT', :createdBy, 0)
                        """)
                .param("id", v.id()).param("caseId", v.surgeryCaseId())
                .param("visitedAt", ts(v.visitedAt())).param("visitedBy", v.visitedBy())
                .param("anesthesiaPlan", v.anesthesiaPlan()).param("asaGrade", v.asaGrade())
                .param("mallampati", v.mallampati()).param("airwayNote", v.airwayNote())
                .param("dentition", v.dentition())
                .param("anesthesiaHistory", v.anesthesiaHistory())
                .param("allergyHistory", v.allergyHistory())
                .param("fastingSolid", v.fastingSolidHours())
                .param("fastingClear", v.fastingClearHours())
                .param("conclusion", v.conclusion()).param("riskNote", v.riskNote())
                .param("createdBy", v.createdBy()).update();
    }

    public boolean updatePreopVisitDraft(PreopVisit v, long expectedVersion) {
        return jdbc.sql("""
                        UPDATE clinical_svc.preop_visit
                           SET visited_at = CAST(:visitedAt AS timestamptz),
                               visited_by = :visitedBy, anesthesia_plan = :anesthesiaPlan,
                               asa_grade = :asaGrade, mallampati = :mallampati,
                               airway_note = :airwayNote, dentition = :dentition,
                               anesthesia_history = :anesthesiaHistory,
                               allergy_history = :allergyHistory,
                               fasting_solid_hours = CAST(:fastingSolid AS integer),
                               fasting_clear_hours = CAST(:fastingClear AS integer),
                               conclusion = :conclusion, risk_note = :riskNote,
                               version = version + 1, updated_at = now()
                         WHERE id = :id AND status = 'DRAFT' AND version = :version
                        """)
                .param("visitedAt", ts(v.visitedAt())).param("visitedBy", v.visitedBy())
                .param("anesthesiaPlan", v.anesthesiaPlan()).param("asaGrade", v.asaGrade())
                .param("mallampati", v.mallampati()).param("airwayNote", v.airwayNote())
                .param("dentition", v.dentition())
                .param("anesthesiaHistory", v.anesthesiaHistory())
                .param("allergyHistory", v.allergyHistory())
                .param("fastingSolid", v.fastingSolidHours())
                .param("fastingClear", v.fastingClearHours())
                .param("conclusion", v.conclusion()).param("riskNote", v.riskNote())
                .param("id", v.id()).param("version", expectedVersion).update() > 0;
    }

    public boolean signPreopVisit(UUID id, String signedBy, long expectedVersion) {
        return jdbc.sql("""
                        UPDATE clinical_svc.preop_visit
                           SET status = 'SIGNED', signed_by = :signedBy, signed_at = now(),
                               version = version + 1, updated_at = now()
                         WHERE id = :id AND status = 'DRAFT' AND version = :version
                        """)
                .param("signedBy", signedBy).param("id", id).param("version", expectedVersion)
                .update() > 0;
    }

    // ---------------------------------------------------------------- 手术分级（V18）

    private static final RowMapper<SurgeryLevel> SURGERY_LEVEL_MAPPER = (rs, i) -> new SurgeryLevel(
            rs.getObject("id", UUID.class), rs.getObject("surgery_case_id", UUID.class),
            rs.getInt("level"), rs.getString("approval_status"), rs.getString("applied_by"),
            instantOf(rs, "applied_at"), rs.getString("reviewed_by"),
            instantOf(rs, "reviewed_at"), rs.getString("reject_reason"),
            rs.getString("created_by"), rs.getLong("version"), instantOf(rs, "created_at"));

    public Optional<SurgeryLevel> findSurgeryLevel(UUID surgeryCaseId) {
        return jdbc.sql("SELECT * FROM clinical_svc.surgery_level WHERE surgery_case_id = ?")
                .param(surgeryCaseId).query(SURGERY_LEVEL_MAPPER).optional();
    }

    public void insertSurgeryLevel(SurgeryLevel l) {
        jdbc.sql("""
                        INSERT INTO clinical_svc.surgery_level
                            (id, surgery_case_id, level, approval_status, applied_by, applied_at,
                             created_by, version)
                        VALUES (:id, :caseId, :level, :status, :appliedBy, now(), :createdBy, 0)
                        """)
                .param("id", l.id()).param("caseId", l.surgeryCaseId()).param("level", l.level())
                .param("status", l.approvalStatus()).param("appliedBy", l.appliedBy())
                .param("createdBy", l.createdBy()).update();
    }

    /**
     * 重新申请分级：清空上一次的审批结论。
     *
     * <p>为什么"改分级"必须清空 reviewed_*：把 4 级改成 3 级却保留原审批结论，
     * 等于用一个旧结论批准了一个新申请（数据库触发器也会拒绝这种写法）。
     */
    public boolean resetSurgeryLevel(UUID surgeryCaseId, int level, String approvalStatus,
                                     String appliedBy, long expectedVersion) {
        return jdbc.sql("""
                        UPDATE clinical_svc.surgery_level
                           SET level = :level, approval_status = :status,
                               applied_by = :appliedBy, applied_at = now(),
                               reviewed_by = NULL, reviewed_at = NULL, reject_reason = NULL,
                               version = version + 1, updated_at = now()
                         WHERE surgery_case_id = :caseId AND version = :version
                        """)
                .param("level", level).param("status", approvalStatus)
                .param("appliedBy", appliedBy).param("caseId", surgeryCaseId)
                .param("version", expectedVersion).update() > 0;
    }

    public boolean approveSurgeryLevel(UUID surgeryCaseId, String reviewedBy,
                                       long expectedVersion) {
        return jdbc.sql("""
                        UPDATE clinical_svc.surgery_level
                           SET approval_status = 'APPROVED', reviewed_by = :reviewedBy,
                               reviewed_at = now(), reject_reason = NULL,
                               version = version + 1, updated_at = now()
                         WHERE surgery_case_id = :caseId AND approval_status = 'PENDING'
                           AND version = :version
                        """)
                .param("reviewedBy", reviewedBy).param("caseId", surgeryCaseId)
                .param("version", expectedVersion).update() > 0;
    }

    public boolean rejectSurgeryLevel(UUID surgeryCaseId, String reason, String reviewedBy,
                                      long expectedVersion) {
        return jdbc.sql("""
                        UPDATE clinical_svc.surgery_level
                           SET approval_status = 'REJECTED', reviewed_by = :reviewedBy,
                               reviewed_at = now(), reject_reason = :reason,
                               version = version + 1, updated_at = now()
                         WHERE surgery_case_id = :caseId AND approval_status = 'PENDING'
                           AND version = :version
                        """)
                .param("reason", reason).param("reviewedBy", reviewedBy)
                .param("caseId", surgeryCaseId).param("version", expectedVersion)
                .update() > 0;
    }

    // ---------------------------------------------------------------- 手术间利用率（V18）

    private static final RowMapper<RoomDayUsage> ROOM_DAY_USAGE_MAPPER = (rs, i) -> new RoomDayUsage(
            instantOf(rs, "day_start"), rs.getInt("scheduled_count"),
            rs.getInt("completed_count"), rs.getInt("cancelled_count"),
            rs.getLong("occupied_minutes"), rs.getLong("turnover_minutes"),
            rs.getInt("turnover_count"));

    /**
     * 某手术间在 [fromAt, toAtExclusive) 内按天聚合：台次、占用分钟、接台间隙。
     *
     * <p>口径（写进 SQL 而不是 Java，避免报表与看板两套算法）：
     * <ul>
     *   <li>占用分钟只统计**未取消**的排台，且与每日开放时段 [openMinute, closeMinute) 取交集，
     *       跨零点或超出开放时段的排台不会被算成满勤；</li>
     *   <li>接台间隙 = 同一房间同一天相邻两台（未取消）之间的空闲分钟，负数按 0 计；</li>
     *   <li>没有排台的日期也会返回一行（generate_series），前端可以直接画连续曲线。</li>
     * </ul>
     */
    public List<RoomDayUsage> listRoomDayUsage(UUID roomId, java.time.Instant fromAt,
                                               java.time.Instant toAtExclusive,
                                               int openMinuteOfDay, int closeMinuteOfDay) {
        return jdbc.sql("""
                        WITH days AS (
                            SELECT generate_series(CAST(:fromAt AS timestamptz),
                                                   CAST(:toAt AS timestamptz) - interval '1 day',
                                                   interval '1 day') AS day_start
                        ),
                        windows AS (
                            SELECT day_start,
                                   day_start + make_interval(mins => CAST(:openMin AS int))
                                       AS win_start,
                                   day_start + make_interval(mins => CAST(:closeMin AS int))
                                       AS win_end
                              FROM days
                        ),
                        joined AS (
                            SELECT w.day_start, s.id, s.status, s.planned_start, s.planned_end,
                                   w.win_start, w.win_end
                              FROM windows w
                              LEFT JOIN clinical_svc.surgery_schedule s
                                ON s.room_id = :roomId
                               AND s.planned_start < w.win_end
                               AND s.planned_end > w.win_start
                        ),
                        gaps AS (
                            SELECT day_start,
                                   EXTRACT(EPOCH FROM (
                                       planned_start - lag(planned_end) OVER (
                                           PARTITION BY day_start ORDER BY planned_start)
                                   )) / 60 AS gap_minutes
                              FROM joined
                             WHERE status IS NOT NULL AND status <> 'CANCELLED'
                        )
                        SELECT j.day_start,
                               count(j.id) FILTER (WHERE j.status <> 'CANCELLED')
                                   AS scheduled_count,
                               count(j.id) FILTER (WHERE j.status = 'COMPLETED')
                                   AS completed_count,
                               count(j.id) FILTER (WHERE j.status = 'CANCELLED')
                                   AS cancelled_count,
                               COALESCE(sum(
                                   GREATEST(0, EXTRACT(EPOCH FROM (
                                       LEAST(j.planned_end, j.win_end)
                                       - GREATEST(j.planned_start, j.win_start))) / 60)
                               ) FILTER (WHERE j.status <> 'CANCELLED'), 0) AS occupied_minutes,
                               COALESCE((SELECT sum(GREATEST(0, g.gap_minutes))
                                           FROM gaps g WHERE g.day_start = j.day_start), 0)
                                   AS turnover_minutes,
                               COALESCE((SELECT count(*) FROM gaps g
                                          WHERE g.day_start = j.day_start
                                            AND g.gap_minutes IS NOT NULL), 0)
                                   AS turnover_count
                          FROM joined j
                         GROUP BY j.day_start
                         ORDER BY j.day_start
                        """)
                .param("fromAt", ts(fromAt)).param("toAt", ts(toAtExclusive))
                .param("openMin", openMinuteOfDay).param("closeMin", closeMinuteOfDay)
                .param("roomId", roomId).query(ROOM_DAY_USAGE_MAPPER).list();
    }

    private static final RowMapper<RoomDayUsageRow> ROOM_DAY_USAGE_ROW_MAPPER = (rs, i) ->
            new RoomDayUsageRow(rs.getObject("room_id", UUID.class),
                    new RoomDayUsage(instantOf(rs, "day_start"),
                            rs.getInt("scheduled_count"), rs.getInt("completed_count"),
                            rs.getInt("cancelled_count"), rs.getLong("occupied_minutes"),
                            rs.getLong("turnover_minutes"), rs.getInt("turnover_count")));

    /**
     * 多手术间利用率（一条 SQL 覆盖全部房间 × 全部日期）。
     *
     * <p>替代「按房间循环调用 {@link #listRoomDayUsage}」的 N+1：
     * 原实现 12 个房间会串行跑 12 次 CTE，房间多的院区直接撞上网关 30s 超时。
     * 口径与单房间版本完全一致（同一段 SQL，只是多了 room 维度），
     * 因此日报表与看板不会出现两个算法。
     */
    public List<RoomDayUsageRow> listRoomDayUsageBatch(List<UUID> roomIds,
                                                       java.time.Instant fromAt,
                                                       java.time.Instant toAtExclusive,
                                                       int openMinuteOfDay, int closeMinuteOfDay) {
        if (roomIds == null || roomIds.isEmpty()) {
            return List.of();
        }
        return jdbc.sql("""
                        WITH rooms AS (
                            SELECT unnest(CAST(:roomIds AS uuid[])) AS room_id
                        ),
                        days AS (
                            SELECT generate_series(CAST(:fromAt AS timestamptz),
                                                   CAST(:toAt AS timestamptz) - interval '1 day',
                                                   interval '1 day') AS day_start
                        ),
                        grid AS (
                            SELECT r.room_id, d.day_start FROM rooms r CROSS JOIN days d
                        ),
                        windows AS (
                            SELECT room_id, day_start,
                                   day_start + make_interval(mins => CAST(:openMin AS int))
                                       AS win_start,
                                   day_start + make_interval(mins => CAST(:closeMin AS int))
                                       AS win_end
                              FROM grid
                        ),
                        joined AS (
                            SELECT w.room_id, w.day_start, s.id, s.status,
                                   s.planned_start, s.planned_end, w.win_start, w.win_end
                              FROM windows w
                              LEFT JOIN clinical_svc.surgery_schedule s
                                ON s.room_id = w.room_id
                               AND s.planned_start < w.win_end
                               AND s.planned_end > w.win_start
                        ),
                        gaps AS (
                            SELECT room_id, day_start,
                                   EXTRACT(EPOCH FROM (
                                       planned_start - lag(planned_end) OVER (
                                           PARTITION BY room_id, day_start
                                           ORDER BY planned_start)
                                   )) / 60 AS gap_minutes
                              FROM joined
                             WHERE status IS NOT NULL AND status <> 'CANCELLED'
                        )
                        SELECT j.room_id,
                               j.day_start,
                               count(j.id) FILTER (WHERE j.status <> 'CANCELLED')
                                   AS scheduled_count,
                               count(j.id) FILTER (WHERE j.status = 'COMPLETED')
                                   AS completed_count,
                               count(j.id) FILTER (WHERE j.status = 'CANCELLED')
                                   AS cancelled_count,
                               COALESCE(sum(
                                   GREATEST(0, EXTRACT(EPOCH FROM (
                                       LEAST(j.planned_end, j.win_end)
                                       - GREATEST(j.planned_start, j.win_start))) / 60)
                               ) FILTER (WHERE j.status <> 'CANCELLED'), 0) AS occupied_minutes,
                               COALESCE((SELECT sum(GREATEST(0, g.gap_minutes))
                                           FROM gaps g
                                          WHERE g.room_id = j.room_id
                                            AND g.day_start = j.day_start), 0)
                                   AS turnover_minutes,
                               COALESCE((SELECT count(*) FROM gaps g
                                          WHERE g.room_id = j.room_id
                                            AND g.day_start = j.day_start
                                            AND g.gap_minutes IS NOT NULL), 0)
                                   AS turnover_count
                          FROM joined j
                         GROUP BY j.room_id, j.day_start
                         ORDER BY j.room_id, j.day_start
                        """)
                .param("roomIds", uuidArrayLiteral(roomIds))
                .param("fromAt", ts(fromAt)).param("toAt", ts(toAtExclusive))
                .param("openMin", openMinuteOfDay).param("closeMin", closeMinuteOfDay)
                .query(ROOM_DAY_USAGE_ROW_MAPPER).list();
    }

    // ---------------------------------------------------------------- 手术收费打包（V19）

    private static final RowMapper<SurgeryChargeRule> SURGERY_CHARGE_RULE_MAPPER =
            (rs, i) -> new SurgeryChargeRule(
                    rs.getObject("id", UUID.class), rs.getString("rule_type"),
                    rs.getString("match_value"), rs.getString("item_code"),
                    rs.getBigDecimal("quantity"), rs.getInt("sort_no"),
                    rs.getBoolean("enabled"), rs.getLong("version"));

    /**
     * 查询启用中的打包规则。
     *
     * @param ruleType   规则类型（术式类别 / 麻醉方式 / 耗材）
     * @param matchValue 匹配值；传 {@code null} 表示该类型全部（耗材用）
     */
    public List<SurgeryChargeRule> listSurgeryChargeRules(String ruleType, String matchValue) {
        return jdbc.sql("""
                        SELECT id, rule_type, match_value, item_code, quantity, sort_no, enabled,
                               version
                          FROM clinical_svc.surgery_charge_rule
                         WHERE enabled AND rule_type = :ruleType
                           AND (CAST(:matchValue AS text) IS NULL
                                OR match_value = CAST(:matchValue AS text))
                         ORDER BY sort_no, item_code
                        """)
                .param("ruleType", ruleType).param("matchValue", matchValue)
                .query(SURGERY_CHARGE_RULE_MAPPER).list();
    }

    /** 该手术已计费的收费项目编码（预览时标记"已计费"）。 */
    public List<String> listChargedSurgeryItemCodes(UUID surgeryCaseId) {
        return jdbc.sql("""
                        SELECT item_code FROM clinical_svc.charge_item
                         WHERE source_key LIKE 'SURGERY:' || :caseId || ':%'
                        """)
                .param("caseId", surgeryCaseId.toString())
                .query(String.class).list();
    }

    // ---------------------------------------------------------------- 会诊

    private static final String CONSULTATION_COLUMNS = """
            id, consultation_no, visit_id, patient_id, patient_no, from_dept_id, to_dept_id,
            reason, status, requested_by, requested_at, responded_by, responded_at,
            rejected_reason, completed_by, completed_at, conclusion, cancelled_by, cancelled_at,
            cancelled_reason, version, created_at
            """;

    private static final RowMapper<Consultation> CONSULTATION_MAPPER = (rs, i) -> new Consultation(
            rs.getObject("id", UUID.class), rs.getString("consultation_no"),
            rs.getObject("visit_id", UUID.class), rs.getObject("patient_id", UUID.class),
            rs.getString("patient_no"), rs.getObject("from_dept_id", UUID.class),
            rs.getObject("to_dept_id", UUID.class), rs.getString("reason"),
            rs.getString("status"), rs.getString("requested_by"), instantOf(rs, "requested_at"),
            rs.getString("responded_by"), instantOf(rs, "responded_at"),
            rs.getString("rejected_reason"), rs.getString("completed_by"),
            instantOf(rs, "completed_at"), rs.getString("conclusion"),
            rs.getString("cancelled_by"), instantOf(rs, "cancelled_at"),
            rs.getString("cancelled_reason"), rs.getLong("version"), instantOf(rs, "created_at"));

    public String nextConsultationNo() {
        Long seq = jdbc.sql("SELECT nextval(CAST(:seq AS regclass))")
                .param("seq", "clinical_svc.consultation_no_seq").query(Long.class).single();
        return "CS" + BusinessTime.today().format(DateTimeFormatter.BASIC_ISO_DATE)
                + String.format("%06d", seq);
    }

    public void insertConsultation(Consultation c) {
        jdbc.sql("""
                        INSERT INTO clinical_svc.consultation_request
                            (id, consultation_no, visit_id, patient_id, patient_no, from_dept_id,
                             to_dept_id, reason, status, requested_by)
                        VALUES (:id, :no, :visitId, :patientId, :patientNo, :fromDept,
                                :toDept, :reason, :status, :requestedBy)
                        """)
                .param("id", c.id()).param("no", c.consultationNo())
                .param("visitId", c.visitId()).param("patientId", c.patientId())
                .param("patientNo", c.patientNo()).param("fromDept", c.fromDeptId())
                .param("toDept", c.toDeptId()).param("reason", c.reason())
                .param("status", c.status()).param("requestedBy", c.requestedBy()).update();
    }

    public Optional<Consultation> findConsultation(UUID id) {
        return jdbc.sql("SELECT " + CONSULTATION_COLUMNS
                        + " FROM clinical_svc.consultation_request WHERE id = ?")
                .param(id).query(CONSULTATION_MAPPER).optional();
    }

    /** 加行锁读取会诊单；必须在事务内调用（状态迁移用它串行化并发受理/撤回）。 */
    public Optional<Consultation> lockConsultation(UUID id) {
        return jdbc.sql("SELECT " + CONSULTATION_COLUMNS
                        + " FROM clinical_svc.consultation_request WHERE id = ? FOR UPDATE")
                .param(id).query(CONSULTATION_MAPPER).optional();
    }

    public List<Consultation> listConsultationsByVisit(UUID visitId, int limit) {
        return jdbc.sql("SELECT " + CONSULTATION_COLUMNS
                        + """
                           FROM clinical_svc.consultation_request
                          WHERE visit_id = :visitId
                          ORDER BY requested_at DESC
                          LIMIT :limit
                          """)
                .param("visitId", visitId).param("limit", limit)
                .query(CONSULTATION_MAPPER).list();
    }

    /** 全院的会诊列表（仅全院范围角色使用，例如质控查看会诊落实情况）。 */
    public List<Consultation> listConsultations(String status, int limit) {
        return jdbc.sql("SELECT " + CONSULTATION_COLUMNS
                        + """
                           FROM clinical_svc.consultation_request
                          WHERE (CAST(:status AS text) IS NULL OR status = CAST(:status AS text))
                          ORDER BY requested_at DESC
                          LIMIT :limit
                          """)
                .param("status", status).param("limit", limit)
                .query(CONSULTATION_MAPPER).list();
    }

    /** 会诊收件箱：受邀科室待处理/进行中的会诊。 */
    public List<Consultation> listConsultationsInbox(java.util.Collection<UUID> deptIds,
                                                     String status, int limit) {
        if (deptIds == null || deptIds.isEmpty()) {
            return List.of();
        }
        return jdbc.sql("SELECT " + CONSULTATION_COLUMNS
                        + """
                           FROM clinical_svc.consultation_request
                          WHERE to_dept_id IN (:deptIds)
                            AND (CAST(:status AS text) IS NULL OR status = CAST(:status AS text))
                          ORDER BY requested_at DESC
                          LIMIT :limit
                          """)
                .param("deptIds", deptIds).param("status", status).param("limit", limit)
                .query(CONSULTATION_MAPPER).list();
    }

    /** 接受：仅 REQUESTED 可受理。 */
    public boolean acceptConsultation(UUID id, String actor, long expectedVersion) {
        return jdbc.sql("""
                        UPDATE clinical_svc.consultation_request
                           SET status = 'ACCEPTED', responded_by = :actor, responded_at = now(),
                               version = version + 1, updated_at = now()
                         WHERE id = :id AND status = 'REQUESTED' AND version = :version
                        """)
                .param("id", id).param("actor", actor).param("version", expectedVersion)
                .update() > 0;
    }

    /** 拒绝：仅 REQUESTED 可拒绝，必须带理由。 */
    public boolean rejectConsultation(UUID id, String actor, String reason, long expectedVersion) {
        return jdbc.sql("""
                        UPDATE clinical_svc.consultation_request
                           SET status = 'REJECTED', responded_by = :actor, responded_at = now(),
                               rejected_reason = :reason, version = version + 1, updated_at = now()
                         WHERE id = :id AND status = 'REQUESTED' AND version = :version
                        """)
                .param("id", id).param("actor", actor).param("reason", reason)
                .param("version", expectedVersion).update() > 0;
    }

    /** 完成：仅 ACCEPTED 可完成，需填写会诊意见。 */
    public boolean completeConsultation(UUID id, String actor, String conclusion,
                                        long expectedVersion) {
        return jdbc.sql("""
                        UPDATE clinical_svc.consultation_request
                           SET status = 'COMPLETED', completed_by = :actor, completed_at = now(),
                               conclusion = :conclusion, version = version + 1, updated_at = now()
                         WHERE id = :id AND status = 'ACCEPTED' AND version = :version
                        """)
                .param("id", id).param("actor", actor).param("conclusion", conclusion)
                .param("version", expectedVersion).update() > 0;
    }

    /** 撤回：主诊科室可撤销进行中的会诊（REQUESTED / ACCEPTED）。 */
    public boolean cancelConsultation(UUID id, String actor, String reason, long expectedVersion) {
        return jdbc.sql("""
                        UPDATE clinical_svc.consultation_request
                           SET status = 'CANCELLED', cancelled_by = :actor, cancelled_at = now(),
                               cancelled_reason = :reason, version = version + 1, updated_at = now()
                         WHERE id = :id AND status IN ('REQUESTED', 'ACCEPTED')
                           AND version = :version
                        """)
                .param("id", id).param("actor", actor).param("reason", reason)
                .param("version", expectedVersion).update() > 0;
    }

    /** 该就诊是否有仍在进行、且邀请指定科室的会诊（跨科室只读授权的判定依据）。 */
    public boolean existsActiveConsultation(UUID visitId, java.util.Collection<UUID> deptIds) {
        if (deptIds == null || deptIds.isEmpty()) {
            return false;
        }
        return jdbc.sql("""
                        SELECT 1 FROM clinical_svc.consultation_request
                         WHERE visit_id = :visitId
                           AND to_dept_id IN (:deptIds)
                           AND status IN ('REQUESTED', 'ACCEPTED')
                         LIMIT 1
                        """)
                .param("visitId", visitId).param("deptIds", deptIds)
                .query(Integer.class).optional().isPresent();
    }

    /**
     * 转科时批量撤回该就诊的全部进行中会诊，返回撤回条数。
     *
     * <p>授权来源是"旧主诊科室发起的会诊"，转科后这个前提不存在：
     * 不撤回的话，受邀科室会继续持有该就诊的只读权限，而新主诊科室却不知情。
     */
    public int cancelActiveConsultationsForVisit(UUID visitId, String actor, String reason) {
        return jdbc.sql("""
                        UPDATE clinical_svc.consultation_request
                           SET status = 'CANCELLED', cancelled_by = :actor, cancelled_at = now(),
                               cancelled_reason = :reason, version = version + 1, updated_at = now()
                         WHERE visit_id = :visitId AND status IN ('REQUESTED', 'ACCEPTED')
                        """)
                .param("visitId", visitId).param("actor", actor).param("reason", reason)
                .update();
    }

    // ---------------------------------------------------------------- 转科

    private static final String VISIT_TRANSFER_COLUMNS = """
            id, visit_id, from_dept_id, to_dept_id, reason, transferred_by, transferred_at, version
            """;

    private static final RowMapper<VisitTransfer> VISIT_TRANSFER_MAPPER = (rs, i) -> new VisitTransfer(
            rs.getObject("id", UUID.class), rs.getObject("visit_id", UUID.class),
            rs.getObject("from_dept_id", UUID.class), rs.getObject("to_dept_id", UUID.class),
            rs.getString("reason"), rs.getString("transferred_by"),
            instantOf(rs, "transferred_at"), rs.getLong("version"));

    public void insertVisitTransfer(VisitTransfer t) {
        jdbc.sql("""
                        INSERT INTO clinical_svc.visit_transfer
                            (id, visit_id, from_dept_id, to_dept_id, reason, transferred_by)
                        VALUES (:id, :visitId, :fromDept, :toDept, :reason, :by)
                        """)
                .param("id", t.id()).param("visitId", t.visitId())
                .param("fromDept", t.fromDeptId()).param("toDept", t.toDeptId())
                .param("reason", t.reason()).param("by", t.transferredBy()).update();
    }

    public List<VisitTransfer> listTransfersByVisit(UUID visitId, int limit) {
        return jdbc.sql("SELECT " + VISIT_TRANSFER_COLUMNS
                        + """
                           FROM clinical_svc.visit_transfer
                          WHERE visit_id = :visitId
                          ORDER BY transferred_at DESC
                          LIMIT :limit
                          """)
                .param("visitId", visitId).param("limit", limit)
                .query(VISIT_TRANSFER_MAPPER).list();
    }

    // ---------------------------------------------------------------- 护理排班（G26）

    private static final RowMapper<NursingShift> NURSING_SHIFT_MAPPER = (rs, i) ->
            new NursingShift(rs.getObject("id", UUID.class), rs.getString("code"),
                    rs.getString("name"), rs.getTime("start_time").toLocalTime(),
                    rs.getTime("end_time").toLocalTime(), rs.getBoolean("cross_midnight"),
                    rs.getBoolean("enabled"), rs.getLong("version"));

    private static final RowMapper<NursingSchedule> NURSING_SCHEDULE_MAPPER = (rs, i) ->
            new NursingSchedule(rs.getObject("id", UUID.class),
                    rs.getObject("dept_id", UUID.class), rs.getString("dept_name"),
                    rs.getString("shift_code"), rs.getString("shift_name"),
                    rs.getDate("work_date").toLocalDate(), rs.getString("nurse_name"),
                    rs.getString("nurse_no"), rs.getString("status"), rs.getString("note"),
                    rs.getString("created_by"), rs.getTimestamp("created_at").toInstant(),
                    rs.getString("cancelled_by"),
                    rs.getTimestamp("cancelled_at") == null ? null
                            : rs.getTimestamp("cancelled_at").toInstant(),
                    rs.getString("cancel_reason"), rs.getLong("version"));

    private static final String NURSING_SCHEDULE_COLUMNS = """
            s.id, s.dept_id, d.name AS dept_name, s.shift_code, sh.name AS shift_name,
            s.work_date, s.nurse_name, s.nurse_no, s.status, s.note, s.created_by,
            s.created_at, s.cancelled_by, s.cancelled_at, s.cancel_reason, s.version
            """;

    public List<NursingShift> listNursingShifts() {
        return jdbc.sql("""
                        SELECT id, code, name, start_time, end_time, cross_midnight, enabled, version
                          FROM clinical_svc.nursing_shift
                         WHERE enabled
                         ORDER BY start_time, code
                        """).query(NURSING_SHIFT_MAPPER).list();
    }

    public Optional<NursingShift> findNursingShift(String code) {
        return jdbc.sql("""
                        SELECT id, code, name, start_time, end_time, cross_midnight, enabled, version
                          FROM clinical_svc.nursing_shift WHERE code = ?
                        """).param(code).query(NURSING_SHIFT_MAPPER).optional();
    }

    /** 插入排班；同科室同班次同护士或同护士同日已存在时返回 false（跳过）。 */
    public boolean insertNursingSchedule(UUID id, UUID deptId, String shiftCode,
                                         LocalDate workDate, String nurseName, String nurseNo,
                                         String note, String actor) {
        return jdbc.sql("""
                        INSERT INTO clinical_svc.nursing_schedule
                            (id, dept_id, shift_code, work_date, nurse_name, nurse_no, note,
                             created_by)
                        VALUES (:id, :deptId, :shiftCode, :workDate, :nurseName,
                                CAST(:nurseNo AS text), CAST(:note AS text), :actor)
                        ON CONFLICT DO NOTHING
                        """)
                .param("id", id).param("deptId", deptId).param("shiftCode", shiftCode)
                .param("workDate", workDate).param("nurseName", nurseName)
                .param("nurseNo", nurseNo).param("note", note).param("actor", actor)
                .update() > 0;
    }

    public List<NursingSchedule> listNursingSchedules(UUID deptId, LocalDate from,
                                                      LocalDate to) {
        return jdbc.sql("SELECT " + NURSING_SCHEDULE_COLUMNS + """
                          FROM clinical_svc.nursing_schedule s
                          JOIN clinical_svc.md_dept d ON d.id = s.dept_id
                          JOIN clinical_svc.nursing_shift sh ON sh.code = s.shift_code
                         WHERE (CAST(:deptId AS uuid) IS NULL OR s.dept_id = CAST(:deptId AS uuid))
                           AND s.work_date >= :fromDate AND s.work_date <= :toDate
                         ORDER BY s.work_date, sh.start_time, s.nurse_name
                        """)
                .param("deptId", deptId).param("fromDate", from).param("toDate", to)
                .query(NURSING_SCHEDULE_MAPPER).list();
    }

    public Optional<NursingSchedule> findNursingSchedule(UUID id) {
        return jdbc.sql("SELECT " + NURSING_SCHEDULE_COLUMNS + """
                          FROM clinical_svc.nursing_schedule s
                          JOIN clinical_svc.md_dept d ON d.id = s.dept_id
                          JOIN clinical_svc.nursing_shift sh ON sh.code = s.shift_code
                         WHERE s.id = ?
                        """).param(id).query(NURSING_SCHEDULE_MAPPER).optional();
    }

    /** 取消排班（CAS：版本一致且未取消）。 */
    public boolean cancelNursingSchedule(UUID id, String reason, String actor, long version) {
        return jdbc.sql("""
                        UPDATE clinical_svc.nursing_schedule
                           SET status = 'CANCELLED', cancel_reason = :reason,
                               cancelled_by = :actor, cancelled_at = now(),
                               version = version + 1, updated_at = now()
                         WHERE id = :id AND status <> 'CANCELLED' AND version = :version
                        """)
                .param("id", id).param("reason", reason).param("actor", actor)
                .param("version", version).update() > 0;
    }

    /** 发布某科室某日全部草稿排班，返回发布条数。 */
    public int publishNursingSchedules(UUID deptId, LocalDate day) {
        return jdbc.sql("""
                        UPDATE clinical_svc.nursing_schedule
                           SET status = 'PUBLISHED', version = version + 1, updated_at = now()
                         WHERE dept_id = :deptId AND work_date = :day AND status = 'DRAFT'
                        """)
                .param("deptId", deptId).param("day", day).update();
    }

    /** 护士工作量：统计周期内未取消排班的班次数与夜班数。 */
    public List<NursingWorkloadRow> nursingWorkload(UUID deptId, LocalDate from, LocalDate to) {
        return jdbc.sql("""
                        SELECT nurse_name, nurse_no, count(*) AS shifts,
                               count(*) FILTER (WHERE shift_code = 'NIGHT') AS night_shifts
                          FROM clinical_svc.nursing_schedule
                         WHERE (CAST(:deptId AS uuid) IS NULL OR dept_id = CAST(:deptId AS uuid))
                           AND work_date >= :fromDate AND work_date <= :toDate
                           AND status <> 'CANCELLED'
                         GROUP BY nurse_name, nurse_no
                         ORDER BY shifts DESC, nurse_name
                        """)
                .param("deptId", deptId).param("fromDate", from).param("toDate", to)
                .query((rs, i) -> new NursingWorkloadRow(rs.getString("nurse_name"),
                        rs.getString("nurse_no"), rs.getLong("shifts"),
                        rs.getLong("night_shifts"))).list();
    }

    /** 每日每班次覆盖人数（未取消排班）。 */
    public List<NursingCoverageRow> nursingCoverage(UUID deptId, LocalDate from, LocalDate to) {
        return jdbc.sql("""
                        SELECT work_date, shift_code, count(*) AS cnt
                          FROM clinical_svc.nursing_schedule
                         WHERE (CAST(:deptId AS uuid) IS NULL OR dept_id = CAST(:deptId AS uuid))
                           AND work_date >= :fromDate AND work_date <= :toDate
                           AND status <> 'CANCELLED'
                         GROUP BY work_date, shift_code
                         ORDER BY work_date, shift_code
                        """)
                .param("deptId", deptId).param("fromDate", from).param("toDate", to)
                .query((rs, i) -> new NursingCoverageRow(
                        rs.getDate("work_date").toLocalDate(), rs.getString("shift_code"),
                        rs.getLong("cnt"))).list();
    }

    // ---------------------------------------------------------------- 运营洞察（G27）

    /**
     * 病区入出量：只统计本期有入院或出院事件的病区，按病区聚合。
     *
     * <p>用于「科室/病区下钻」与同比：同一方法对本期与去年同期各调用一次。
     */
    public List<WardAdmissionRow> admissionByWard(OffsetDateTime from, OffsetDateTime to) {
        return jdbc.sql("""
                        SELECT w.code AS ward_code, w.name AS ward_name, d.name AS dept_name,
                               count(*) FILTER (WHERE a.admitted_at >= :fromTs
                                                 AND a.admitted_at < :toTs) AS admitted,
                               count(*) FILTER (WHERE a.discharged_at >= :fromTs
                                                 AND a.discharged_at < :toTs) AS discharged
                          FROM clinical_svc.admission a
                          JOIN clinical_svc.ward w ON w.id = a.ward_id
                          JOIN clinical_svc.md_dept d ON d.id = w.dept_id
                         WHERE (a.admitted_at >= :fromTs AND a.admitted_at < :toTs)
                            OR (a.discharged_at >= :fromTs AND a.discharged_at < :toTs)
                         GROUP BY w.code, w.name, d.name
                         ORDER BY admitted DESC, w.code
                         LIMIT 50
                        """)
                .param("fromTs", from).param("toTs", to)
                .query((rs, i) -> new WardAdmissionRow(rs.getString("ward_code"),
                        rs.getString("ward_name"), rs.getString("dept_name"),
                        rs.getLong("admitted"), rs.getLong("discharged"))).list();
    }

    // ---------------------------------------------------------------- 临床路径（G29）

    private static final RowMapper<PathwayTemplate> PATHWAY_TEMPLATE_MAPPER = (rs, i) ->
            new PathwayTemplate(
                    rs.getObject("id", UUID.class), rs.getString("code"), rs.getString("name"),
                    rs.getString("diagnosis_code"), rs.getObject("dept_id", UUID.class),
                    rs.getString("dept_name"), rs.getInt("day_count"), rs.getString("status"),
                    rs.getString("created_by"), rs.getString("published_by"),
                    rs.getTimestamp("published_at") == null
                            ? null : rs.getTimestamp("published_at").toInstant(),
                    rs.getLong("version"), rs.getTimestamp("created_at").toInstant());

    private static final RowMapper<PathwayNode> PATHWAY_NODE_MAPPER = (rs, i) ->
            new PathwayNode(
                    rs.getObject("id", UUID.class), rs.getObject("template_id", UUID.class),
                    rs.getInt("day_no"), rs.getInt("seq"), rs.getString("name"),
                    rs.getString("category"), rs.getBoolean("required"),
                    rs.getString("description"));

    private static final RowMapper<PathwayInstance> PATHWAY_INSTANCE_MAPPER = (rs, i) ->
            new PathwayInstance(
                    rs.getObject("id", UUID.class), rs.getString("instance_no"),
                    rs.getObject("template_id", UUID.class), rs.getString("template_name"),
                    rs.getObject("visit_id", UUID.class), rs.getString("visit_no"),
                    rs.getObject("patient_id", UUID.class), rs.getString("patient_no"),
                    rs.getObject("dept_id", UUID.class), rs.getString("dept_name"),
                    rs.getString("status"), rs.getInt("current_day"),
                    rs.getDate("start_date").toLocalDate(),
                    rs.getDate("end_date") == null ? null : rs.getDate("end_date").toLocalDate(),
                    rs.getString("exit_reason"), rs.getString("created_by"), rs.getLong("version"),
                    rs.getTimestamp("created_at").toInstant());

    private static final RowMapper<PathwayTask> PATHWAY_TASK_MAPPER = (rs, i) ->
            new PathwayTask(
                    rs.getObject("id", UUID.class), rs.getObject("instance_id", UUID.class),
                    rs.getObject("node_id", UUID.class), rs.getInt("day_no"), rs.getInt("seq"),
                    rs.getString("name"), rs.getString("category"), rs.getBoolean("required"),
                    rs.getString("status"), rs.getString("done_by"),
                    rs.getTimestamp("done_at") == null
                            ? null : rs.getTimestamp("done_at").toInstant(),
                    rs.getString("note"));

    private static final String PATHWAY_INSTANCE_SELECT = """
            SELECT i.id, i.instance_no, i.template_id, t.name AS template_name,
                   i.visit_id, v.visit_no, i.patient_id, i.patient_no, i.dept_id,
                   d.name AS dept_name, i.status, i.current_day, i.start_date, i.end_date,
                   i.exit_reason, i.created_by, i.version, i.created_at
              FROM clinical_svc.pathway_instance i
              JOIN clinical_svc.pathway_template t ON t.id = i.template_id
              JOIN clinical_svc.visit v ON v.id = i.visit_id
              LEFT JOIN clinical_svc.md_dept d ON d.id = i.dept_id
            """;

    private static final String PATHWAY_TEMPLATE_SELECT = """
            SELECT t.id, t.code, t.name, t.diagnosis_code, t.dept_id, d.name AS dept_name,
                   t.day_count, t.status, t.created_by, t.published_by, t.published_at,
                   t.version, t.created_at
              FROM clinical_svc.pathway_template t
              LEFT JOIN clinical_svc.md_dept d ON d.id = t.dept_id
            """;

    public String nextPathwayInstanceNo() {
        return nextNo("clinical_svc.pathway_instance_no_seq", "CP");
    }

    public boolean pathwayTemplateCodeExists(String code) {
        return jdbc.sql("""
                        SELECT EXISTS (SELECT 1 FROM clinical_svc.pathway_template WHERE code = ?)
                        """)
                .param(code).query(Boolean.class).single();
    }

    public void insertPathwayTemplate(PathwayTemplate t) {
        jdbc.sql("""
                        INSERT INTO clinical_svc.pathway_template
                            (id, code, name, diagnosis_code, dept_id, day_count, status, created_by)
                        VALUES (:id, :code, :name, :diagnosisCode, :deptId, :dayCount, :status,
                                :createdBy)
                        """)
                .param("id", t.id()).param("code", t.code()).param("name", t.name())
                .param("diagnosisCode", t.diagnosisCode()).param("deptId", t.deptId())
                .param("dayCount", t.dayCount()).param("status", t.status())
                .param("createdBy", t.createdBy()).update();
    }

    public void insertPathwayNode(PathwayNode n) {
        jdbc.sql("""
                        INSERT INTO clinical_svc.pathway_node
                            (id, template_id, day_no, seq, name, category, required, description)
                        VALUES (:id, :templateId, :dayNo, :seq, :name, :category, :required,
                                :description)
                        """)
                .param("id", n.id()).param("templateId", n.templateId())
                .param("dayNo", n.dayNo()).param("seq", n.seq()).param("name", n.name())
                .param("category", n.category()).param("required", n.required())
                .param("description", n.description()).update();
    }

    public Optional<PathwayTemplate> findPathwayTemplate(UUID id) {
        return jdbc.sql(PATHWAY_TEMPLATE_SELECT + " WHERE t.id = ?")
                .param(id).query(PATHWAY_TEMPLATE_MAPPER).optional();
    }

    public Optional<PathwayTemplate> lockPathwayTemplate(UUID id) {
        return jdbc.sql(PATHWAY_TEMPLATE_SELECT + " WHERE t.id = ? FOR UPDATE OF t")
                .param(id).query(PATHWAY_TEMPLATE_MAPPER).optional();
    }

    /** 模板列表：不传状态时返回全部，按状态权重 + 创建时间倒序。 */
    public List<PathwayTemplate> listPathwayTemplates(String status, String keyword, int limit) {
        return jdbc.sql(PATHWAY_TEMPLATE_SELECT + """
                         WHERE (CAST(:status AS text) IS NULL OR t.status = CAST(:status AS text))
                           AND (CAST(:keyword AS text) IS NULL
                                OR t.code ILIKE '%' || CAST(:keyword AS text) || '%'
                                OR t.name ILIKE '%' || CAST(:keyword AS text) || '%')
                         ORDER BY CASE t.status WHEN 'PUBLISHED' THEN 0 WHEN 'DRAFT' THEN 1
                                               ELSE 2 END,
                                  t.created_at DESC
                         LIMIT :limit
                        """)
                .param("status", status).param("keyword", keyword)
                .param("limit", limit).query(PATHWAY_TEMPLATE_MAPPER).list();
    }

    public List<PathwayNode> listPathwayNodes(UUID templateId) {
        return jdbc.sql("""
                        SELECT id, template_id, day_no, seq, name, category, required, description
                          FROM clinical_svc.pathway_node
                         WHERE template_id = ?
                         ORDER BY day_no, seq
                        """)
                .param(templateId).query(PATHWAY_NODE_MAPPER).list();
    }

    public boolean publishPathwayTemplate(UUID id, String actor, long expectedVersion) {
        return jdbc.sql("""
                        UPDATE clinical_svc.pathway_template
                           SET status = 'PUBLISHED', published_by = :actor, published_at = now(),
                               version = version + 1, updated_at = now()
                         WHERE id = :id AND status = 'DRAFT' AND version = :version
                        """)
                .param("id", id).param("actor", actor).param("version", expectedVersion)
                .update() > 0;
    }

    public boolean disablePathwayTemplate(UUID id, long expectedVersion) {
        return jdbc.sql("""
                        UPDATE clinical_svc.pathway_template
                           SET status = 'DISABLED', version = version + 1, updated_at = now()
                         WHERE id = :id AND status = 'PUBLISHED' AND version = :version
                        """)
                .param("id", id).param("version", expectedVersion).update() > 0;
    }

    /** 按就诊号定位就诊（入径时允许直接输入住院号）。 */
    public Optional<Visit> findVisitByNo(String visitNo) {
        return jdbc.sql("""
                        SELECT id, visit_no, patient_id, patient_no, dept_id, doctor_id, slot_id,
                               visit_type, status, visit_date, version
                          FROM clinical_svc.visit WHERE visit_no = ?
                        """)
                .param(visitNo).query(VISIT_MAPPER).optional();
    }

    public Optional<PathwayInstance> findPathwayInstance(UUID id) {
        return jdbc.sql(PATHWAY_INSTANCE_SELECT + " WHERE i.id = ?")
                .param(id).query(PATHWAY_INSTANCE_MAPPER).optional();
    }

    public Optional<PathwayInstance> lockPathwayInstance(UUID id) {
        return jdbc.sql(PATHWAY_INSTANCE_SELECT + " WHERE i.id = ? FOR UPDATE OF i")
                .param(id).query(PATHWAY_INSTANCE_MAPPER).optional();
    }

    public Optional<PathwayInstance> findActivePathwayInstanceByVisit(UUID visitId) {
        return jdbc.sql(PATHWAY_INSTANCE_SELECT
                        + " WHERE i.visit_id = ? AND i.status = 'ACTIVE'")
                .param(visitId).query(PATHWAY_INSTANCE_MAPPER).optional();
    }

    public List<PathwayInstance> listPathwayInstances(String status, UUID deptId, int limit) {
        return jdbc.sql(PATHWAY_INSTANCE_SELECT + """
                         WHERE (CAST(:status AS text) IS NULL OR i.status = CAST(:status AS text))
                           AND (CAST(:deptId AS uuid) IS NULL OR i.dept_id = CAST(:deptId AS uuid))
                         ORDER BY CASE i.status WHEN 'ACTIVE' THEN 0 ELSE 1 END,
                                  i.created_at DESC
                         LIMIT :limit
                        """)
                .param("status", status).param("deptId", deptId).param("limit", limit)
                .query(PATHWAY_INSTANCE_MAPPER).list();
    }

    public void insertPathwayInstance(PathwayInstance i) {
        jdbc.sql("""
                        INSERT INTO clinical_svc.pathway_instance
                            (id, instance_no, template_id, visit_id, patient_id, patient_no,
                             dept_id, status, current_day, start_date, created_by)
                        VALUES (:id, :no, :templateId, :visitId, :patientId, :patientNo,
                                :deptId, :status, :currentDay, :startDate, :createdBy)
                        """)
                .param("id", i.id()).param("no", i.instanceNo())
                .param("templateId", i.templateId()).param("visitId", i.visitId())
                .param("patientId", i.patientId()).param("patientNo", i.patientNo())
                .param("deptId", i.deptId()).param("status", i.status())
                .param("currentDay", i.currentDay()).param("startDate", i.startDate())
                .param("createdBy", i.createdBy()).update();
    }

    public void insertPathwayTask(PathwayTask t) {
        jdbc.sql("""
                        INSERT INTO clinical_svc.pathway_task
                            (id, instance_id, node_id, day_no, seq, name, category, required, status)
                        VALUES (:id, :instanceId, :nodeId, :dayNo, :seq, :name, :category,
                                :required, :status)
                        """)
                .param("id", t.id()).param("instanceId", t.instanceId())
                .param("nodeId", t.nodeId()).param("dayNo", t.dayNo()).param("seq", t.seq())
                .param("name", t.name()).param("category", t.category())
                .param("required", t.required()).param("status", t.status()).update();
    }

    public List<PathwayTask> listPathwayTasks(UUID instanceId) {
        return jdbc.sql("""
                        SELECT id, instance_id, node_id, day_no, seq, name, category, required,
                               status, done_by, done_at, note
                          FROM clinical_svc.pathway_task
                         WHERE instance_id = ?
                         ORDER BY day_no, seq
                        """)
                .param(instanceId).query(PATHWAY_TASK_MAPPER).list();
    }

    public Optional<PathwayTask> findPathwayTask(UUID id) {
        return jdbc.sql("""
                        SELECT id, instance_id, node_id, day_no, seq, name, category, required,
                               status, done_by, done_at, note
                          FROM clinical_svc.pathway_task WHERE id = ?
                        """)
                .param(id).query(PATHWAY_TASK_MAPPER).optional();
    }

    /** 任务流转（CAS：仅 PENDING 可流转，防重复点击）。 */
    public boolean updatePathwayTask(UUID id, UUID instanceId, String status,
                                     String actor, String note) {
        return jdbc.sql("""
                        UPDATE clinical_svc.pathway_task
                           SET status = :status, done_by = :actor, done_at = now(), note = :note
                         WHERE id = :id AND instance_id = :instanceId AND status = 'PENDING'
                        """)
                .param("id", id).param("instanceId", instanceId).param("status", status)
                .param("actor", actor).param("note", note).update() > 0;
    }

    /** 推进当前天：只前进不后退，避免漏做任务被"回拨"掩盖。 */
    public boolean advancePathwayInstance(UUID id, int currentDay) {
        return jdbc.sql("""
                        UPDATE clinical_svc.pathway_instance
                           SET current_day = :currentDay, updated_at = now()
                         WHERE id = :id AND status = 'ACTIVE' AND current_day < :currentDay
                        """)
                .param("id", id).param("currentDay", currentDay).update() > 0;
    }

    public int countPendingPathwayTasks(UUID instanceId) {
        return jdbc.sql("""
                        SELECT count(*) FROM clinical_svc.pathway_task
                         WHERE instance_id = ? AND status = 'PENDING'
                        """)
                .param(instanceId).query(Integer.class).single();
    }

    public boolean completePathwayInstance(UUID id, LocalDate endDate, long expectedVersion) {
        return jdbc.sql("""
                        UPDATE clinical_svc.pathway_instance
                           SET status = 'COMPLETED', end_date = :endDate,
                               version = version + 1, updated_at = now()
                         WHERE id = :id AND status = 'ACTIVE' AND version = :version
                        """)
                .param("id", id).param("endDate", endDate).param("version", expectedVersion)
                .update() > 0;
    }

    public boolean exitPathwayInstance(UUID id, String reason, LocalDate endDate,
                                       long expectedVersion) {
        return jdbc.sql("""
                        UPDATE clinical_svc.pathway_instance
                           SET status = 'EXITED', exit_reason = :reason, end_date = :endDate,
                               version = version + 1, updated_at = now()
                         WHERE id = :id AND status = 'ACTIVE' AND version = :version
                        """)
                .param("id", id).param("reason", reason).param("endDate", endDate)
                .param("version", expectedVersion).update() > 0;
    }

    // ---------------------------------------------------------------- 审计

    public void logChange(String actor, String action, String targetType, String targetId, String detailJson) {
        jdbc.sql("""
                        INSERT INTO clinical_svc.clinical_change_log
                            (actor, action, target_type, target_id, detail)
                        VALUES (:actor, :action, :targetType, :targetId, CAST(:detail AS jsonb))
                        """)
                .param("actor", actor).param("action", action)
                .param("targetType", targetType).param("targetId", targetId)
                .param("detail", detailJson == null ? "{}" : detailJson).update();
    }
}
