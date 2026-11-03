package com.medassist.pharmacy.repo;

import com.medassist.common.time.BusinessTime;
import com.medassist.pharmacy.domain.PharmacyModel.Dispense;
import com.medassist.pharmacy.domain.PharmacyModel.DispenseItem;
import com.medassist.pharmacy.domain.PharmacyModel.DispenseReturn;
import com.medassist.pharmacy.domain.PharmacyModel.Drug;
import com.medassist.pharmacy.domain.PharmacyModel.DrugBatch;
import com.medassist.pharmacy.domain.PharmacyModel.NarcoticPrescriber;
import com.medassist.pharmacy.domain.PharmacyModel.NarcoticRegister;
import com.medassist.pharmacy.domain.PharmacyModel.Prescription;
import com.medassist.pharmacy.domain.PharmacyModel.PrescriptionAudit;
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
            rs.getBoolean("narcotic"), rs.getBoolean("enabled"), rs.getLong("version"),
            rs.getBigDecimal("max_daily_dose"), rs.getBoolean("pediatric_contraindicated"),
            rs.getBoolean("elderly_caution"));

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
            rs.getString("billing_mode"), rs.getObject("source_order_id", UUID.class),
            rs.getDate("supply_date") == null ? null : rs.getDate("supply_date").toLocalDate());

    private static final RowMapper<PrescriptionItem> RX_ITEM_MAPPER = (rs, i) -> new PrescriptionItem(
            rs.getObject("id", UUID.class), rs.getObject("prescription_id", UUID.class),
            rs.getObject("drug_id", UUID.class), rs.getString("drug_code"), rs.getString("drug_name"),
            rs.getString("spec"), rs.getInt("quantity"), rs.getString("unit"),
            rs.getString("dosage"), rs.getString("frequency"),
            rs.getObject("days") == null ? null : rs.getInt("days"),
            rs.getBigDecimal("unit_price"), rs.getBigDecimal("amount"),
            rs.getBigDecimal("single_dose"), rs.getString("dose_unit"),
            rs.getObject("times_per_day") == null ? null : rs.getInt("times_per_day"),
            rs.getBigDecimal("daily_dose"));

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
                               insurance_category, antibiotic_level, narcotic, enabled, version, max_daily_dose, pediatric_contraindicated, elderly_caution
                          FROM pharmacy_svc.md_drug WHERE id = ?
                        """).param(id).query(DRUG_MAPPER).optional();
    }

    public Optional<Drug> findDrugByCode(String drugCode) {
        return jdbc.sql("""
                        SELECT id, drug_code, name, trade_name, spec, dosage_form, unit,
                               package_unit, package_size, price, insurance_code,
                               insurance_category, antibiotic_level, narcotic, enabled, version, max_daily_dose, pediatric_contraindicated, elderly_caution
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
                               insurance_category, antibiotic_level, narcotic, enabled, version, max_daily_dose, pediatric_contraindicated, elderly_caution
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
                             insurance_category, antibiotic_level, narcotic, enabled, version, max_daily_dose, pediatric_contraindicated, elderly_caution)
                        VALUES (:id, :code, :name, CAST(:tradeName AS text), :spec, :form, :unit,
                                CAST(:packageUnit AS text), CAST(:packageSize AS integer), :price,
                                CAST(:insuranceCode AS text),
                                :insuranceCategory, :antibioticLevel, :narcotic, true, 0,
                                CAST(:maxDailyDose AS numeric), :pediatric, :elderly)
                        """)
                .param("id", d.id()).param("code", d.drugCode()).param("name", d.name())
                .param("tradeName", d.tradeName()).param("spec", d.spec())
                .param("form", d.dosageForm()).param("unit", d.unit())
                .param("packageUnit", d.packageUnit()).param("packageSize", d.packageSize())
                .param("price", d.price()).param("insuranceCode", d.insuranceCode())
                .param("insuranceCategory", d.insuranceCategory())
                .param("antibioticLevel", d.antibioticLevel())
                .param("narcotic", d.narcotic())
                .param("maxDailyDose", d.maxDailyDose())
                .param("pediatric", d.pediatricContraindicated())
                .param("elderly", d.elderlyCaution()).update();
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
                             prescriber, status, total_amount, version, billing_mode, source_order_id,
                             supply_date)
                        VALUES (:id, :no, :patientId, :patientNo, CAST(:visitId AS uuid),
                                CAST(:visitNo AS text), CAST(:deptId AS uuid),
                                :prescriber, :status, :total, 0, :billingMode,
                                CAST(:sourceOrderId AS uuid), CAST(:supplyDate AS date))
                        """)
                .param("id", p.id()).param("no", p.prescriptionNo())
                .param("patientId", p.patientId()).param("patientNo", p.patientNo())
                .param("visitId", p.visitId()).param("visitNo", p.visitNo())
                .param("deptId", p.deptId()).param("prescriber", p.prescriber())
                .param("status", p.status()).param("total", p.totalAmount())
                .param("billingMode", p.billingMode())
                .param("sourceOrderId", p.sourceOrderId())
                .param("supplyDate", p.supplyDate()).update();
    }

    public void insertPrescriptionItem(PrescriptionItem item) {
        jdbc.sql("""
                        INSERT INTO pharmacy_svc.prescription_item
                            (id, prescription_id, drug_id, drug_code, drug_name, spec, quantity,
                             unit, dosage, frequency, days, unit_price, amount, single_dose, dose_unit, times_per_day)
                        VALUES (:id, :rxId, :drugId, :code, :name, :spec, :qty,
                                :unit, CAST(:dosage AS text), CAST(:frequency AS text),
                                CAST(:days AS integer), :unitPrice, :amount,
                                CAST(:singleDose AS numeric), CAST(:doseUnit AS text),
                                CAST(:timesPerDay AS integer))
                        """)
                .param("id", item.id()).param("rxId", item.prescriptionId())
                .param("drugId", item.drugId()).param("code", item.drugCode())
                .param("name", item.drugName()).param("spec", item.spec())
                .param("qty", item.quantity()).param("unit", item.unit())
                .param("dosage", item.dosage()).param("frequency", item.frequency())
                .param("days", item.days()).param("unitPrice", item.unitPrice())
                .param("amount", item.amount())
                .param("singleDose", item.singleDose()).param("doseUnit", item.doseUnit())
                .param("timesPerDay", item.timesPerDay()).update();
    }

    public Optional<Prescription> findPrescription(UUID id) {
        return jdbc.sql("""
                        SELECT id, prescription_no, patient_id, patient_no, visit_id, visit_no, dept_id,
                               prescriber, status, reviewer, reviewed_at, reject_reason,
                               total_amount, version, created_at, billing_mode, source_order_id, supply_date
                          FROM pharmacy_svc.prescription WHERE id = ?
                        """).param(id).query(RX_MAPPER).optional();
    }

    /**
     * 按来源医嘱 + 供药日期查处方（医嘱转处方的幂等键；手工处方返回空）。
     *
     * <p>长嘱按日请领：同一条医嘱每天一张，故必须带 supplyDate。
     */
    public Optional<Prescription> findPrescriptionBySourceOrder(UUID sourceOrderId, LocalDate supplyDate) {
        return jdbc.sql("""
                        SELECT id, prescription_no, patient_id, patient_no, visit_id, visit_no, dept_id,
                               prescriber, status, reviewer, reviewed_at, reject_reason,
                               total_amount, version, created_at, billing_mode, source_order_id, supply_date
                          FROM pharmacy_svc.prescription
                         WHERE source_order_id = ? AND supply_date = ?
                        """).param(sourceOrderId).param(supplyDate).query(RX_MAPPER).optional();
    }

    /**
     * 该医嘱的全部处方（按供药日期升序，加行锁）。
     *
     * <p>停嘱/撤销时要把"今天及以后尚未发药"的处方一并作废；已发药的留给退药流程，
     * 不能用 {@code FOR UPDATE} 单行读取（长嘱会有多行）。
     */
    public List<Prescription> lockPrescriptionsBySourceOrder(UUID sourceOrderId) {
        return jdbc.sql("""
                        SELECT id, prescription_no, patient_id, patient_no, visit_id, visit_no, dept_id,
                               prescriber, status, reviewer, reviewed_at, reject_reason,
                               total_amount, version, created_at, billing_mode, source_order_id, supply_date
                          FROM pharmacy_svc.prescription
                         WHERE source_order_id = ?
                         ORDER BY supply_date
                           FOR UPDATE
                        """).param(sourceOrderId).query(RX_MAPPER).list();
    }

    /** 作废处方（CAS：版本号一致且尚未发药）。已作废的走幂等分支，不进这里。 */
    public boolean cancelPrescription(UUID id, long expectedVersion, String reason, String actor) {
        return jdbc.sql("""
                        UPDATE pharmacy_svc.prescription
                           SET status = 'CANCELLED', cancel_reason = :reason,
                               cancelled_by = :actor, cancelled_at = now(),
                               version = version + 1, updated_at = now()
                         WHERE id = :id AND version = :version
                           AND status IN ('PENDING_REVIEW', 'APPROVED', 'REJECTED')
                        """)
                .param("reason", reason).param("actor", actor)
                .param("id", id).param("version", expectedVersion).update() > 0;
    }

    /**
     * 发药单全部退回后作废处方（DISPENSED → CANCELLED）。
     *
     * <p>没有这一步，处方会永远停在 DISPENSED：临床停嘱/撤销的下游作废一直 409，
     * 补偿 worker 永远收敛不了（第六轮 MF-03）。
     */
    public boolean returnPrescription(UUID id, String reason, String actor) {
        return jdbc.sql("""
                        UPDATE pharmacy_svc.prescription
                           SET status = 'CANCELLED', cancel_reason = :reason,
                               cancelled_by = :actor, cancelled_at = now(),
                               version = version + 1, updated_at = now()
                         WHERE id = :id AND status = 'DISPENSED'
                        """)
                .param("reason", reason).param("actor", actor).param("id", id)
                .update() > 0;
    }

    /** 加行锁读取处方；必须在事务内调用（审核/发药/退药）。 */
    public Optional<Prescription> lockPrescription(UUID id) {
        return jdbc.sql("""
                        SELECT id, prescription_no, patient_id, patient_no, visit_id, visit_no, dept_id,
                               prescriber, status, reviewer, reviewed_at, reject_reason,
                               total_amount, version, created_at, billing_mode, source_order_id, supply_date
                          FROM pharmacy_svc.prescription WHERE id = ? FOR UPDATE
                        """).param(id).query(RX_MAPPER).optional();
    }

    public List<PrescriptionItem> listPrescriptionItems(UUID prescriptionId) {
        return jdbc.sql("""
                        SELECT id, prescription_id, drug_id, drug_code, drug_name, spec, quantity,
                               unit, dosage, frequency, days, unit_price, amount, single_dose, dose_unit, times_per_day, daily_dose
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
                               total_amount, version, created_at, billing_mode, source_order_id, supply_date
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

    /**
     * 补偿扫描：取计费异常态的发药单（走 {@code ix_dispense_billing_abnormal} 部分索引）。
     *
     * <p>按发生时间升序：最早的异常最先补偿，避免老单饿死。PENDING 也在列——
     * afterCommit 回调进程崩溃时会永久停在 PENDING，必须由扫描器收敛。
     */
    public List<Dispense> listAbnormalBillingDisps(int limit) {
        return jdbc.sql("""
                        SELECT id, dispense_no, prescription_id, operator, checker, status,
                               total_amount, dispensed_at, version, billing_status
                          FROM pharmacy_svc.dispense
                         WHERE billing_status IN ('PENDING', 'CHARGE_FAILED', 'REFUND_FAILED')
                         ORDER BY dispensed_at
                         LIMIT :limit
                        """).param("limit", limit).query(DISPENSE_MAPPER).list();
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

    /** 按退药单号回查（幂等重试用）。 */
    public java.util.Optional<DispenseReturn> findReturn(UUID returnId) {
        return jdbc.sql("""
                        SELECT id, dispense_id, dispense_item_id, quantity, reason, operator, created_at
                          FROM pharmacy_svc.dispense_return WHERE id = ?
                        """).param(returnId).query(RETURN_MAPPER).optional();
    }

    public List<DispenseReturn> listReturns(UUID dispenseId) {
        return jdbc.sql("""
                        SELECT id, dispense_id, dispense_item_id, quantity, reason, operator, created_at
                          FROM pharmacy_svc.dispense_return
                         WHERE dispense_id = ? ORDER BY created_at
                        """).param(dispenseId).query(RETURN_MAPPER).list();
    }

    // ---------------------------------------------------------------- 审方记录

    private static final RowMapper<PrescriptionAudit> AUDIT_MAPPER = (rs, i) -> new PrescriptionAudit(
            rs.getObject("id", UUID.class), rs.getObject("prescription_id", UUID.class),
            rs.getString("phase"), rs.getString("level"), rs.getString("rule_code"),
            rs.getString("message"), rs.getTimestamp("created_at").toInstant());

    public void insertAudit(PrescriptionAudit audit) {
        jdbc.sql("""
                        INSERT INTO pharmacy_svc.prescription_audit
                            (id, prescription_id, phase, level, rule_code, message, detail)
                        VALUES (:id, :rxId, :phase, :level, :ruleCode, :message, CAST(:detail AS jsonb))
                        """)
                .param("id", audit.id()).param("rxId", audit.prescriptionId())
                .param("phase", audit.phase()).param("level", audit.level())
                .param("ruleCode", audit.ruleCode()).param("message", audit.message())
                .param("detail", "{}").update();
    }

    /** 某处方的全部审方记录（按时间正序）。 */
    public List<PrescriptionAudit> listAudits(UUID prescriptionId) {
        return jdbc.sql("""
                        SELECT id, prescription_id, phase, level, rule_code, message, created_at
                          FROM pharmacy_svc.prescription_audit
                         WHERE prescription_id = ? ORDER BY created_at
                        """).param(prescriptionId).query(AUDIT_MAPPER).list();
    }

    // ---------------------------------------------------------------- 麻醉药品（V6）

    private static final RowMapper<NarcoticPrescriber> NARCOTIC_PRESCRIBER_MAPPER =
            (rs, i) -> new NarcoticPrescriber(
                    rs.getObject("id", UUID.class), rs.getString("prescriber"),
                    rs.getString("license_no"), rs.getString("granted_by"),
                    instantOf(rs, "granted_at"), rs.getString("revoked_by"),
                    instantOf(rs, "revoked_at"), rs.getString("revoke_reason"),
                    rs.getBoolean("enabled"), rs.getLong("version"));

    private static final RowMapper<NarcoticRegister> NARCOTIC_REGISTER_MAPPER =
            (rs, i) -> new NarcoticRegister(
                    rs.getObject("id", UUID.class), rs.getObject("dispense_item_id", UUID.class),
                    rs.getObject("dispense_id", UUID.class),
                    rs.getObject("prescription_id", UUID.class), rs.getString("prescription_no"),
                    rs.getObject("patient_id", UUID.class), rs.getString("patient_no"),
                    rs.getObject("drug_id", UUID.class), rs.getString("drug_code"),
                    rs.getString("drug_name"), rs.getString("spec"), rs.getInt("quantity"),
                    rs.getString("unit"), rs.getString("batch_no"), rs.getString("prescriber"),
                    rs.getString("dispenser"), rs.getString("checker"),
                    instantOf(rs, "dispensed_at"), rs.getInt("ampoule_returned"),
                    instantOf(rs, "ampoule_returned_at"), rs.getString("ampoule_receiver"),
                    rs.getString("status"), rs.getLong("version"), instantOf(rs, "created_at"));

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

    public Optional<NarcoticPrescriber> findNarcoticPrescriber(String prescriber) {
        return jdbc.sql("SELECT * FROM pharmacy_svc.narcotic_prescriber WHERE prescriber = ?")
                .param(prescriber).query(NARCOTIC_PRESCRIBER_MAPPER).optional();
    }

    public List<NarcoticPrescriber> listNarcoticPrescribers(Boolean enabled, int limit) {
        return jdbc.sql("""
                        SELECT * FROM pharmacy_svc.narcotic_prescriber
                         WHERE (CAST(:enabled AS boolean) IS NULL
                                OR enabled = CAST(:enabled AS boolean))
                         ORDER BY enabled DESC, prescriber
                         LIMIT :limit
                        """)
                .param("enabled", enabled).param("limit", limit)
                .query(NARCOTIC_PRESCRIBER_MAPPER).list();
    }

    /**
     * 授予处方权（已存在则重新启用并刷新证书号）。
     *
     * <p>用 upsert 而不是"先查再插"：并发授予同一医师会撞唯一键，
     * upsert 让重复授予变成幂等操作，且不会因为唯一键冲突把事务打成 aborted。
     */
    public void upsertNarcoticPrescriber(NarcoticPrescriber p) {
        jdbc.sql("""
                        INSERT INTO pharmacy_svc.narcotic_prescriber
                            (id, prescriber, license_no, granted_by, granted_at, enabled, version)
                        VALUES (:id, :prescriber, CAST(:licenseNo AS text), :grantedBy, now(),
                                true, 0)
                        ON CONFLICT (prescriber) DO UPDATE
                           SET license_no = CAST(:licenseNo AS text),
                               granted_by = :grantedBy, granted_at = now(),
                               revoked_by = NULL, revoked_at = NULL, revoke_reason = NULL,
                               enabled = true, version = pharmacy_svc.narcotic_prescriber.version + 1,
                               updated_at = now()
                        """)
                .param("id", p.id()).param("prescriber", p.prescriber())
                .param("licenseNo", p.licenseNo()).param("grantedBy", p.grantedBy()).update();
    }

    /** 撤销处方权（CAS：仅启用中可撤销，版本一致）。 */
    public boolean revokeNarcoticPrescriber(String prescriber, String reason, String actor,
                                            long expectedVersion) {
        return jdbc.sql("""
                        UPDATE pharmacy_svc.narcotic_prescriber
                           SET enabled = false, revoked_by = :actor, revoked_at = now(),
                               revoke_reason = :reason, version = version + 1, updated_at = now()
                         WHERE prescriber = :prescriber AND enabled AND version = :version
                        """)
                .param("actor", actor).param("reason", reason)
                .param("prescriber", prescriber).param("version", expectedVersion)
                .update() > 0;
    }

    public void insertNarcoticRegister(NarcoticRegister r) {
        jdbc.sql("""
                        INSERT INTO pharmacy_svc.narcotic_register
                            (id, dispense_item_id, dispense_id, prescription_id, prescription_no,
                             patient_id, patient_no, drug_id, drug_code, drug_name, spec, quantity,
                             unit, batch_no, prescriber, dispenser, checker, dispensed_at, status,
                             version)
                        VALUES (:id, :dispenseItemId, :dispenseId, :prescriptionId,
                                :prescriptionNo, :patientId, :patientNo, :drugId, :drugCode,
                                :drugName, :spec, :quantity, :unit, :batchNo, :prescriber,
                                :dispenser, :checker, CAST(:dispensedAt AS timestamptz),
                                'PENDING_RETURN', 0)
                        """)
                .param("id", r.id()).param("dispenseItemId", r.dispenseItemId())
                .param("dispenseId", r.dispenseId()).param("prescriptionId", r.prescriptionId())
                .param("prescriptionNo", r.prescriptionNo()).param("patientId", r.patientId())
                .param("patientNo", r.patientNo()).param("drugId", r.drugId())
                .param("drugCode", r.drugCode()).param("drugName", r.drugName())
                .param("spec", r.spec()).param("quantity", r.quantity()).param("unit", r.unit())
                .param("batchNo", r.batchNo()).param("prescriber", r.prescriber())
                .param("dispenser", r.dispenser()).param("checker", r.checker())
                .param("dispensedAt", ts(r.dispensedAt())).update();
    }

    public Optional<NarcoticRegister> findNarcoticRegister(UUID id) {
        return jdbc.sql("SELECT * FROM pharmacy_svc.narcotic_register WHERE id = ?")
                .param(id).query(NARCOTIC_REGISTER_MAPPER).optional();
    }

    public Optional<NarcoticRegister> lockNarcoticRegister(UUID id) {
        return jdbc.sql("SELECT * FROM pharmacy_svc.narcotic_register WHERE id = ? FOR UPDATE")
                .param(id).query(NARCOTIC_REGISTER_MAPPER).optional();
    }

    public List<NarcoticRegister> listNarcoticRegisters(java.time.Instant fromAt,
                                                        java.time.Instant toAt,
                                                        UUID patientId, String status, int limit) {
        return jdbc.sql("""
                        SELECT * FROM pharmacy_svc.narcotic_register
                         WHERE dispensed_at >= CAST(:fromAt AS timestamptz)
                           AND dispensed_at < CAST(:toAt AS timestamptz)
                           AND (CAST(:patientId AS uuid) IS NULL
                                OR patient_id = CAST(:patientId AS uuid))
                           AND (CAST(:status AS text) IS NULL
                                OR status = CAST(:status AS text))
                         ORDER BY dispensed_at DESC
                         LIMIT :limit
                        """)
                .param("fromAt", ts(fromAt)).param("toAt", ts(toAt))
                .param("patientId", patientId).param("status", status).param("limit", limit)
                .query(NARCOTIC_REGISTER_MAPPER).list();
    }

    /** 该患者该药品"空安瓿未回收"的登记条数（再次开方的硬门槛）。 */
    public long countOutstandingNarcotic(UUID patientId, UUID drugId) {
        Long count = jdbc.sql("""
                        SELECT count(*) FROM pharmacy_svc.narcotic_register
                         WHERE patient_id = ? AND drug_id = ? AND status = 'PENDING_RETURN'
                        """).param(patientId).param(drugId).query(Long.class).single();
        return count == null ? 0 : count;
    }

    /**
     * 登记空安瓿回收（CAS：仅待回收 + 版本一致 + 数量必须等于发药量）。
     *
     * <p>P1-10：原实现用 {@code CASE WHEN :qty = quantity THEN 'RETURNED' ELSE status END}，
     * 部分回收会写成 "PENDING_RETURN + ampoule_returned>0"，与 V6 的
     * {@code ck_narcotic_return_state} 直接冲突 → 数据库抛 500（靠服务层预校验掩盖）。
     * 空安瓿回收的业务口径本来就是"整笔回收"（{@code NarcoticService} 已强制数量相等），
     * 因此把条件下沉到 WHERE：数量不符时更新 0 行（返回 false），既不写坏状态也不抛 500。
     */
    public boolean returnNarcoticAmpoule(UUID id, int quantity, String receiver,
                                         long expectedVersion) {
        return jdbc.sql("""
                        UPDATE pharmacy_svc.narcotic_register
                           SET ampoule_returned = :qty, ampoule_returned_at = now(),
                               ampoule_receiver = :receiver,
                               status = 'RETURNED',
                               version = version + 1, updated_at = now()
                         WHERE id = :id AND status = 'PENDING_RETURN' AND version = :version
                           AND :qty = quantity
                        """)
                .param("qty", quantity).param("receiver", receiver)
                .param("id", id).param("version", expectedVersion).update() > 0;
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
