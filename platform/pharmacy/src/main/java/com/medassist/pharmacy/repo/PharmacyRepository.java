package com.medassist.pharmacy.repo;

import com.medassist.common.time.BusinessTime;
import com.medassist.pharmacy.domain.PharmacyModel.AntibioticApproval;
import com.medassist.pharmacy.domain.PharmacyModel.AntibioticDoctorStatRow;
import com.medassist.pharmacy.domain.PharmacyModel.AntibioticDrugStatRow;
import com.medassist.pharmacy.domain.PharmacyModel.AntibioticLevelStatRow;
import com.medassist.pharmacy.domain.PharmacyModel.AntibioticOverLimitRow;
import com.medassist.pharmacy.domain.PharmacyModel.AntibioticPendingRow;
import com.medassist.pharmacy.domain.PharmacyModel.Dispense;
import com.medassist.pharmacy.domain.PharmacyModel.DispenseItem;
import com.medassist.pharmacy.domain.PharmacyModel.DispenseReturn;
import com.medassist.pharmacy.domain.PharmacyModel.DailyDispenseTotal;
import com.medassist.pharmacy.domain.PharmacyModel.Drug;
import com.medassist.pharmacy.domain.PharmacyModel.DrugBatch;
import com.medassist.pharmacy.domain.PharmacyModel.ExpiredBatchRow;
import com.medassist.pharmacy.domain.PharmacyModel.NarcoticPrescriber;
import com.medassist.pharmacy.domain.PharmacyModel.NarcoticRegister;
import com.medassist.pharmacy.domain.PharmacyModel.PivasItem;
import com.medassist.pharmacy.domain.PharmacyModel.PivasItemBatch;
import com.medassist.pharmacy.domain.PharmacyModel.PivasOrder;
import com.medassist.pharmacy.domain.PharmacyModel.PivasStatusCount;
import com.medassist.pharmacy.domain.PharmacyModel.Prescription;
import com.medassist.pharmacy.domain.PharmacyModel.PrescriptionAudit;
import com.medassist.pharmacy.domain.PharmacyModel.DeptDrugStock;
import com.medassist.pharmacy.domain.PharmacyModel.DrugTransfer;
import com.medassist.pharmacy.domain.PharmacyModel.DrugTransferItem;
import com.medassist.pharmacy.domain.PharmacyModel.PrescriptionItem;
import com.medassist.pharmacy.domain.PharmacyModel.ReportSnapshot;
import com.medassist.pharmacy.domain.PharmacyModel.StockAdjustment;
import com.medassist.pharmacy.domain.PharmacyModel.StockLoss;
import com.medassist.pharmacy.domain.PharmacyModel.StockLossItem;
import com.medassist.pharmacy.domain.PharmacyModel.StockTake;
import com.medassist.pharmacy.domain.PharmacyModel.StockTakeItem;
import com.medassist.pharmacy.domain.PharmacyModel.StockTakeSource;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
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

    /** 按药品 + 批号定位批次：到货验收时判断是新建批次还是补录数量。 */
    public Optional<DrugBatch> findBatchByDrugAndBatchNo(UUID drugId, String batchNo) {
        return jdbc.sql("""
                        SELECT b.id, b.drug_id, d.drug_code, d.name AS drug_name, b.batch_no,
                               b.expire_date, b.quantity, b.supplier, b.version
                          FROM pharmacy_svc.drug_batch b
                          JOIN pharmacy_svc.md_drug d ON d.id = b.drug_id
                         WHERE b.drug_id = :drugId AND b.batch_no = :batchNo
                        """).param("drugId", drugId).param("batchNo", batchNo)
                .query(BATCH_MAPPER).optional();
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
        insertPrescription(p, com.medassist.common.security.DataScope.DEFAULT_CAMPUS_ID);
    }

    /** 写入处方并固化院区（G47）。 */
    public void insertPrescription(Prescription p, UUID campusId) {
        jdbc.sql("""
                        INSERT INTO pharmacy_svc.prescription
                            (id, prescription_no, patient_id, patient_no, visit_id, visit_no, dept_id,
                             prescriber, status, total_amount, version, billing_mode, source_order_id,
                             supply_date, campus_id)
                        VALUES (:id, :no, :patientId, :patientNo, CAST(:visitId AS uuid),
                                CAST(:visitNo AS text), CAST(:deptId AS uuid),
                                :prescriber, :status, :total, 0, :billingMode,
                                CAST(:sourceOrderId AS uuid), CAST(:supplyDate AS date), :campusId)
                        """)
                .param("id", p.id()).param("no", p.prescriptionNo())
                .param("patientId", p.patientId()).param("patientNo", p.patientNo())
                .param("visitId", p.visitId()).param("visitNo", p.visitNo())
                .param("deptId", p.deptId()).param("prescriber", p.prescriber())
                .param("status", p.status()).param("total", p.totalAmount())
                .param("billingMode", p.billingMode())
                .param("sourceOrderId", p.sourceOrderId())
                .param("supplyDate", p.supplyDate())
                .param("campusId", campusId).update();
    }

    /** 处方所属院区（行级权限校验用；empty 表示处方不存在）。 */
    public Optional<UUID> findPrescriptionCampus(UUID id) {
        return jdbc.sql("SELECT campus_id FROM pharmacy_svc.prescription WHERE id = ?")
                .param(id)
                .query((rs, i) -> rs.getObject("campus_id", UUID.class))
                .optional();
    }

    /** 发药单所属处方（G57：发药单级院区校验用；empty 表示发药单不存在）。 */
    public Optional<UUID> findDispensePrescriptionId(UUID dispenseId) {
        return jdbc.sql("SELECT prescription_id FROM pharmacy_svc.dispense WHERE id = ?")
                .param(dispenseId)
                .query((rs, i) -> rs.getObject("prescription_id", UUID.class))
                .optional();
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
        return listPrescriptions(status, patientId, limit, "");
    }

    /** 处方列表（G47 按院区收敛；空 CSV 表示不限院区）。 */
    public List<Prescription> listPrescriptions(String status, UUID patientId, int limit, String campusCsv) {
        return jdbc.sql("""
                        SELECT id, prescription_no, patient_id, patient_no, visit_id, visit_no, dept_id,
                               prescriber, status, reviewer, reviewed_at, reject_reason,
                               total_amount, version, created_at, billing_mode, source_order_id, supply_date
                          FROM pharmacy_svc.prescription
                         WHERE (CAST(:status AS text) IS NULL OR status = CAST(:status AS text))
                           AND (CAST(:patientId AS uuid) IS NULL OR patient_id = CAST(:patientId AS uuid))
                           AND (CAST(:campusCsv AS text) = ''
                                OR campus_id = ANY(string_to_array(CAST(:campusCsv AS text), ',')::uuid[]))
                         ORDER BY created_at DESC
                         LIMIT :limit
                        """)
                .param("status", status).param("patientId", patientId).param("limit", limit)
                .param("campusCsv", campusCsv == null ? "" : campusCsv)
                .query(RX_MAPPER).list();
    }

    /**
     * 各状态处方数量（P0-F4）：一条 GROUP BY 拿到准确待办数。
     *
     * <p>此前前端按状态分 5 次拉取再本地计数，每片被服务端封顶 200，
     * 待办被历史数据挤掉时 KPI 会偏小；分片请求数还随状态线性增长。
     */
    public java.util.Map<String, Integer> countPrescriptionsByStatus() {
        return countPrescriptionsByStatus("");
    }

    /** 各状态处方数量（G47 按院区收敛；待办数不能跨院区混算）。 */
    public java.util.Map<String, Integer> countPrescriptionsByStatus(String campusCsv) {
        java.util.Map<String, Integer> counts = new java.util.LinkedHashMap<>();
        jdbc.sql("""
                        SELECT status, count(*) AS n
                          FROM pharmacy_svc.prescription
                         WHERE (CAST(:campusCsv AS text) = ''
                                OR campus_id = ANY(string_to_array(CAST(:campusCsv AS text), ',')::uuid[]))
                         GROUP BY status
                        """)
                .param("campusCsv", campusCsv == null ? "" : campusCsv)
                .query((rs, i) -> java.util.Map.entry(rs.getString("status"), rs.getInt("n")))
                .list()
                .forEach(e -> counts.put(e.getKey(), e.getValue()));
        return counts;
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

    /**
     * 更新发药计费状态（补偿重试 / afterCommit 回写用）。
     *
     * <p>P1-D3：必须带"允许的来源状态"。此前只按 id 更新，回写顺序不确定时
     * 会互相覆盖（例如已 CHARGED 的单被一次迟到的 REFUND_PENDING 覆盖，
     * 财务侧看到"待退费"但实际已退）。来源状态不匹配时影响 0 行，由调用方决定是否告警。
     */
    public boolean updateDispenseBillingStatus(UUID id, String billingStatus, List<String> expectedFrom) {
        return jdbc.sql("""
                        UPDATE pharmacy_svc.dispense
                           SET billing_status = :status, updated_at = now()
                         WHERE id = :id
                           AND billing_status = ANY (CAST(:expected AS text[]))
                        """)
                .param("status", billingStatus)
                .param("id", id)
                .param("expected", expectedFrom.toArray(String[]::new))
                .update() > 0;
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
     *
     * <p>G70（评审 §3.9）：PENDING/CHARGE_FAILED 且已发生退药的单，在 {@code retry} 里
     * 必然被拒（不能把退回去的药再收一次钱），旧扫描按 {@code dispensed_at} 取固定头部，
     * 这些"毒丸"会永久占满每批名额、饿死后面的异常单。这里在 SQL 层排除它们
     * （交由收费处人工处理），退款失败仍照常扫描。
     */
    public List<Dispense> listAbnormalBillingDisps(int limit) {
        return jdbc.sql("""
                        SELECT d.id, d.dispense_no, d.prescription_id, d.operator, d.checker,
                               d.status, d.total_amount, d.dispensed_at, d.version,
                               d.billing_status
                          FROM pharmacy_svc.dispense d
                         WHERE d.billing_status = 'REFUND_FAILED'
                            OR (d.billing_status IN ('PENDING', 'CHARGE_FAILED')
                                AND NOT EXISTS (
                                    SELECT 1 FROM pharmacy_svc.dispense_item i
                                     WHERE i.dispense_id = d.id AND i.returned_quantity > 0))
                         ORDER BY d.dispensed_at
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

    // ---------------------------------------------------------------- 药品盘点（G6）

    private static final RowMapper<StockTake> STOCK_TAKE_MAPPER =
            (rs, i) -> new StockTake(
                    rs.getObject("id", UUID.class), rs.getString("take_no"), rs.getString("status"),
                    rs.getString("scope_label"), rs.getString("note"), rs.getString("created_by"),
                    rs.getString("submitted_by"),
                    rs.getTimestamp("submitted_at") == null ? null
                            : rs.getTimestamp("submitted_at").toInstant(),
                    rs.getString("approved_by"),
                    rs.getTimestamp("approved_at") == null ? null
                            : rs.getTimestamp("approved_at").toInstant(),
                    rs.getString("cancelled_by"),
                    rs.getTimestamp("cancelled_at") == null ? null
                            : rs.getTimestamp("cancelled_at").toInstant(),
                    rs.getString("cancel_reason"), rs.getLong("version"),
                    rs.getTimestamp("created_at").toInstant());

    private static final RowMapper<StockTakeItem> STOCK_TAKE_ITEM_MAPPER =
            (rs, i) -> new StockTakeItem(
                    rs.getObject("id", UUID.class), rs.getObject("take_id", UUID.class),
                    rs.getObject("drug_id", UUID.class), rs.getString("drug_code"),
                    rs.getString("drug_name"), rs.getString("spec"), rs.getString("unit"),
                    rs.getObject("batch_id", UUID.class), rs.getString("batch_no"),
                    rs.getDate("expire_date").toLocalDate(), rs.getInt("book_quantity"),
                    (Integer) rs.getObject("actual_quantity"), rs.getString("reason"),
                    rs.getString("counted_by"),
                    rs.getTimestamp("counted_at") == null ? null
                            : rs.getTimestamp("counted_at").toInstant(),
                    rs.getLong("version"));

    private static final RowMapper<StockTakeSource> STOCK_TAKE_SOURCE_MAPPER =
            (rs, i) -> new StockTakeSource(
                    rs.getObject("drug_id", UUID.class), rs.getString("drug_code"),
                    rs.getString("drug_name"), rs.getString("spec"), rs.getString("unit"),
                    rs.getObject("batch_id", UUID.class), rs.getString("batch_no"),
                    rs.getDate("expire_date").toLocalDate(), rs.getInt("quantity"));

    private static final RowMapper<StockAdjustment> STOCK_ADJUSTMENT_MAPPER =
            (rs, i) -> new StockAdjustment(
                    rs.getObject("id", UUID.class), rs.getObject("take_id", UUID.class),
                    rs.getObject("drug_id", UUID.class), rs.getObject("batch_id", UUID.class),
                    rs.getString("batch_no"), rs.getInt("before_quantity"),
                    rs.getInt("after_quantity"), rs.getInt("delta"), rs.getString("reason"),
                    rs.getString("actor"), rs.getTimestamp("created_at").toInstant());

    public String nextStockTakeNo() {
        return nextNo("pharmacy_svc.stock_take_no_seq", "ST");
    }

    public void insertStockTake(StockTake t) {
        jdbc.sql("""
                        INSERT INTO pharmacy_svc.stock_take
                            (id, take_no, status, scope_label, note, created_by, version)
                        VALUES (:id, :no, :status, CAST(:scope AS text), CAST(:note AS text),
                                :createdBy, 0)
                        """)
                .param("id", t.id()).param("no", t.takeNo()).param("status", t.status())
                .param("scope", t.scopeLabel()).param("note", t.note())
                .param("createdBy", t.createdBy()).update();
    }

    public Optional<StockTake> findStockTake(UUID id) {
        return jdbc.sql("""
                        SELECT id, take_no, status, scope_label, note, created_by, submitted_by,
                               submitted_at, approved_by, approved_at, cancelled_by,
                               cancelled_at, cancel_reason, version, created_at
                          FROM pharmacy_svc.stock_take WHERE id = ?
                        """).param(id).query(STOCK_TAKE_MAPPER).optional();
    }

    /** 当前进行中的盘点单（COUNTING/SUBMITTED）；库级唯一索引保证最多一条。 */
    public Optional<StockTake> findActiveStockTake() {
        return jdbc.sql("""
                        SELECT id, take_no, status, scope_label, note, created_by, submitted_by,
                               submitted_at, approved_by, approved_at, cancelled_by,
                               cancelled_at, cancel_reason, version, created_at
                          FROM pharmacy_svc.stock_take
                         WHERE status IN ('COUNTING', 'SUBMITTED')
                         ORDER BY created_at DESC
                         LIMIT 1
                        """).query(STOCK_TAKE_MAPPER).optional();
    }

    /** 加行锁读取盘点单；必须在事务内调用（提交/审批/取消的 CAS 基准）。 */
    public Optional<StockTake> lockStockTake(UUID id) {
        return jdbc.sql("""
                        SELECT id, take_no, status, scope_label, note, created_by, submitted_by,
                               submitted_at, approved_by, approved_at, cancelled_by,
                               cancelled_at, cancel_reason, version, created_at
                          FROM pharmacy_svc.stock_take WHERE id = ? FOR UPDATE
                        """).param(id).query(STOCK_TAKE_MAPPER).optional();
    }

    public List<StockTake> listStockTakes(String status, int limit) {
        return jdbc.sql("""
                        SELECT id, take_no, status, scope_label, note, created_by, submitted_by,
                               submitted_at, approved_by, approved_at, cancelled_by,
                               cancelled_at, cancel_reason, version, created_at
                          FROM pharmacy_svc.stock_take
                         WHERE (CAST(:status AS text) IS NULL OR status = CAST(:status AS text))
                         ORDER BY created_at DESC
                         LIMIT :limit
                        """)
                .param("status", status).param("limit", limit)
                .query(STOCK_TAKE_MAPPER).list();
    }

    /** 建单快照来源：有余量的批次，可按药品编码/名称/批号缩小范围。 */
    public List<StockTakeSource> listStockTakeSources(String keyword) {
        String pattern = keyword == null || keyword.isBlank() ? null : "%" + keyword.trim() + "%";
        return jdbc.sql("""
                        SELECT d.id AS drug_id, d.drug_code, d.name AS drug_name, d.spec, d.unit,
                               b.id AS batch_id, b.batch_no, b.expire_date, b.quantity
                          FROM pharmacy_svc.drug_batch b
                          JOIN pharmacy_svc.md_drug d ON d.id = b.drug_id
                         WHERE b.quantity > 0
                           AND (CAST(:pattern AS text) IS NULL
                                OR d.name ILIKE CAST(:pattern AS text)
                                OR d.drug_code ILIKE CAST(:pattern AS text)
                                OR b.batch_no ILIKE CAST(:pattern AS text))
                         ORDER BY d.drug_code, b.expire_date, b.batch_no
                        """)
                .param("pattern", pattern).query(STOCK_TAKE_SOURCE_MAPPER).list();
    }

    public void insertStockTakeItem(UUID id, UUID takeId, UUID drugId, UUID batchId,
                                    int bookQuantity) {
        jdbc.sql("""
                        INSERT INTO pharmacy_svc.stock_take_item
                            (id, take_id, drug_id, batch_id, book_quantity, version)
                        VALUES (:id, :takeId, :drugId, :batchId, :book, 0)
                        """)
                .param("id", id).param("takeId", takeId).param("drugId", drugId)
                .param("batchId", batchId).param("book", bookQuantity).update();
    }

    public List<StockTakeItem> listStockTakeItems(UUID takeId) {
        return jdbc.sql("""
                        SELECT i.id, i.take_id, i.drug_id, d.drug_code, d.name AS drug_name,
                               d.spec, d.unit, i.batch_id, b.batch_no, b.expire_date,
                               i.book_quantity, i.actual_quantity, i.reason, i.counted_by,
                               i.counted_at, i.version
                          FROM pharmacy_svc.stock_take_item i
                          JOIN pharmacy_svc.md_drug d ON d.id = i.drug_id
                          JOIN pharmacy_svc.drug_batch b ON b.id = i.batch_id
                         WHERE i.take_id = :takeId
                         ORDER BY d.drug_code, b.expire_date, b.batch_no
                        """).param("takeId", takeId).query(STOCK_TAKE_ITEM_MAPPER).list();
    }

    /** 录入实盘（CAS：明细版本号）；返回 false 表示已被他人修改。 */
    public boolean countStockTakeItem(UUID itemId, UUID takeId, int actualQuantity, String reason,
                                      String actor, long expectedVersion) {
        return jdbc.sql("""
                        UPDATE pharmacy_svc.stock_take_item
                           SET actual_quantity = :actual,
                               reason = CAST(:reason AS text),
                               counted_by = :actor,
                               counted_at = now(),
                               version = version + 1
                         WHERE id = :id AND take_id = :takeId AND version = :version
                        """)
                .param("actual", actualQuantity).param("reason", reason).param("actor", actor)
                .param("id", itemId).param("takeId", takeId).param("version", expectedVersion)
                .update() > 0;
    }

    public int countUncountedItems(UUID takeId) {
        Integer count = jdbc.sql("""
                        SELECT count(*) FROM pharmacy_svc.stock_take_item
                         WHERE take_id = ? AND actual_quantity IS NULL
                        """).param(takeId).query(Integer.class).single();
        return count == null ? 0 : count;
    }

    public boolean submitStockTake(UUID id, long expectedVersion, String actor) {
        return jdbc.sql("""
                        UPDATE pharmacy_svc.stock_take
                           SET status = 'SUBMITTED', submitted_by = :actor, submitted_at = now(),
                               version = version + 1, updated_at = now()
                         WHERE id = :id AND status = 'COUNTING' AND version = :version
                        """)
                .param("id", id).param("version", expectedVersion).param("actor", actor)
                .update() > 0;
    }

    public boolean approveStockTake(UUID id, long expectedVersion, String actor) {
        return jdbc.sql("""
                        UPDATE pharmacy_svc.stock_take
                           SET status = 'APPROVED', approved_by = :actor, approved_at = now(),
                               version = version + 1, updated_at = now()
                         WHERE id = :id AND status = 'SUBMITTED' AND version = :version
                        """)
                .param("id", id).param("version", expectedVersion).param("actor", actor)
                .update() > 0;
    }

    public boolean cancelStockTake(UUID id, long expectedVersion, String actor, String reason) {
        return jdbc.sql("""
                        UPDATE pharmacy_svc.stock_take
                           SET status = 'CANCELLED', cancelled_by = :actor, cancelled_at = now(),
                               cancel_reason = :reason, version = version + 1, updated_at = now()
                         WHERE id = :id AND status IN ('COUNTING', 'SUBMITTED')
                           AND version = :version
                        """)
                .param("id", id).param("version", expectedVersion).param("actor", actor)
                .param("reason", reason).update() > 0;
    }

    /**
     * 按快照校准批次库存（CAS：当前数量必须等于账面快照且版本一致）。
     *
     * <p>条件里带 quantity 而不是只带 version：即便版本恰好被别的路径推进，
     * 也要保证"改之前确实是盘点时的数量"，否则拒绝整单。
     */
    public boolean calibrateBatchQuantity(UUID batchId, int expectedQuantity, int afterQuantity,
                                          long expectedVersion) {
        return jdbc.sql("""
                        UPDATE pharmacy_svc.drug_batch
                           SET quantity = :after, version = version + 1, updated_at = now()
                         WHERE id = :id AND quantity = :expected AND version = :version
                        """)
                .param("after", afterQuantity).param("id", batchId)
                .param("expected", expectedQuantity).param("version", expectedVersion)
                .update() > 0;
    }

    public void insertStockAdjustment(StockAdjustment a) {
        jdbc.sql("""
                        INSERT INTO pharmacy_svc.stock_adjustment
                            (id, take_id, drug_id, batch_id, before_quantity, after_quantity,
                             delta, reason, actor)
                        VALUES (:id, :takeId, :drugId, :batchId, :before, :after, :delta,
                                CAST(:reason AS text), :actor)
                        """)
                .param("id", a.id()).param("takeId", a.takeId()).param("drugId", a.drugId())
                .param("batchId", a.batchId()).param("before", a.beforeQuantity())
                .param("after", a.afterQuantity()).param("delta", a.delta())
                .param("reason", a.reason()).param("actor", a.actor()).update();
    }

    public List<StockAdjustment> listStockAdjustments(UUID takeId) {
        return jdbc.sql("""
                        SELECT a.id, a.take_id, a.drug_id, a.batch_id, b.batch_no,
                               a.before_quantity, a.after_quantity, a.delta, a.reason,
                               a.actor, a.created_at
                          FROM pharmacy_svc.stock_adjustment a
                          JOIN pharmacy_svc.drug_batch b ON b.id = a.batch_id
                         WHERE a.take_id = :takeId
                         ORDER BY a.created_at, b.batch_no
                        """).param("takeId", takeId).query(STOCK_ADJUSTMENT_MAPPER).list();
    }

    // ---------------------------------------------------------------- 药品报损（G7）

    private static final RowMapper<StockLoss> STOCK_LOSS_MAPPER =
            (rs, i) -> new StockLoss(
                    rs.getObject("id", UUID.class), rs.getString("loss_no"),
                    rs.getString("status"), rs.getString("reason_category"), rs.getString("note"),
                    rs.getString("created_by"), rs.getString("reviewed_by"),
                    rs.getTimestamp("reviewed_at") == null ? null
                            : rs.getTimestamp("reviewed_at").toInstant(),
                    rs.getString("review_note"), rs.getLong("version"),
                    rs.getTimestamp("created_at").toInstant());

    private static final RowMapper<StockLossItem> STOCK_LOSS_ITEM_MAPPER =
            (rs, i) -> new StockLossItem(
                    rs.getObject("id", UUID.class), rs.getObject("loss_id", UUID.class),
                    rs.getObject("drug_id", UUID.class), rs.getString("drug_code"),
                    rs.getString("drug_name"), rs.getString("spec"), rs.getString("unit"),
                    rs.getObject("batch_id", UUID.class), rs.getString("batch_no"),
                    rs.getDate("expire_date").toLocalDate(), rs.getInt("quantity"),
                    rs.getString("reason"));

    public String nextStockLossNo() {
        return nextNo("pharmacy_svc.stock_loss_no_seq", "LS");
    }

    public void insertStockLoss(StockLoss l) {
        jdbc.sql("""
                        INSERT INTO pharmacy_svc.stock_loss
                            (id, loss_no, status, reason_category, note, created_by, version)
                        VALUES (:id, :no, :status, :category, CAST(:note AS text), :createdBy, 0)
                        """)
                .param("id", l.id()).param("no", l.lossNo()).param("status", l.status())
                .param("category", l.reasonCategory()).param("note", l.note())
                .param("createdBy", l.createdBy()).update();
    }

    /** 到期自动草稿：DRAFT + 来源 AUTO_EXPIRY（不扣库存，等待人工提交/审批）。 */
    public void insertAutoDraftLoss(UUID id, String lossNo, String note, String actor) {
        jdbc.sql("""
                        INSERT INTO pharmacy_svc.stock_loss
                            (id, loss_no, status, reason_category, note, created_by, source, version)
                        VALUES (:id, :no, 'DRAFT', 'EXPIRED', CAST(:note AS text), :actor,
                                'AUTO_EXPIRY', 0)
                        """)
                .param("id", id).param("no", lossNo).param("note", note)
                .param("actor", actor).update();
    }

    /**
     * 到期自动草稿的互斥闸门（G77）：事务级 advisory lock。
     *
     * <p>多副本同时跑 01:10 的定时任务时，两个实例会读到同一批到期批次、
     * 各自插入一张 DRAFT 报损单——重复单据，人工还得分辨该提交哪张。
     * 这里在事务内抢占同名锁：拿到的一方扫描并生成，另一方本轮直接跳过；
     * 事务结束（提交/回滚/连接断开）自动释放，不需要额外清理逻辑。
     *
     * <p>键值用 "PHAR" 命名空间 + 序号，避免与库内其他 advisory lock 撞号。
     */
    public boolean tryLockExpiryAutoDraft() {
        return jdbc.sql("SELECT pg_try_advisory_xact_lock(:key)")
                .param("key", 0x50484152L << 16 | 1L)
                .query(Boolean.class).single();
    }

    /** 已过期且仍有库存、且未在任何有效报损单中的批次（G28）。 */
    public List<ExpiredBatchRow> findExpiredBatchesForLoss(java.time.LocalDate today, int limit) {
        return jdbc.sql("""
                        SELECT b.id AS batch_id, b.drug_id, d.drug_code,
                               d.name AS drug_name, b.batch_no, b.expire_date, b.quantity
                          FROM pharmacy_svc.drug_batch b
                          JOIN pharmacy_svc.md_drug d ON d.id = b.drug_id
                         WHERE b.expire_date < :today
                           AND b.quantity > 0
                           AND d.enabled
                           AND NOT EXISTS (
                               SELECT 1
                                 FROM pharmacy_svc.stock_loss_item i
                                 JOIN pharmacy_svc.stock_loss l ON l.id = i.loss_id
                                WHERE i.batch_id = b.id
                                  AND l.status IN ('DRAFT', 'SUBMITTED', 'APPROVED'))
                         ORDER BY b.expire_date, b.batch_no
                         LIMIT :limit
                        """)
                .param("today", today).param("limit", limit)
                .query((rs, i) -> new ExpiredBatchRow(
                        rs.getObject("batch_id", UUID.class),
                        rs.getObject("drug_id", UUID.class),
                        rs.getString("drug_code"), rs.getString("drug_name"),
                        rs.getString("batch_no"), rs.getDate("expire_date").toLocalDate(),
                        rs.getInt("quantity"))).list();
    }

    /**
     * 草稿提交审批（CAS：仅 DRAFT）。
     *
     * <p>同时把 created_by 改为提交人：自动草稿的创建人是 scheduler，若不改写，
     * 提交人就能审批"自己"提交的单，四眼原则形同虚设。
     */
    public boolean submitStockLoss(UUID id, String actor, long expectedVersion) {
        return jdbc.sql("""
                        UPDATE pharmacy_svc.stock_loss
                           SET status = 'SUBMITTED', created_by = :actor,
                               version = version + 1, updated_at = now()
                         WHERE id = :id AND status = 'DRAFT' AND version = :version
                        """)
                .param("id", id).param("actor", actor).param("version", expectedVersion)
                .update() > 0;
    }

    public Optional<StockLoss> findStockLoss(UUID id) {
        return jdbc.sql("""
                        SELECT id, loss_no, status, reason_category, note, created_by,
                               reviewed_by, reviewed_at, review_note, version, created_at
                          FROM pharmacy_svc.stock_loss WHERE id = ?
                        """).param(id).query(STOCK_LOSS_MAPPER).optional();
    }

    /** 加行锁读取报损单；审批扣减前必须调用（防止同一单被重复审批）。 */
    public Optional<StockLoss> lockStockLoss(UUID id) {
        return jdbc.sql("""
                        SELECT id, loss_no, status, reason_category, note, created_by,
                               reviewed_by, reviewed_at, review_note, version, created_at
                          FROM pharmacy_svc.stock_loss WHERE id = ? FOR UPDATE
                        """).param(id).query(STOCK_LOSS_MAPPER).optional();
    }

    public List<StockLoss> listStockLosses(String status, int limit) {
        return jdbc.sql("""
                        SELECT id, loss_no, status, reason_category, note, created_by,
                               reviewed_by, reviewed_at, review_note, version, created_at
                          FROM pharmacy_svc.stock_loss
                         WHERE (CAST(:status AS text) IS NULL OR status = CAST(:status AS text))
                         ORDER BY created_at DESC
                         LIMIT :limit
                        """)
                .param("status", status).param("limit", limit)
                .query(STOCK_LOSS_MAPPER).list();
    }

    public void insertStockLossItem(UUID id, UUID lossId, UUID drugId, UUID batchId, int quantity,
                                    String reason) {
        jdbc.sql("""
                        INSERT INTO pharmacy_svc.stock_loss_item
                            (id, loss_id, drug_id, batch_id, quantity, reason)
                        VALUES (:id, :lossId, :drugId, :batchId, :qty, CAST(:reason AS text))
                        """)
                .param("id", id).param("lossId", lossId).param("drugId", drugId)
                .param("batchId", batchId).param("qty", quantity).param("reason", reason).update();
    }

    public List<StockLossItem> listStockLossItems(UUID lossId) {
        return jdbc.sql("""
                        SELECT i.id, i.loss_id, i.drug_id, d.drug_code, d.name AS drug_name,
                               d.spec, d.unit, i.batch_id, b.batch_no, b.expire_date,
                               i.quantity, i.reason
                          FROM pharmacy_svc.stock_loss_item i
                          JOIN pharmacy_svc.md_drug d ON d.id = i.drug_id
                          JOIN pharmacy_svc.drug_batch b ON b.id = i.batch_id
                         WHERE i.loss_id = :lossId
                         ORDER BY d.drug_code, b.expire_date, b.batch_no
                        """).param("lossId", lossId).query(STOCK_LOSS_ITEM_MAPPER).list();
    }

    /** 审批/驳回（CAS：期望状态 + 版本号）。 */
    public boolean reviewStockLoss(UUID id, long expectedVersion, boolean approve, String actor,
                                   String note) {
        return jdbc.sql("""
                        UPDATE pharmacy_svc.stock_loss
                           SET status = :status, reviewed_by = :actor, reviewed_at = now(),
                               review_note = CAST(:note AS text),
                               version = version + 1, updated_at = now()
                         WHERE id = :id AND status = 'SUBMITTED' AND version = :version
                        """)
                .param("status", approve ? StockLoss.APPROVED : StockLoss.REJECTED)
                .param("actor", actor).param("note", note)
                .param("id", id).param("version", expectedVersion).update() > 0;
    }

    /** 报损审批产生的库存调整流水（与盘点共用 stock_adjustment）。 */
    public void insertStockLossAdjustment(UUID lossId, UUID drugId, UUID batchId, int beforeQuantity,
                                          int afterQuantity, int delta, String reason,
                                          String actor) {
        jdbc.sql("""
                        INSERT INTO pharmacy_svc.stock_adjustment
                            (id, loss_id, drug_id, batch_id, before_quantity, after_quantity,
                             delta, reason, actor)
                        VALUES (:id, :lossId, :drugId, :batchId, :before, :after, :delta,
                                CAST(:reason AS text), :actor)
                        """)
                .param("id", UUID.randomUUID()).param("lossId", lossId).param("drugId", drugId)
                .param("batchId", batchId).param("before", beforeQuantity)
                .param("after", afterQuantity).param("delta", delta)
                .param("reason", reason).param("actor", actor).update();
    }

    public List<StockAdjustment> listStockLossAdjustments(UUID lossId) {
        return jdbc.sql("""
                        SELECT a.id, a.take_id, a.drug_id, a.batch_id, b.batch_no,
                               a.before_quantity, a.after_quantity, a.delta, a.reason,
                               a.actor, a.created_at
                          FROM pharmacy_svc.stock_adjustment a
                          JOIN pharmacy_svc.drug_batch b ON b.id = a.batch_id
                         WHERE a.loss_id = :lossId
                         ORDER BY a.created_at, b.batch_no
                        """).param("lossId", lossId).query(STOCK_ADJUSTMENT_MAPPER).list();
    }

    // ---------------------------------------------------------------- 药品科室调拨（G8）

    private static final RowMapper<DrugTransfer> DRUG_TRANSFER_MAPPER =
            (rs, i) -> new DrugTransfer(
                    rs.getObject("id", UUID.class), rs.getString("transfer_no"),
                    rs.getString("status"), rs.getObject("to_dept_id", UUID.class),
                    rs.getString("note"), rs.getString("created_by"), rs.getString("dispatched_by"),
                    rs.getTimestamp("dispatched_at") == null ? null
                            : rs.getTimestamp("dispatched_at").toInstant(),
                    rs.getString("accepted_by"),
                    rs.getTimestamp("accepted_at") == null ? null
                            : rs.getTimestamp("accepted_at").toInstant(),
                    rs.getString("rejected_by"),
                    rs.getTimestamp("rejected_at") == null ? null
                            : rs.getTimestamp("rejected_at").toInstant(),
                    rs.getString("reject_reason"), rs.getString("cancelled_by"),
                    rs.getTimestamp("cancelled_at") == null ? null
                            : rs.getTimestamp("cancelled_at").toInstant(),
                    rs.getString("cancel_reason"), rs.getLong("version"),
                    rs.getTimestamp("created_at").toInstant());

    private static final RowMapper<DrugTransferItem> DRUG_TRANSFER_ITEM_MAPPER =
            (rs, i) -> new DrugTransferItem(
                    rs.getObject("id", UUID.class), rs.getObject("transfer_id", UUID.class),
                    rs.getObject("drug_id", UUID.class), rs.getString("drug_code"),
                    rs.getString("drug_name"), rs.getString("spec"), rs.getString("unit"),
                    rs.getObject("batch_id", UUID.class), rs.getString("batch_no"),
                    rs.getDate("expire_date").toLocalDate(), rs.getInt("quantity"));

    private static final RowMapper<DeptDrugStock> DEPT_DRUG_STOCK_MAPPER =
            (rs, i) -> new DeptDrugStock(
                    rs.getObject("id", UUID.class), rs.getObject("dept_id", UUID.class),
                    rs.getObject("drug_id", UUID.class), rs.getString("drug_code"),
                    rs.getString("drug_name"), rs.getString("spec"), rs.getString("unit"),
                    rs.getObject("batch_id", UUID.class), rs.getString("batch_no"),
                    rs.getDate("expire_date").toLocalDate(), rs.getInt("quantity"),
                    rs.getLong("version"));

    public String nextDrugTransferNo() {
        return nextNo("pharmacy_svc.drug_transfer_no_seq", "TR");
    }

    public void insertDrugTransfer(DrugTransfer t) {
        jdbc.sql("""
                        INSERT INTO pharmacy_svc.drug_transfer
                            (id, transfer_no, status, to_dept_id, note, created_by, version)
                        VALUES (:id, :no, :status, :deptId, CAST(:note AS text), :createdBy, 0)
                        """)
                .param("id", t.id()).param("no", t.transferNo()).param("status", t.status())
                .param("deptId", t.toDeptId()).param("note", t.note())
                .param("createdBy", t.createdBy()).update();
    }

    public Optional<DrugTransfer> findDrugTransfer(UUID id) {
        return jdbc.sql("""
                        SELECT id, transfer_no, status, to_dept_id, note, created_by,
                               dispatched_by, dispatched_at, accepted_by, accepted_at,
                               rejected_by, rejected_at, reject_reason, cancelled_by,
                               cancelled_at, cancel_reason, version, created_at
                          FROM pharmacy_svc.drug_transfer WHERE id = ?
                        """).param(id).query(DRUG_TRANSFER_MAPPER).optional();
    }

    /** 加行锁读取调拨单；发出/接收/拒收/撤回前必须调用。 */
    public Optional<DrugTransfer> lockDrugTransfer(UUID id) {
        return jdbc.sql("""
                        SELECT id, transfer_no, status, to_dept_id, note, created_by,
                               dispatched_by, dispatched_at, accepted_by, accepted_at,
                               rejected_by, rejected_at, reject_reason, cancelled_by,
                               cancelled_at, cancel_reason, version, created_at
                          FROM pharmacy_svc.drug_transfer WHERE id = ? FOR UPDATE
                        """).param(id).query(DRUG_TRANSFER_MAPPER).optional();
    }

    public List<DrugTransfer> listDrugTransfers(String status, UUID toDeptId, int limit) {
        return jdbc.sql("""
                        SELECT id, transfer_no, status, to_dept_id, note, created_by,
                               dispatched_by, dispatched_at, accepted_by, accepted_at,
                               rejected_by, rejected_at, reject_reason, cancelled_by,
                               cancelled_at, cancel_reason, version, created_at
                          FROM pharmacy_svc.drug_transfer
                         WHERE (CAST(:status AS text) IS NULL OR status = CAST(:status AS text))
                           AND (CAST(:deptId AS uuid) IS NULL OR to_dept_id = CAST(:deptId AS uuid))
                         ORDER BY created_at DESC
                         LIMIT :limit
                        """)
                .param("status", status).param("deptId", toDeptId).param("limit", limit)
                .query(DRUG_TRANSFER_MAPPER).list();
    }

    public void insertDrugTransferItem(UUID id, UUID transferId, UUID drugId, UUID batchId,
                                       int quantity) {
        jdbc.sql("""
                        INSERT INTO pharmacy_svc.drug_transfer_item
                            (id, transfer_id, drug_id, batch_id, quantity)
                        VALUES (:id, :transferId, :drugId, :batchId, :qty)
                        """)
                .param("id", id).param("transferId", transferId).param("drugId", drugId)
                .param("batchId", batchId).param("qty", quantity).update();
    }

    public List<DrugTransferItem> listDrugTransferItems(UUID transferId) {
        return jdbc.sql("""
                        SELECT i.id, i.transfer_id, i.drug_id, d.drug_code, d.name AS drug_name,
                               d.spec, d.unit, i.batch_id, b.batch_no, b.expire_date, i.quantity
                          FROM pharmacy_svc.drug_transfer_item i
                          JOIN pharmacy_svc.md_drug d ON d.id = i.drug_id
                          JOIN pharmacy_svc.drug_batch b ON b.id = i.batch_id
                         WHERE i.transfer_id = :transferId
                         ORDER BY d.drug_code, b.expire_date, b.batch_no
                        """).param("transferId", transferId).query(DRUG_TRANSFER_ITEM_MAPPER).list();
    }

    public boolean dispatchDrugTransfer(UUID id, long expectedVersion, String actor) {
        return jdbc.sql("""
                        UPDATE pharmacy_svc.drug_transfer
                           SET status = 'DISPATCHED', dispatched_by = :actor, dispatched_at = now(),
                               version = version + 1, updated_at = now()
                         WHERE id = :id AND status = 'SUBMITTED' AND version = :version
                        """)
                .param("id", id).param("version", expectedVersion).param("actor", actor)
                .update() > 0;
    }

    public boolean acceptDrugTransfer(UUID id, long expectedVersion, String actor) {
        return jdbc.sql("""
                        UPDATE pharmacy_svc.drug_transfer
                           SET status = 'ACCEPTED', accepted_by = :actor, accepted_at = now(),
                               version = version + 1, updated_at = now()
                         WHERE id = :id AND status = 'DISPATCHED' AND version = :version
                        """)
                .param("id", id).param("version", expectedVersion).param("actor", actor)
                .update() > 0;
    }

    public boolean rejectDrugTransfer(UUID id, long expectedVersion, String actor, String reason) {
        return jdbc.sql("""
                        UPDATE pharmacy_svc.drug_transfer
                           SET status = 'REJECTED', rejected_by = :actor, rejected_at = now(),
                               reject_reason = :reason, version = version + 1, updated_at = now()
                         WHERE id = :id AND status = 'DISPATCHED' AND version = :version
                        """)
                .param("id", id).param("version", expectedVersion).param("actor", actor)
                .param("reason", reason).update() > 0;
    }

    public boolean cancelDrugTransfer(UUID id, long expectedVersion, String actor, String reason) {
        return jdbc.sql("""
                        UPDATE pharmacy_svc.drug_transfer
                           SET status = 'CANCELLED', cancelled_by = :actor, cancelled_at = now(),
                               cancel_reason = :reason, version = version + 1, updated_at = now()
                         WHERE id = :id AND status = 'SUBMITTED' AND version = :version
                        """)
                .param("id", id).param("version", expectedVersion).param("actor", actor)
                .param("reason", reason).update() > 0;
    }

    /** 科室二级库累加（同科室+药品+批次 upsert）。 */
    public void upsertDeptDrugStock(UUID deptId, UUID drugId, UUID batchId, int quantity) {
        jdbc.sql("""
                        INSERT INTO pharmacy_svc.dept_drug_stock
                            (id, dept_id, drug_id, batch_id, quantity, version)
                        VALUES (:id, :deptId, :drugId, :batchId, :qty, 0)
                        ON CONFLICT (dept_id, drug_id, batch_id) DO UPDATE
                           SET quantity = pharmacy_svc.dept_drug_stock.quantity
                                          + EXCLUDED.quantity,
                               version = pharmacy_svc.dept_drug_stock.version + 1,
                               updated_at = now()
                        """)
                .param("id", UUID.randomUUID()).param("deptId", deptId).param("drugId", drugId)
                .param("batchId", batchId).param("qty", quantity).update();
    }

    public List<DeptDrugStock> listDeptDrugStock(UUID deptId, String keyword, int limit) {
        String pattern = keyword == null || keyword.isBlank() ? null : "%" + keyword.trim() + "%";
        return jdbc.sql("""
                        SELECT s.id, s.dept_id, s.drug_id, d.drug_code, d.name AS drug_name,
                               d.spec, d.unit, s.batch_id, b.batch_no, b.expire_date,
                               s.quantity, s.version
                          FROM pharmacy_svc.dept_drug_stock s
                          JOIN pharmacy_svc.md_drug d ON d.id = s.drug_id
                          JOIN pharmacy_svc.drug_batch b ON b.id = s.batch_id
                         WHERE s.dept_id = :deptId
                           AND s.quantity > 0
                           AND (CAST(:pattern AS text) IS NULL
                                OR d.name ILIKE CAST(:pattern AS text)
                                OR d.drug_code ILIKE CAST(:pattern AS text)
                                OR b.batch_no ILIKE CAST(:pattern AS text))
                         ORDER BY d.drug_code, b.expire_date, b.batch_no
                         LIMIT :limit
                        """)
                .param("deptId", deptId).param("pattern", pattern).param("limit", limit)
                .query(DEPT_DRUG_STOCK_MAPPER).list();
    }

    // ---------------------------------------------------------------- 发药趋势（G15）

    /** 发药日趋势：按发药时刻归日；只统计已发药（含部分退回）的发药单。 */
    public List<DailyDispenseTotal> dailyDispenseTotals(OffsetDateTime from, OffsetDateTime to,
                                                        String zone) {
        return jdbc.sql("""
                        SELECT (dispensed_at AT TIME ZONE CAST(:zone AS text))::date AS day,
                               count(*) AS dispense_count,
                               COALESCE(sum(total_amount), 0) AS amount
                          FROM pharmacy_svc.dispense
                         WHERE status IN ('DISPENSED', 'PARTIAL_RETURNED')
                           AND dispensed_at >= :fromTs AND dispensed_at < :toTs
                         GROUP BY 1
                         ORDER BY 1
                        """)
                .param("fromTs", from).param("toTs", to).param("zone", zone)
                .query((rs, i) -> new DailyDispenseTotal(
                        rs.getDate("day").toLocalDate(), rs.getInt("dispense_count"),
                        rs.getBigDecimal("amount")))
                .list();
    }

    // ---------------------------------------------------------------- 日快照（G18）

    /** 写入/覆盖单日单指标快照；唯一键 (snapshot_date, metric) 保证幂等。 */
    public void upsertReportSnapshot(UUID id, LocalDate day, String metric, BigDecimal value,
                                     String detail, String source) {
        jdbc.sql("""
                        INSERT INTO pharmacy_svc.report_snapshot
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
                          FROM pharmacy_svc.report_snapshot
                         WHERE snapshot_date >= :from AND snapshot_date <= :to
                           AND (CAST(:metric AS text) IS NULL OR metric = CAST(:metric AS text))
                         ORDER BY snapshot_date DESC, metric
                        """)
                .param("from", from).param("to", to).param("metric", metricKey)
                .query((rs, i) -> new ReportSnapshot(
                        rs.getDate("snapshot_date").toLocalDate(), rs.getString("metric"),
                        rs.getBigDecimal("value"), rs.getString("detail"),
                        rs.getString("source"), instantOf(rs, "updated_at")))
                .list();
    }

    // ---------------------------------------------------------------- 抗菌药物专项（G33）

    private static final RowMapper<AntibioticApproval> ANTIBIOTIC_APPROVAL_MAPPER = (rs, i) ->
            new AntibioticApproval(
                    rs.getObject("id", UUID.class),
                    rs.getObject("prescription_id", UUID.class), rs.getInt("max_level"),
                    rs.getString("status"), rs.getString("note"), rs.getString("requested_by"),
                    rs.getTimestamp("requested_at").toInstant(), rs.getString("reviewed_by"),
                    rs.getTimestamp("reviewed_at").toInstant(), rs.getLong("version"));

    /** 处方内最高抗菌药物分级（0=不含抗菌药物）。 */
    public int maxAntibioticLevel(UUID prescriptionId) {
        Integer level = jdbc.sql("""
                        SELECT COALESCE(MAX(d.antibiotic_level), 0)
                          FROM pharmacy_svc.prescription_item i
                          JOIN pharmacy_svc.md_drug d ON d.id = i.drug_id
                         WHERE i.prescription_id = ?
                        """)
                .param(prescriptionId).query(Integer.class).single();
        return level == null ? 0 : level;
    }

    public Optional<AntibioticApproval> findAntibioticApproval(UUID prescriptionId) {
        return jdbc.sql("""
                        SELECT id, prescription_id, max_level, status, note, requested_by,
                               requested_at, reviewed_by, reviewed_at, version
                          FROM pharmacy_svc.antibiotic_approval WHERE prescription_id = ?
                        """)
                .param(prescriptionId).query(ANTIBIOTIC_APPROVAL_MAPPER).optional();
    }

    /** 审批写入/覆盖（同一处方一条；重新审批保留原申请人，更新审批人与状态）。 */
    public void upsertAntibioticApproval(AntibioticApproval a) {
        jdbc.sql("""
                        INSERT INTO pharmacy_svc.antibiotic_approval
                            (id, prescription_id, max_level, status, note, requested_by,
                             requested_at, reviewed_by, reviewed_at)
                        VALUES (:id, :prescriptionId, :maxLevel, :status, :note, :requestedBy,
                                :requestedAt, :reviewedBy, :reviewedAt)
                        ON CONFLICT (prescription_id) DO UPDATE
                           SET status = EXCLUDED.status,
                               note = EXCLUDED.note,
                               reviewed_by = EXCLUDED.reviewed_by,
                               reviewed_at = EXCLUDED.reviewed_at,
                               version = pharmacy_svc.antibiotic_approval.version + 1
                        """)
                .param("id", a.id()).param("prescriptionId", a.prescriptionId())
                .param("maxLevel", a.maxLevel()).param("status", a.status())
                .param("note", a.note()).param("requestedBy", a.requestedBy())
                // Instant 无法被 PostgreSQL 驱动推断类型，写入前显式转 OffsetDateTime（实测 500）
                .param("requestedAt", a.requestedAt().atZone(BusinessTime.zone()).toOffsetDateTime())
                .param("reviewedBy", a.reviewedBy())
                .param("reviewedAt", a.reviewedAt().atZone(BusinessTime.zone()).toOffsetDateTime())
                .update();
    }

    /**
     * 抗菌药物分级统计（G59：按处方院区收敛，{@code campusCsv} 空串表示跨院区角色不限）。
     */
    public List<AntibioticLevelStatRow> antibioticLevelStats(OffsetDateTime fromTs,
                                                             OffsetDateTime toTs,
                                                             String campusCsv) {
        return jdbc.sql("""
                        SELECT d.antibiotic_level AS level, count(DISTINCT p.id) AS rx_count,
                               COALESCE(SUM(i.quantity), 0) AS quantity
                          FROM pharmacy_svc.prescription p
                          JOIN pharmacy_svc.prescription_item i ON i.prescription_id = p.id
                          JOIN pharmacy_svc.md_drug d ON d.id = i.drug_id
                         WHERE d.antibiotic_level > 0
                           AND p.created_at >= :fromTs AND p.created_at < :toTs
                           AND p.status <> 'CANCELLED'
                           AND (CAST(:campusCsv AS text) = ''
                                OR p.campus_id = ANY(string_to_array(CAST(:campusCsv AS text), ',')::uuid[]))
                         GROUP BY d.antibiotic_level
                         ORDER BY d.antibiotic_level
                        """)
                .param("fromTs", fromTs).param("toTs", toTs)
                .param("campusCsv", campusCsv == null ? "" : campusCsv)
                .query((rs, i) -> new AntibioticLevelStatRow(rs.getInt("level"),
                        rs.getLong("rx_count"), rs.getLong("quantity"))).list();
    }

    public List<AntibioticDoctorStatRow> antibioticDoctorStats(OffsetDateTime fromTs,
                                                               OffsetDateTime toTs,
                                                               String campusCsv) {
        return jdbc.sql("""
                        SELECT p.prescriber, count(DISTINCT p.id) AS rx_count,
                               COALESCE(SUM(i.quantity), 0) AS quantity
                          FROM pharmacy_svc.prescription p
                          JOIN pharmacy_svc.prescription_item i ON i.prescription_id = p.id
                          JOIN pharmacy_svc.md_drug d ON d.id = i.drug_id
                         WHERE d.antibiotic_level > 0
                           AND p.created_at >= :fromTs AND p.created_at < :toTs
                           AND p.status <> 'CANCELLED'
                           AND (CAST(:campusCsv AS text) = ''
                                OR p.campus_id = ANY(string_to_array(CAST(:campusCsv AS text), ',')::uuid[]))
                         GROUP BY p.prescriber
                         ORDER BY rx_count DESC, quantity DESC
                         LIMIT 20
                        """)
                .param("fromTs", fromTs).param("toTs", toTs)
                .param("campusCsv", campusCsv == null ? "" : campusCsv)
                .query((rs, i) -> new AntibioticDoctorStatRow(rs.getString("prescriber"),
                        rs.getLong("rx_count"), rs.getLong("quantity"))).list();
    }

    public List<AntibioticDrugStatRow> antibioticDrugStats(OffsetDateTime fromTs,
                                                           OffsetDateTime toTs,
                                                           String campusCsv) {
        return jdbc.sql("""
                        SELECT d.drug_code, d.name AS drug_name, d.antibiotic_level AS level,
                               count(DISTINCT p.id) AS rx_count,
                               COALESCE(SUM(i.quantity), 0) AS quantity
                          FROM pharmacy_svc.prescription p
                          JOIN pharmacy_svc.prescription_item i ON i.prescription_id = p.id
                          JOIN pharmacy_svc.md_drug d ON d.id = i.drug_id
                         WHERE d.antibiotic_level > 0
                           AND p.created_at >= :fromTs AND p.created_at < :toTs
                           AND p.status <> 'CANCELLED'
                           AND (CAST(:campusCsv AS text) = ''
                                OR p.campus_id = ANY(string_to_array(CAST(:campusCsv AS text), ',')::uuid[]))
                         GROUP BY d.drug_code, d.name, d.antibiotic_level
                         ORDER BY quantity DESC, rx_count DESC
                         LIMIT 20
                        """)
                .param("fromTs", fromTs).param("toTs", toTs)
                .param("campusCsv", campusCsv == null ? "" : campusCsv)
                .query((rs, i) -> new AntibioticDrugStatRow(rs.getString("drug_code"),
                        rs.getString("drug_name"), rs.getInt("level"), rs.getLong("rx_count"),
                        rs.getLong("quantity"))).list();
    }

    /** 超疗程提醒：抗菌药物 days 超过阈值（排除已取消/驳回；G59 按处方院区收敛）。 */
    public List<AntibioticOverLimitRow> antibioticOverLimit(OffsetDateTime fromTs,
                                                            OffsetDateTime toTs,
                                                            int thresholdDays, int limit,
                                                            String campusCsv) {
        return jdbc.sql("""
                        SELECT p.id AS prescription_id, p.prescription_no, p.patient_no,
                               p.prescriber, d.name AS drug_name, MAX(i.days) AS max_days,
                               p.created_at
                          FROM pharmacy_svc.prescription p
                          JOIN pharmacy_svc.prescription_item i ON i.prescription_id = p.id
                          JOIN pharmacy_svc.md_drug d ON d.id = i.drug_id
                         WHERE d.antibiotic_level > 0 AND i.days IS NOT NULL
                           AND i.days > :threshold
                           AND p.created_at >= :fromTs AND p.created_at < :toTs
                           AND p.status NOT IN ('CANCELLED', 'REJECTED')
                           AND (CAST(:campusCsv AS text) = ''
                                OR p.campus_id = ANY(string_to_array(CAST(:campusCsv AS text), ',')::uuid[]))
                         GROUP BY p.id, p.prescription_no, p.patient_no, p.prescriber,
                                  d.name, p.created_at
                         ORDER BY max_days DESC, p.created_at DESC
                         LIMIT :limit
                        """)
                .param("fromTs", fromTs).param("toTs", toTs)
                .param("threshold", thresholdDays).param("limit", limit)
                .param("campusCsv", campusCsv == null ? "" : campusCsv)
                .query((rs, i) -> new AntibioticOverLimitRow(
                        rs.getObject("prescription_id", UUID.class),
                        rs.getString("prescription_no"), rs.getString("patient_no"),
                        rs.getString("prescriber"), rs.getString("drug_name"),
                        rs.getObject("max_days") == null ? null : rs.getInt("max_days"),
                        rs.getTimestamp("created_at").toInstant())).list();
    }

    /** 待专项审批：含三级抗菌药物、待审核/已审核但尚无 APPROVED 专项审批的处方（G59 按院区收敛）。 */
    public List<AntibioticPendingRow> antibioticPending(int limit, String campusCsv) {
        return jdbc.sql("""
                        SELECT p.id AS prescription_id, p.prescription_no, p.patient_no,
                               p.prescriber, p.status,
                               string_agg(DISTINCT d.name || '×' || i.quantity, '；')
                                   AS drug_summary,
                               p.created_at
                          FROM pharmacy_svc.prescription p
                          JOIN pharmacy_svc.prescription_item i ON i.prescription_id = p.id
                          JOIN pharmacy_svc.md_drug d ON d.id = i.drug_id
                         WHERE d.antibiotic_level = 3
                           AND p.status IN ('PENDING_REVIEW', 'APPROVED')
                           AND NOT EXISTS (
                               SELECT 1 FROM pharmacy_svc.antibiotic_approval a
                                WHERE a.prescription_id = p.id AND a.status = 'APPROVED')
                           AND (CAST(:campusCsv AS text) = ''
                                OR p.campus_id = ANY(string_to_array(CAST(:campusCsv AS text), ',')::uuid[]))
                         GROUP BY p.id, p.prescription_no, p.patient_no, p.prescriber,
                                  p.status, p.created_at
                         ORDER BY p.created_at
                         LIMIT :limit
                        """)
                .param("limit", limit)
                .param("campusCsv", campusCsv == null ? "" : campusCsv)
                .query((rs, i) -> new AntibioticPendingRow(
                        rs.getObject("prescription_id", UUID.class),
                        rs.getString("prescription_no"), rs.getString("patient_no"),
                        rs.getString("prescriber"), rs.getString("status"),
                        rs.getString("drug_summary"),
                        rs.getTimestamp("created_at").toInstant())).list();
    }

    public int countAntibioticPending(String campusCsv) {
        Integer count = jdbc.sql("""
                        SELECT count(DISTINCT p.id)
                          FROM pharmacy_svc.prescription p
                          JOIN pharmacy_svc.prescription_item i ON i.prescription_id = p.id
                          JOIN pharmacy_svc.md_drug d ON d.id = i.drug_id
                         WHERE d.antibiotic_level = 3
                           AND p.status IN ('PENDING_REVIEW', 'APPROVED')
                           AND NOT EXISTS (
                               SELECT 1 FROM pharmacy_svc.antibiotic_approval a
                                WHERE a.prescription_id = p.id AND a.status = 'APPROVED')
                           AND (CAST(:campusCsv AS text) = ''
                                OR p.campus_id = ANY(string_to_array(CAST(:campusCsv AS text), ',')::uuid[]))
                        """)
                .param("campusCsv", campusCsv == null ? "" : campusCsv)
                .query(Integer.class).single();
        return count == null ? 0 : count;
    }

    // ---------------------------------------------------------------- 静配中心（G34）

    private static final RowMapper<PivasOrder> PIVAS_ORDER_MAPPER = (rs, i) -> new PivasOrder(
            rs.getObject("id", UUID.class), rs.getString("order_no"),
            rs.getObject("prescription_id", UUID.class), rs.getString("prescription_no"),
            rs.getObject("patient_id", UUID.class), rs.getString("patient_no"),
            rs.getObject("visit_id", UUID.class), rs.getString("visit_no"),
            rs.getObject("dept_id", UUID.class), rs.getString("priority"), rs.getString("status"),
            rs.getString("solvent"),
            rs.getObject("solvent_volume_ml") == null ? null : rs.getInt("solvent_volume_ml"),
            rs.getString("drug_summary"), rs.getString("plan_batch"),
            rs.getTimestamp("planned_at") == null ? null : rs.getTimestamp("planned_at").toInstant(),
            rs.getString("prepared_by"),
            rs.getTimestamp("prepared_at") == null ? null : rs.getTimestamp("prepared_at").toInstant(),
            rs.getString("checked_by"),
            rs.getTimestamp("checked_at") == null ? null : rs.getTimestamp("checked_at").toInstant(),
            rs.getString("bag_no"), rs.getString("reject_reason"), rs.getString("created_by"),
            rs.getLong("version"), rs.getTimestamp("created_at").toInstant());

    private static final String PIVAS_ORDER_COLUMNS = """
            id, order_no, prescription_id, prescription_no, patient_id, patient_no,
            visit_id, visit_no, dept_id, priority, status, solvent, solvent_volume_ml,
            drug_summary, plan_batch, planned_at, prepared_by, prepared_at, checked_by,
            checked_at, bag_no, reject_reason, created_by, version, created_at
            """;

    public String nextPivasOrderNo() {
        return nextNo("pharmacy_svc.pivas_order_no_seq", "PV");
    }

    public String nextPivasBagNo() {
        return nextNo("pharmacy_svc.pivas_bag_no_seq", "BAG");
    }

    public void insertPivasOrder(PivasOrder o) {
        jdbc.sql("""
                        INSERT INTO pharmacy_svc.pivas_order
                            (id, order_no, prescription_id, prescription_no, patient_id, patient_no,
                             visit_id, visit_no, dept_id, priority, status, solvent,
                             solvent_volume_ml, drug_summary, plan_batch, planned_at, created_by)
                        VALUES (:id, :orderNo, :prescriptionId, :prescriptionNo, :patientId,
                                CAST(:patientNo AS text), CAST(:visitId AS uuid),
                                CAST(:visitNo AS text), CAST(:deptId AS uuid), :priority,
                                :status, CAST(:solvent AS text), :solventVolumeMl, :drugSummary,
                                CAST(:planBatch AS text),
                                CAST(:plannedAt AS timestamptz), :createdBy)
                        """)
                .param("id", o.id()).param("orderNo", o.orderNo())
                .param("prescriptionId", o.prescriptionId())
                .param("prescriptionNo", o.prescriptionNo())
                .param("patientId", o.patientId()).param("patientNo", o.patientNo())
                .param("visitId", o.visitId()).param("visitNo", o.visitNo())
                .param("deptId", o.deptId()).param("priority", o.priority())
                .param("status", o.status()).param("solvent", o.solvent())
                .param("solventVolumeMl", o.solventVolumeMl())
                .param("drugSummary", o.drugSummary()).param("planBatch", o.planBatch())
                .param("plannedAt", o.plannedAt() == null ? null
                        : o.plannedAt().atZone(BusinessTime.zone()).toOffsetDateTime())
                .param("createdBy", o.createdBy()).update();
    }

    public void insertPivasItem(PivasItem item) {
        jdbc.sql("""
                        INSERT INTO pharmacy_svc.pivas_item
                            (id, pivas_order_id, seq, drug_id, drug_code, drug_name, spec,
                             quantity, unit, dosage, frequency)
                        VALUES (:id, :orderId, :seq, :drugId, :drugCode, :drugName,
                                CAST(:spec AS text), :quantity, CAST(:unit AS text),
                                CAST(:dosage AS text), CAST(:frequency AS text))
                        """)
                .param("id", item.id()).param("orderId", item.pivasOrderId())
                .param("seq", item.seq()).param("drugId", item.drugId())
                .param("drugCode", item.drugCode()).param("drugName", item.drugName())
                .param("spec", item.spec()).param("quantity", item.quantity())
                .param("unit", item.unit()).param("dosage", item.dosage())
                .param("frequency", item.frequency()).update();
    }

    public void insertPivasItemBatch(PivasItemBatch b) {
        jdbc.sql("""
                        INSERT INTO pharmacy_svc.pivas_item_batch
                            (id, pivas_item_id, drug_id, batch_id, batch_no, expire_date, quantity)
                        VALUES (:id, :itemId, :drugId, :batchId, :batchNo, :expireDate, :quantity)
                        """)
                .param("id", b.id()).param("itemId", b.pivasItemId())
                .param("drugId", b.drugId()).param("batchId", b.batchId())
                .param("batchNo", b.batchNo()).param("expireDate", b.expireDate())
                .param("quantity", b.quantity()).update();
    }

    public Optional<PivasOrder> findPivasOrder(UUID id) {
        return jdbc.sql("SELECT " + PIVAS_ORDER_COLUMNS
                        + " FROM pharmacy_svc.pivas_order WHERE id = ?")
                .param(id).query(PIVAS_ORDER_MAPPER).optional();
    }

    /** 行锁：配置/复核/驳回必须在同一事务内串行化。 */
    public Optional<PivasOrder> lockPivasOrder(UUID id) {
        return jdbc.sql("SELECT " + PIVAS_ORDER_COLUMNS
                        + " FROM pharmacy_svc.pivas_order WHERE id = ? FOR UPDATE")
                .param(id).query(PIVAS_ORDER_MAPPER).optional();
    }

    public Optional<PivasOrder> findPivasOrderByPrescription(UUID prescriptionId) {
        return jdbc.sql("SELECT " + PIVAS_ORDER_COLUMNS
                        + " FROM pharmacy_svc.pivas_order WHERE prescription_id = ?")
                .param(prescriptionId).query(PIVAS_ORDER_MAPPER).optional();
    }

    /** 成品输液追溯入口：按标签号反查静配单全链路。 */
    public Optional<PivasOrder> findPivasOrderByBagNo(String bagNo) {
        return jdbc.sql("SELECT " + PIVAS_ORDER_COLUMNS
                        + " FROM pharmacy_svc.pivas_order WHERE bag_no = ?")
                .param(bagNo).query(PIVAS_ORDER_MAPPER).optional();
    }

    public List<PivasOrder> listPivasOrders(String status, int limit) {
        return jdbc.sql("""
                        SELECT id, order_no, prescription_id, prescription_no, patient_id,
                               patient_no, visit_id, visit_no, dept_id, priority, status,
                               solvent, solvent_volume_ml, drug_summary, plan_batch, planned_at,
                               prepared_by, prepared_at, checked_by, checked_at, bag_no,
                               reject_reason, created_by, version, created_at
                          FROM pharmacy_svc.pivas_order
                         WHERE (CAST(:status AS text) IS NULL OR status = CAST(:status AS text))
                         ORDER BY CASE priority WHEN 'URGENT' THEN 0 ELSE 1 END,
                                  created_at DESC
                         LIMIT :limit
                        """)
                .param("status", status).param("limit", limit)
                .query(PIVAS_ORDER_MAPPER).list();
    }

    public List<PivasItem> listPivasItems(UUID orderId) {
        return jdbc.sql("""
                        SELECT id, pivas_order_id, seq, drug_id, drug_code, drug_name, spec,
                               quantity, unit, dosage, frequency
                          FROM pharmacy_svc.pivas_item
                         WHERE pivas_order_id = ?
                         ORDER BY seq
                        """)
                .param(orderId)
                .query((rs, i) -> new PivasItem(
                        rs.getObject("id", UUID.class), rs.getObject("pivas_order_id", UUID.class),
                        rs.getInt("seq"), rs.getObject("drug_id", UUID.class),
                        rs.getString("drug_code"), rs.getString("drug_name"),
                        rs.getString("spec"), rs.getInt("quantity"), rs.getString("unit"),
                        rs.getString("dosage"), rs.getString("frequency"))).list();
    }

    public List<PivasItemBatch> listPivasItemBatches(UUID orderId) {
        return jdbc.sql("""
                        SELECT b.id, b.pivas_item_id, b.drug_id, b.batch_id, b.batch_no,
                               b.expire_date, b.quantity, b.created_at
                          FROM pharmacy_svc.pivas_item_batch b
                          JOIN pharmacy_svc.pivas_item i ON i.id = b.pivas_item_id
                         WHERE i.pivas_order_id = ?
                         ORDER BY i.seq, b.expire_date, b.batch_no
                        """)
                .param(orderId)
                .query((rs, i) -> new PivasItemBatch(
                        rs.getObject("id", UUID.class),
                        rs.getObject("pivas_item_id", UUID.class),
                        rs.getObject("drug_id", UUID.class), rs.getObject("batch_id", UUID.class),
                        rs.getString("batch_no"), rs.getDate("expire_date").toLocalDate(),
                        rs.getInt("quantity"), rs.getTimestamp("created_at").toInstant())).list();
    }

    public boolean markPivasPrepared(UUID id, long expectedVersion, String preparedBy) {
        return jdbc.sql("""
                        UPDATE pharmacy_svc.pivas_order
                           SET status = 'PREPARED', prepared_by = :by, prepared_at = now(),
                               version = version + 1
                         WHERE id = :id AND status = 'PENDING' AND version = :version
                        """)
                .param("by", preparedBy).param("id", id).param("version", expectedVersion)
                .update() > 0;
    }

    public boolean markPivasChecked(UUID id, long expectedVersion, String checkedBy,
                                    String bagNo) {
        return jdbc.sql("""
                        UPDATE pharmacy_svc.pivas_order
                           SET status = 'CHECKED', checked_by = :by, checked_at = now(),
                               bag_no = :bagNo, version = version + 1
                         WHERE id = :id AND status = 'PREPARED' AND version = :version
                        """)
                .param("by", checkedBy).param("bagNo", bagNo)
                .param("id", id).param("version", expectedVersion).update() > 0;
    }

    public boolean markPivasRejected(UUID id, long expectedVersion, String reason) {
        return jdbc.sql("""
                        UPDATE pharmacy_svc.pivas_order
                           SET status = 'REJECTED', reject_reason = :reason, version = version + 1
                         WHERE id = :id AND status IN ('PENDING', 'PREPARED')
                           AND version = :version
                        """)
                .param("reason", reason).param("id", id).param("version", expectedVersion)
                .update() > 0;
    }

    public List<PivasStatusCount> pivasStatusCounts() {
        return jdbc.sql("""
                        SELECT status, count(*) AS cnt
                          FROM pharmacy_svc.pivas_order
                         GROUP BY status
                        """)
                .query((rs, i) -> new PivasStatusCount(rs.getString("status"),
                        rs.getInt("cnt"))).list();
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
