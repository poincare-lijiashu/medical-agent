package com.medassist.clinical.repo;

import com.medassist.clinical.domain.ClinicalModel.Admission;
import com.medassist.clinical.domain.ClinicalModel.Bed;
import com.medassist.clinical.domain.ClinicalModel.Charge;
import com.medassist.clinical.domain.ClinicalModel.ChargeItemDef;
import com.medassist.clinical.domain.ClinicalModel.ChargeLine;
import com.medassist.clinical.domain.ClinicalModel.Dept;
import com.medassist.clinical.domain.ClinicalModel.Deposit;
import com.medassist.clinical.domain.ClinicalModel.NursingExecution;
import com.medassist.clinical.domain.ClinicalModel.PharmacyChargeLink;
import com.medassist.clinical.domain.ClinicalModel.Slot;
import com.medassist.clinical.domain.ClinicalModel.Visit;
import com.medassist.clinical.domain.ClinicalModel.VisitOrder;
import com.medassist.clinical.domain.ClinicalModel.Ward;
import com.medassist.common.time.BusinessTime;
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
            rs.getString("frequency"), rs.getInt("quantity"), rs.getString("unit"),
            rs.getBigDecimal("unit_price"), rs.getBigDecimal("amount"),
            rs.getString("status"), rs.getString("ordered_by"), rs.getLong("version"),
            rs.getString("order_mode"),
            rs.getDate("start_date") == null ? null : rs.getDate("start_date").toLocalDate(),
            rs.getDate("stop_date") == null ? null : rs.getDate("stop_date").toLocalDate());

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
                             quantity, unit, unit_price, amount, status, ordered_by, version,
                             order_mode, start_date)
                        VALUES (:id, :orderNo, :visitId, :orderType, :itemCode, :itemName, :dose,
                                :frequency, :quantity, :unit, :unitPrice, :amount, :status, :orderedBy, 0,
                                :orderMode, :startDate)
                        """)
                .param("id", o.id()).param("orderNo", o.orderNo()).param("visitId", o.visitId())
                .param("orderType", o.orderType()).param("itemCode", o.itemCode())
                .param("itemName", o.itemName()).param("dose", o.dose())
                .param("frequency", o.frequency()).param("quantity", o.quantity())
                .param("unit", o.unit()).param("unitPrice", o.unitPrice())
                .param("amount", o.amount()).param("status", o.status())
                .param("orderedBy", o.orderedBy())
                .param("orderMode", o.orderMode())
                .param("startDate", o.startDate()).update();
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
                                  WHERE ne.order_id = o.id AND ne.exec_date = :date
                                    AND ne.status <> 'EXECUTED')
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

    public boolean cancelOrder(UUID orderId, UUID visitId) {
        return jdbc.sql("""
                        UPDATE clinical_svc.visit_order
                           SET status = 'CANCELLED', version = version + 1, updated_at = now()
                         WHERE id = :id AND visit_id = :visitId AND status = 'PLACED'
                        """)
                .param("id", orderId).param("visitId", visitId).update() > 0;
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
     */
    public List<ChargeLine> listChargeLines(UUID visitId) {
        return jdbc.sql("""
                        SELECT c.charge_no, ci.item_code, ci.item_name, ci.category,
                               ci.amount, c.status AS charge_status
                          FROM clinical_svc.charge c
                          JOIN clinical_svc.charge_item ci ON ci.charge_id = c.id
                         WHERE c.visit_id = ?
                         ORDER BY ci.created_at
                        """)
                .param(visitId)
                .query((rs, i) -> new ChargeLine(
                        rs.getString("charge_no"), rs.getString("item_code"),
                        rs.getString("item_name"), rs.getString("category"),
                        rs.getBigDecimal("amount"), rs.getString("charge_status")))
                .list();
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
                        VALUES (:id, :chargeId, :orderId, :sourceKey, :itemCode, :itemName, :category, :amount)
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
                        SELECT id, admission_id, visit_id, order_id, exec_date, status, executor, note, created_at
                          FROM clinical_svc.nursing_execution
                         WHERE order_id = :orderId AND exec_date = :date
                        """)
                .param("orderId", orderId).param("date", date)
                .query(NURSING_MAPPER).optional();
    }

    /** 某就诊的护理执行记录；date 为空时返回全部。 */
    public List<NursingExecution> listNursingExecutions(UUID visitId, LocalDate date) {
        if (date == null) {
            return jdbc.sql("""
                            SELECT id, admission_id, visit_id, order_id, exec_date, status, executor, note, created_at
                              FROM clinical_svc.nursing_execution
                             WHERE visit_id = ? ORDER BY exec_date DESC, created_at DESC
                            """).param(visitId).query(NURSING_MAPPER).list();
        }
        return jdbc.sql("""
                        SELECT id, admission_id, visit_id, order_id, exec_date, status, executor, note, created_at
                          FROM clinical_svc.nursing_execution
                         WHERE visit_id = :visitId AND exec_date = :date
                         ORDER BY created_at DESC
                        """)
                .param("visitId", visitId).param("date", date)
                .query(NURSING_MAPPER).list();
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
