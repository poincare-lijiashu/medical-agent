package com.medassist.clinical.repo;

import com.medassist.clinical.domain.ClinicalModel.Admission;
import com.medassist.clinical.domain.ClinicalModel.AnesthesiaRecord;
import com.medassist.clinical.domain.ClinicalModel.AnesthesiaVital;
import com.medassist.clinical.domain.ClinicalModel.Bed;
import com.medassist.clinical.domain.ClinicalModel.Charge;
import com.medassist.clinical.domain.ClinicalModel.ChargeItemDef;
import com.medassist.clinical.domain.ClinicalModel.ChargeLine;
import com.medassist.clinical.domain.ClinicalModel.DailyDispenseRequest;
import com.medassist.clinical.domain.ClinicalModel.Dept;
import com.medassist.clinical.domain.ClinicalModel.Deposit;
import com.medassist.clinical.domain.ClinicalModel.DiagnosisDef;
import com.medassist.clinical.domain.ClinicalModel.IntraopNursing;
import com.medassist.clinical.domain.ClinicalModel.NursingExecution;
import com.medassist.clinical.domain.ClinicalModel.NursingExecutionCorrection;
import com.medassist.clinical.domain.ClinicalModel.OperatingRoom;
import com.medassist.clinical.domain.ClinicalModel.OperationCatalog;
import com.medassist.clinical.domain.ClinicalModel.OrderRefund;
import com.medassist.clinical.domain.ClinicalModel.PharmacyChargeLink;
import com.medassist.clinical.domain.ClinicalModel.PreopDiscussion;
import com.medassist.clinical.domain.ClinicalModel.PreopVisit;
import com.medassist.clinical.domain.ClinicalModel.RoomDayUsage;
import com.medassist.clinical.domain.ClinicalModel.Slot;
import com.medassist.clinical.domain.ClinicalModel.SurgeryCase;
import com.medassist.clinical.domain.ClinicalModel.SurgeryChargeRule;
import com.medassist.clinical.domain.ClinicalModel.SurgeryConsent;
import com.medassist.clinical.domain.ClinicalModel.SurgeryLevel;
import com.medassist.clinical.domain.ClinicalModel.SurgerySchedule;
import com.medassist.clinical.domain.ClinicalModel.TransferTask;
import com.medassist.clinical.domain.ClinicalModel.Visit;
import com.medassist.clinical.domain.ClinicalModel.VisitDiagnosis;
import com.medassist.clinical.domain.ClinicalModel.VisitOrder;
import com.medassist.clinical.domain.ClinicalModel.Ward;
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
import java.time.format.DateTimeFormatter;
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
