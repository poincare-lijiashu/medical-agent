package com.medassist.clinical.repo;

import com.medassist.clinical.domain.ClinicalModel.Charge;
import com.medassist.clinical.domain.ClinicalModel.ChargeItemDef;
import com.medassist.clinical.domain.ClinicalModel.Dept;
import com.medassist.clinical.domain.ClinicalModel.Slot;
import com.medassist.clinical.domain.ClinicalModel.Visit;
import com.medassist.clinical.domain.ClinicalModel.VisitOrder;
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
 *   <li>{@link #lockSlot} / {@link #lockVisit} / {@link #lockChargeByVisit} 使用
 *       {@code SELECT ... FOR UPDATE}，必须在事务内调用；</li>
 *   <li>{@link #occupySlot} 用 {@code booked < total} 作为数据库级守卫，
 *       即使应用层判断被绕过也不会超卖；</li>
 *   <li>{@link #insertChargeItem} 依赖 {@code order_id} 唯一约束实现"同一医嘱只计一次"。</li>
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
            rs.getString("status"), rs.getString("ordered_by"), rs.getLong("version"));

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
        Long seq = jdbc.sql("SELECT nextval('" + sequence + "')").query(Long.class).single();
        return prefix + LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE)
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
                             quantity, unit, unit_price, amount, status, ordered_by, version)
                        VALUES (:id, :orderNo, :visitId, :orderType, :itemCode, :itemName, :dose,
                                :frequency, :quantity, :unit, :unitPrice, :amount, :status, :orderedBy, 0)
                        """)
                .param("id", o.id()).param("orderNo", o.orderNo()).param("visitId", o.visitId())
                .param("orderType", o.orderType()).param("itemCode", o.itemCode())
                .param("itemName", o.itemName()).param("dose", o.dose())
                .param("frequency", o.frequency()).param("quantity", o.quantity())
                .param("unit", o.unit()).param("unitPrice", o.unitPrice())
                .param("amount", o.amount()).param("status", o.status())
                .param("orderedBy", o.orderedBy()).update();
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

    public Optional<Charge> findChargeByVisit(UUID visitId) {
        return jdbc.sql("""
                        SELECT id, charge_no, visit_id, total_amount, status, payment_method, paid_at, version
                          FROM clinical_svc.charge WHERE visit_id = ?
                        """).param(visitId).query(CHARGE_MAPPER).optional();
    }

    /** 加行锁读取收费单；必须在事务内调用。 */
    public Optional<Charge> lockChargeByVisit(UUID visitId) {
        return jdbc.sql("""
                        SELECT id, charge_no, visit_id, total_amount, status, payment_method, paid_at, version
                          FROM clinical_svc.charge WHERE visit_id = ? FOR UPDATE
                        """).param(visitId).query(CHARGE_MAPPER).optional();
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

    /** 费用明细：order_id 唯一 → 重复结算自动跳过（返回 false 表示已计过费）。 */
    public boolean insertChargeItem(UUID id, UUID chargeId, UUID orderId, String itemCode,
                                    String itemName, String category, BigDecimal amount) {
        return jdbc.sql("""
                        INSERT INTO clinical_svc.charge_item
                            (id, charge_id, order_id, item_code, item_name, category, amount)
                        VALUES (:id, :chargeId, :orderId, :itemCode, :itemName, :category, :amount)
                        ON CONFLICT (order_id) DO NOTHING
                        """)
                .param("id", id).param("chargeId", chargeId).param("orderId", orderId)
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

    /** 挂号费明细（没有对应医嘱，order_id 为 NULL）。 */
    public void insertChargeItemWithoutOrder(UUID id, UUID chargeId, String itemCode,
                                             String itemName, String category, BigDecimal amount) {
        jdbc.sql("""
                        INSERT INTO clinical_svc.charge_item
                            (id, charge_id, order_id, item_code, item_name, category, amount)
                        VALUES (:id, :chargeId, NULL, :itemCode, :itemName, :category, :amount)
                        """)
                .param("id", id).param("chargeId", chargeId).param("itemCode", itemCode)
                .param("itemName", itemName).param("category", category)
                .param("amount", amount).update();
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
