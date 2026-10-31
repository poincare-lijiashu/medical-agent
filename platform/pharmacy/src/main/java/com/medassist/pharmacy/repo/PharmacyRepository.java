package com.medassist.pharmacy.repo;

import com.medassist.common.time.BusinessTime;
import com.medassist.pharmacy.domain.PharmacyModel.Dispense;
import com.medassist.pharmacy.domain.PharmacyModel.DispenseItem;
import com.medassist.pharmacy.domain.PharmacyModel.DispenseReturn;
import com.medassist.pharmacy.domain.PharmacyModel.Drug;
import com.medassist.pharmacy.domain.PharmacyModel.DrugBatch;
import com.medassist.pharmacy.domain.PharmacyModel.Prescription;
import com.medassist.pharmacy.domain.PharmacyModel.PrescriptionItem;
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
 * 药房仓储（JdbcClient 显式 SQL）。
 *
 * <p>并发语义（与临床核心一致）：
 * <ul>
 *   <li>{@link #lockPrescription} / {@link #lockDispense} / {@link #lockBatch} 使用
 *       {@code SELECT ... FOR UPDATE}，必须在事务内调用；</li>
 *   <li>{@link #deductBatch} 用 {@code quantity >= :qty} 作为数据库级守卫，
 *       即使应用层判断被绕过也不会把库存扣成负数；</li>
 *   <li>{@link #addReturnedQuantity} 用 {@code returned_quantity + :qty <= quantity}
 *       保证退药不会超过已发数量。</li>
 * </ul>
 */
@Repository
public class PharmacyRepository {

    private static final RowMapper<Drug> DRUG_MAPPER = (rs, i) -> new Drug(
            rs.getObject("id", UUID.class), rs.getString("drug_code"), rs.getString("name"),
            rs.getString("trade_name"), rs.getString("spec"), rs.getString("dosage_form"),
            rs.getString("unit"), rs.getString("package_unit"),
            rs.getObject("package_size") == null ? null : rs.getInt("package_size"),
            rs.getBigDecimal("price"), rs.getString("insurance_code"),
            rs.getString("insurance_category"), rs.getInt("antibiotic_level"),
            rs.getBoolean("narcotic"), rs.getBoolean("enabled"), rs.getLong("version"));

    private static final RowMapper<DrugBatch> BATCH_MAPPER = (rs, i) -> new DrugBatch(
            rs.getObject("id", UUID.class), rs.getObject("drug_id", UUID.class),
            rs.getString("drug_code"), rs.getString("drug_name"), rs.getString("batch_no"),
            rs.getDate("expire_date").toLocalDate(), rs.getInt("quantity"),
            rs.getString("supplier"), rs.getLong("version"));

    private static final RowMapper<Prescription> RX_MAPPER = (rs, i) -> new Prescription(
            rs.getObject("id", UUID.class), rs.getString("prescription_no"),
            rs.getObject("patient_id", UUID.class), rs.getString("patient_no"),
            rs.getObject("visit_id", UUID.class), rs.getString("visit_no"),
            rs.getObject("dept_id", UUID.class), rs.getString("prescriber"),
            rs.getString("status"), rs.getString("reviewer"),
            rs.getTimestamp("reviewed_at") == null ? null : rs.getTimestamp("reviewed_at").toInstant(),
            rs.getString("reject_reason"), rs.getBigDecimal("total_amount"),
            rs.getLong("version"), rs.getTimestamp("created_at").toInstant(),
            rs.getString("billing_mode"));

    private static final RowMapper<PrescriptionItem> RX_ITEM_MAPPER = (rs, i) -> new PrescriptionItem(
            rs.getObject("id", UUID.class), rs.getObject("prescription_id", UUID.class),
            rs.getObject("drug_id", UUID.class), rs.getString("drug_code"), rs.getString("drug_name"),
            rs.getString("spec"), rs.getInt("quantity"), rs.getString("unit"),
            rs.getString("dosage"), rs.getString("frequency"),
            rs.getObject("days") == null ? null : rs.getInt("days"),
            rs.getBigDecimal("unit_price"), rs.getBigDecimal("amount"));

    private static final RowMapper<Dispense> DISPENSE_MAPPER = (rs, i) -> new Dispense(
            rs.getObject("id", UUID.class), rs.getString("dispense_no"),
            rs.getObject("prescription_id", UUID.class), rs.getString("operator"),
            rs.getString("checker"), rs.getString("status"), rs.getBigDecimal("total_amount"),
            rs.getTimestamp("dispensed_at").toInstant(), rs.getLong("version"),
            rs.getString("billing_status"));

    private static final RowMapper<DispenseItem> DISPENSE_ITEM_MAPPER = (rs, i) -> new DispenseItem(
            rs.getObject("id", UUID.class), rs.getObject("dispense_id", UUID.class),
            rs.getObject("prescription_item_id", UUID.class), rs.getObject("drug_id", UUID.class),
            rs.getObject("batch_id", UUID.class), rs.getString("batch_no"),
            rs.getDate("expire_date").toLocalDate(), rs.getInt("quantity"),
            rs.getInt("returned_quantity"), rs.getBigDecimal("unit_price"),
            rs.getBigDecimal("amount"));

    private static final RowMapper<DispenseReturn> RETURN_MAPPER = (rs, i) -> new DispenseReturn(
            rs.getObject("id", UUID.class), rs.getObject("dispense_id", UUID.class),
            rs.getObject("dispense_item_id", UUID.class), rs.getInt("quantity"),
            rs.getString("reason"), rs.getString("operator"),
            rs.getTimestamp("created_at").toInstant());

    private final JdbcClient jdbc;

    public PharmacyRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    // ---------------------------------------------------------------- 单号

    public String nextPrescriptionNo() {
        return nextNo("pharmacy_svc.prescription_no_seq", "RX");
    }

    public String nextDispenseNo() {
        return nextNo("pharmacy_svc.dispense_no_seq", "DP");
    }

    private String nextNo(String sequence, String prefix) {
        Long seq = jdbc.sql("SELECT nextval(CAST(:seq AS regclass))")
                .param("seq", sequence).query(Long.class).single();
        return prefix + BusinessTime.today().format(DateTimeFormatter.BASIC_ISO_DATE)
                + String.format("%06d", seq);
    }

    // ---------------------------------------------------------------- 药品字典

    public Optional<Drug> findDrug(UUID id) {
        return jdbc.sql("""
                        SELECT id, drug_code, name, trade_name, spec, dosage_form, unit,
                               package_unit, package_size, price, insurance_code,
                               insurance_category, antibiotic_level, narcotic, enabled, version
                          FROM pharmacy_svc.md_drug WHERE id = ?
                        """).param(id).query(DRUG_MAPPER).optional();
    }

    public Optional<Drug> findDrugByCode(String drugCode) {
        return jdbc.sql("""
                        SELECT id, drug_code, name, trade_name, spec, dosage_form, unit,
                               package_unit, package_size, price, insurance_code,
                               insurance_category, antibiotic_level, narcotic, enabled, version
                          FROM pharmacy_svc.md_drug WHERE drug_code = ?
                        """).param(drugCode).query(DRUG_MAPPER).optional();
    }

    /** 药品检索：keyword 匹配名称/编码/商品名；enabled 为空返回全部。 */
    public List<Drug> listDrugs(String keyword, Boolean enabled, int limit) {
        String pattern = keyword == null || keyword.isBlank() ? null : "%" + keyword.trim() + "%";
        // 可空参数必须显式 CAST：PostgreSQL 无法从 "IS NULL" 推断绑定参数类型（实测 500）
        return jdbc.sql("""
                        SELECT id, drug_code, name, trade_name, spec, dosage_form, unit,
                               package_unit, package_size, price, insurance_code,
                               insurance_category, antibiotic_level, narcotic, enabled, version
                          FROM pharmacy_svc.md_drug
                         WHERE (CAST(:pattern AS text) IS NULL OR name ILIKE CAST(:pattern AS text)
                                OR drug_code ILIKE CAST(:pattern AS text)
                                OR COALESCE(trade_name, '') ILIKE CAST(:pattern AS text))
                           AND (CAST(:enabled AS boolean) IS NULL OR enabled = CAST(:enabled AS boolean))
                         ORDER BY drug_code
                         LIMIT :limit
                        """)
                .param("pattern", pattern)
                .param("enabled", enabled)
                .param("limit", limit)
                .query(DRUG_MAPPER).list();
    }

    public void insertDrug(Drug d) {
        jdbc.sql("""
                        INSERT INTO pharmacy_svc.md_drug
                            (id, drug_code, name, trade_name, spec, dosage_form, unit,
                             package_unit, package_size, price, insurance_code,
                             insurance_category, antibiotic_level, narcotic, enabled, version)
                        VALUES (:id, :code, :name, CAST(:tradeName AS text), :spec, :form, :unit,
                                CAST(:packageUnit AS text), CAST(:packageSize AS integer), :price,
                                CAST(:insuranceCode AS text),
                                :insuranceCategory, :antibioticLevel, :narcotic, true, 0)
                        """)
                .param("id", d.id()).param("code", d.drugCode()).param("name", d.name())
                .param("tradeName", d.tradeName()).param("spec", d.spec())
                .param("form", d.dosageForm()).param("unit", d.unit())
                .param("packageUnit", d.packageUnit()).param("packageSize", d.packageSize())
                .param("price", d.price()).param("insuranceCode", d.insuranceCode())
                .param("insuranceCategory", d.insuranceCategory())
                .param("antibioticLevel", d.antibioticLevel())
                .param("narcotic", d.narcotic()).update();
    }

    /** 调价（CAS：版本号必须一致；历史处方保存的是快照，不受影响）。 */
    public boolean updateDrugPrice(UUID id, BigDecimal price, long expectedVersion) {
        return jdbc.sql("""
                        UPDATE pharmacy_svc.md_drug
                           SET price = :price, version = version + 1, updated_at = now()
                         WHERE id = :id AND version = :version AND enabled = true
                        """)
                .param("price", price).param("id", id).param("version", expectedVersion)
                .update() > 0;
    }

    /** 启用/停用（CAS）。停用后不可开方，但已开处方仍可发药。 */
    public boolean setDrugEnabled(UUID id, boolean enabled, long expectedVersion) {
        return jdbc.sql("""
                        UPDATE pharmacy_svc.md_drug
                           SET enabled = :enabled, version = version + 1, updated_at = now()
                         WHERE id = :id AND version = :version
                        """)
                .param("enabled", enabled).param("id", id).param("version", expectedVersion)
                .update() > 0;
    }

    // ---------------------------------------------------------------- 库存批次

    public void insertBatch(UUID id, UUID drugId, String batchNo, LocalDate expireDate,
                            int quantity, String supplier) {
        jdbc.sql("""
                        INSERT INTO pharmacy_svc.drug_batch
                            (id, drug_id, batch_no, expire_date, quantity, supplier, version)
                        VALUES (:id, :drugId, :batchNo, :expireDate, :quantity, CAST(:supplier AS text), 0)
                        """)
                .param("id", id).param("drugId", drugId).param("batchNo", batchNo)
                .param("expireDate", expireDate).param("quantity", quantity)
                .param("supplier", supplier).update();
    }

    public Optional<DrugBatch> findBatch(UUID id) {
        return jdbc.sql("""
                        SELECT b.id, b.drug_id, d.drug_code, d.name AS drug_name, b.batch_no,
                               b.expire_date, b.quantity, b.supplier, b.version
                          FROM pharmacy_svc.drug_batch b
                          JOIN pharmacy_svc.md_drug d ON d.id = b.drug_id
                         WHERE b.id = ?
                        """).param(id).query(BATCH_MAPPER).optional();
    }

    /** 加行锁读取批次；必须在事务内调用（发药/退药前重新校验效期与数量）。 */
    public Optional<DrugBatch> lockBatch(UUID id) {
        return jdbc.sql("""
                        SELECT b.id, b.drug_id, d.drug_code, d.name AS drug_name, b.batch_no,
                               b.expire_date, b.quantity, b.supplier, b.version
                          FROM pharmacy_svc.drug_batch b
                          JOIN pharmacy_svc.md_drug d ON d.id = b.drug_id
                         WHERE b.id = ? FOR UPDATE OF b
                        """).param(id).query(BATCH_MAPPER).optional();
    }

    public List<DrugBatch> listBatchesByDrug(UUID drugId) {
        return jdbc.sql("""
                        SELECT b.id, b.drug_id, d.drug_code, d.name AS drug_name, b.batch_no,
                               b.expire_date, b.quantity, b.supplier, b.version
                          FROM pharmacy_svc.drug_batch b
                          JOIN pharmacy_svc.md_drug d ON d.id = b.drug_id
                         WHERE b.drug_id = ?
                         ORDER BY b.expire_date, b.batch_no
                        """).param(drugId).query(BATCH_MAPPER).list();
    }

    /** 可用批次（未过期且有余量），按效期升序 —— FEFO 取药顺序。 */
    public List<DrugBatch> listAvailableBatches(UUID drugId, LocalDate today) {
        return jdbc.sql("""
                        SELECT b.id, b.drug_id, d.drug_code, d.name AS drug_name, b.batch_no,
                               b.expire_date, b.quantity, b.supplier, b.version
                          FROM pharmacy_svc.drug_batch b
                          JOIN pharmacy_svc.md_drug d ON d.id = b.drug_id
                         WHERE b.drug_id = :drugId AND b.quantity > 0 AND b.expire_date >= :today
                         ORDER BY b.expire_date, b.batch_no
                        """)
                .param("drugId", drugId).param("today", today)
                .query(BATCH_MAPPER).list();
    }

    /** 原子扣减（数据库级守卫：余量不足则更新 0 行，不会出现负库存）。 */
    public boolean deductBatch(UUID batchId, int quantity) {
        return jdbc.sql("""
                        UPDATE pharmacy_svc.drug_batch
                           SET quantity = quantity - :qty, version = version + 1, updated_at = now()
                         WHERE id = :id AND quantity >= :qty
                        """)
                .param("qty", quantity).param("id", batchId).update() > 0;
    }

    /** 退药回补原批次。 */
    public boolean addBatchQuantity(UUID batchId, int quantity) {
        return jdbc.sql("""
                        UPDATE pharmacy_svc.drug_batch
                           SET quantity = quantity + :qty, version = version + 1, updated_at = now()
                         WHERE id = :id
                        """)
                .param("qty", quantity).param("id", batchId).update() > 0;
    }

    public int totalStock(UUID drugId) {
        Integer total = jdbc.sql("SELECT COALESCE(sum(quantity), 0) FROM pharmacy_svc.drug_batch WHERE drug_id = ?")
                .param(drugId).query(Integer.class).single();
        return total == null ? 0 : total;
    }

    /** 近效期与过期批次（库存预警）：只列有余量的批次。 */
    public List<DrugBatch> listExpiringBefore(LocalDate threshold) {
        return jdbc.sql("""
                        SELECT b.id, b.drug_id, d.drug_code, d.name AS drug_name, b.batch_no,
                               b.expire_date, b.quantity, b.supplier, b.version
                          FROM pharmacy_svc.drug_batch b
                          JOIN pharmacy_svc.md_drug d ON d.id = b.drug_id
                         WHERE b.quantity > 0 AND b.expire_date <= :threshold
                         ORDER BY b.expire_date, b.batch_no
                        """).param("threshold", threshold).query(BATCH_MAPPER).list();
    }

    // ---------------------------------------------------------------- 处方

    public void insertPrescription(Prescription p) {
        jdbc.sql("""
                        INSERT INTO pharmacy_svc.prescription
                            (id, prescription_no, patient_id, patient_no, visit_id, visit_no, dept_id,
                             prescriber, status, total_amount, version, billing_mode)
                        VALUES (:id, :no, :patientId, :patientNo, CAST(:visitId AS uuid),
                                CAST(:visitNo AS text), CAST(:deptId AS uuid),
                                :prescriber, :status, :total, 0, :billingMode)
                        """)
                .param("id", p.id()).param("no", p.prescriptionNo())
                .param("patientId", p.patientId()).param("patientNo", p.patientNo())
                .param("visitId", p.visitId()).param("visitNo", p.visitNo())
                .param("deptId", p.deptId()).param("prescriber", p.prescriber())
                .param("status", p.status()).param("total", p.totalAmount())
                .param("billingMode", p.billingMode()).update();
    }

    public void insertPrescriptionItem(PrescriptionItem item) {
        jdbc.sql("""
                        INSERT INTO pharmacy_svc.prescription_item
                            (id, prescription_id, drug_id, drug_code, drug_name, spec, quantity,
                             unit, dosage, frequency, days, unit_price, amount)
                        VALUES (:id, :rxId, :drugId, :code, :name, :spec, :qty,
                                :unit, CAST(:dosage AS text), CAST(:frequency AS text),
                                CAST(:days AS integer), :unitPrice, :amount)
                        """)
                .param("id", item.id()).param("rxId", item.prescriptionId())
                .param("drugId", item.drugId()).param("code", item.drugCode())
                .param("name", item.drugName()).param("spec", item.spec())
                .param("qty", item.quantity()).param("unit", item.unit())
                .param("dosage", item.dosage()).param("frequency", item.frequency())
                .param("days", item.days()).param("unitPrice", item.unitPrice())
                .param("amount", item.amount()).update();
    }

    public Optional<Prescription> findPrescription(UUID id) {
        return jdbc.sql("""
                        SELECT id, prescription_no, patient_id, patient_no, visit_id, visit_no, dept_id,
                               prescriber, status, reviewer, reviewed_at, reject_reason,
                               total_amount, version, created_at, billing_mode
                          FROM pharmacy_svc.prescription WHERE id = ?
                        """).param(id).query(RX_MAPPER).optional();
    }

    /** 加行锁读取处方；必须在事务内调用（审核/发药/退药）。 */
    public Optional<Prescription> lockPrescription(UUID id) {
        return jdbc.sql("""
                        SELECT id, prescription_no, patient_id, patient_no, visit_id, visit_no, dept_id,
                               prescriber, status, reviewer, reviewed_at, reject_reason,
                               total_amount, version, created_at, billing_mode
                          FROM pharmacy_svc.prescription WHERE id = ? FOR UPDATE
                        """).param(id).query(RX_MAPPER).optional();
    }

    public List<PrescriptionItem> listPrescriptionItems(UUID prescriptionId) {
        return jdbc.sql("""
                        SELECT id, prescription_id, drug_id, drug_code, drug_name, spec, quantity,
                               unit, dosage, frequency, days, unit_price, amount
                          FROM pharmacy_svc.prescription_item
                         WHERE prescription_id = ? ORDER BY created_at
                        """).param(prescriptionId).query(RX_ITEM_MAPPER).list();
    }

    /** 状态迁移（CAS：期望状态 + 版本号）。 */
    public boolean updatePrescriptionStatus(UUID id, String expected, String next, long expectedVersion,
                                            String reviewer, String rejectReason) {
        return jdbc.sql("""
                        UPDATE pharmacy_svc.prescription
                           SET status = :next, version = version + 1, updated_at = now(),
                               reviewer = COALESCE(CAST(:reviewer AS text), reviewer),
                               reviewed_at = CASE WHEN :next IN ('APPROVED', 'REJECTED') THEN now() ELSE reviewed_at END,
                               reject_reason = CAST(:reason AS text)
                         WHERE id = :id AND status = :expected AND version = :version
                        """)
                .param("next", next).param("reviewer", reviewer).param("reason", rejectReason)
                .param("id", id).param("expected", expected).param("version", expectedVersion)
                .update() > 0;
    }

    public List<Prescription> listPrescriptions(String status, UUID patientId, int limit) {
        return jdbc.sql("""
                        SELECT id, prescription_no, patient_id, patient_no, visit_id, visit_no, dept_id,
                               prescriber, status, reviewer, reviewed_at, reject_reason,
                               total_amount, version, created_at, billing_mode
                          FROM pharmacy_svc.prescription
                         WHERE (CAST(:status AS text) IS NULL OR status = CAST(:status AS text))
                           AND (CAST(:patientId AS uuid) IS NULL OR patient_id = CAST(:patientId AS uuid))
                         ORDER BY created_at DESC
                         LIMIT :limit
                        """)
                .param("status", status).param("patientId", patientId).param("limit", limit)
                .query(RX_MAPPER).list();
    }

    // ---------------------------------------------------------------- 发药

    public void insertDispense(Dispense d) {
        jdbc.sql("""
                        INSERT INTO pharmacy_svc.dispense
                            (id, dispense_no, prescription_id, operator, checker, status,
                             total_amount, version, billing_status)
                        VALUES (:id, :no, :rxId, :operator, CAST(:checker AS text), :status, :total, 0,
                                :billingStatus)
                        """)
                .param("id", d.id()).param("no", d.dispenseNo())
                .param("rxId", d.prescriptionId()).param("operator", d.operator())
                .param("checker", d.checker()).param("status", d.status())
                .param("total", d.totalAmount())
                .param("billingStatus", d.billingStatus()).update();
    }

    /** 更新发药计费状态（补偿重试/afterCommit 回写用；不走版本号，状态机由服务层约束）。 */
    public boolean updateDispenseBillingStatus(UUID id, String billingStatus) {
        return jdbc.sql("""
                        UPDATE pharmacy_svc.dispense
                           SET billing_status = :status, updated_at = now()
                         WHERE id = :id
                        """)
                .param("status", billingStatus).param("id", id).update() > 0;
    }

    public void insertDispenseItem(DispenseItem item) {
        jdbc.sql("""
                        INSERT INTO pharmacy_svc.dispense_item
                            (id, dispense_id, prescription_item_id, drug_id, batch_id, batch_no,
                             expire_date, quantity, returned_quantity, unit_price, amount)
                        VALUES (:id, :dispenseId, :rxItemId, :drugId, :batchId, :batchNo,
                                :expireDate, :qty, 0, :unitPrice, :amount)
                        """)
                .param("id", item.id()).param("dispenseId", item.dispenseId())
                .param("rxItemId", item.prescriptionItemId()).param("drugId", item.drugId())
                .param("batchId", item.batchId()).param("batchNo", item.batchNo())
                .param("expireDate", item.expireDate()).param("qty", item.quantity())
                .param("unitPrice", item.unitPrice()).param("amount", item.amount()).update();
    }

    public Optional<Dispense> findDispense(UUID id) {
        return jdbc.sql("""
                        SELECT id, dispense_no, prescription_id, operator, checker, status,
                               total_amount, dispensed_at, version, billing_status
                          FROM pharmacy_svc.dispense WHERE id = ?
                        """).param(id).query(DISPENSE_MAPPER).optional();
    }

    public Optional<Dispense> findDispenseByPrescription(UUID prescriptionId) {
        return jdbc.sql("""
                        SELECT id, dispense_no, prescription_id, operator, checker, status,
                               total_amount, dispensed_at, version, billing_status
                          FROM pharmacy_svc.dispense WHERE prescription_id = ?
                        """).param(prescriptionId).query(DISPENSE_MAPPER).optional();
    }

    public Optional<Dispense> lockDispense(UUID id) {
        return jdbc.sql("""
                        SELECT id, dispense_no, prescription_id, operator, checker, status,
                               total_amount, dispensed_at, version, billing_status
                          FROM pharmacy_svc.dispense WHERE id = ? FOR UPDATE
                        """).param(id).query(DISPENSE_MAPPER).optional();
    }

    public List<DispenseItem> listDispenseItems(UUID dispenseId) {
        return jdbc.sql("""
                        SELECT id, dispense_id, prescription_item_id, drug_id, batch_id, batch_no,
                               expire_date, quantity, returned_quantity, unit_price, amount
                          FROM pharmacy_svc.dispense_item
                         WHERE dispense_id = ? ORDER BY created_at
                        """).param(dispenseId).query(DISPENSE_ITEM_MAPPER).list();
    }

    public Optional<DispenseItem> findDispenseItem(UUID id) {
        return jdbc.sql("""
                        SELECT id, dispense_id, prescription_item_id, drug_id, batch_id, batch_no,
                               expire_date, quantity, returned_quantity, unit_price, amount
                          FROM pharmacy_svc.dispense_item WHERE id = ?
                        """).param(id).query(DISPENSE_ITEM_MAPPER).optional();
    }

    public boolean updateDispenseStatus(UUID id, String status, long expectedVersion) {
        return jdbc.sql("""
                        UPDATE pharmacy_svc.dispense
                           SET status = :status, version = version + 1, updated_at = now()
                         WHERE id = :id AND version = :version
                        """)
                .param("status", status).param("id", id).param("version", expectedVersion)
                .update() > 0;
    }

    /** 累加已退数量（数据库级守卫：不得超过已发数量）。 */
    public boolean addReturnedQuantity(UUID dispenseItemId, int quantity) {
        return jdbc.sql("""
                        UPDATE pharmacy_svc.dispense_item
                           SET returned_quantity = returned_quantity + :qty
                         WHERE id = :id AND returned_quantity + :qty <= quantity
                        """)
                .param("qty", quantity).param("id", dispenseItemId).update() > 0;
    }

    public void insertReturn(DispenseReturn r) {
        jdbc.sql("""
                        INSERT INTO pharmacy_svc.dispense_return
                            (id, dispense_id, dispense_item_id, quantity, reason, operator)
                        VALUES (:id, :dispenseId, :itemId, :qty, :reason, :operator)
                        """)
                .param("id", r.id()).param("dispenseId", r.dispenseId())
                .param("itemId", r.dispenseItemId()).param("qty", r.quantity())
                .param("reason", r.reason()).param("operator", r.operator()).update();
    }

    public List<DispenseReturn> listReturns(UUID dispenseId) {
        return jdbc.sql("""
                        SELECT id, dispense_id, dispense_item_id, quantity, reason, operator, created_at
                          FROM pharmacy_svc.dispense_return
                         WHERE dispense_id = ? ORDER BY created_at
                        """).param(dispenseId).query(RETURN_MAPPER).list();
    }

    // ---------------------------------------------------------------- 审计

    public void logChange(String actor, String action, String targetType, String targetId, String detailJson) {
        jdbc.sql("""
                        INSERT INTO pharmacy_svc.pharmacy_change_log
                            (actor, action, target_type, target_id, detail)
                        VALUES (:actor, :action, :targetType, :targetId, CAST(:detail AS jsonb))
                        """)
                .param("actor", actor).param("action", action)
                .param("targetType", targetType).param("targetId", targetId)
                .param("detail", detailJson == null ? "{}" : detailJson).update();
    }
}
