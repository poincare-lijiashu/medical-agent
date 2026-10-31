package com.medassist.insurance.repo;

import com.medassist.common.time.BusinessTime;
import com.medassist.insurance.domain.InsuranceModel.ChargeMapping;
import com.medassist.insurance.domain.InsuranceModel.InsuranceItem;
import com.medassist.insurance.domain.InsuranceModel.Member;
import com.medassist.insurance.domain.InsuranceModel.SettlementBill;
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
 * 医保仓储（JdbcClient 显式 SQL）。
 *
 * <p>目录与映射用 UPSERT（唯一键冲突即更新），结算清单用"期望状态 + 版本号"CAS。
 */
@Repository
public class InsuranceRepository {

    private static final RowMapper<InsuranceItem> ITEM_MAPPER = (rs, i) -> new InsuranceItem(
            rs.getObject("id", UUID.class), rs.getString("insurance_code"), rs.getString("name"),
            rs.getString("category"), rs.getString("pay_category"),
            rs.getBigDecimal("self_pay_ratio"), rs.getBigDecimal("price_limit"),
            rs.getBoolean("enabled"), rs.getLong("version"));

    private static final RowMapper<ChargeMapping> MAPPING_MAPPER = (rs, i) -> new ChargeMapping(
            rs.getObject("id", UUID.class), rs.getString("charge_item_code"),
            rs.getString("insurance_code"), rs.getBoolean("enabled"), rs.getLong("version"));

    private static final RowMapper<Member> MEMBER_MAPPER = (rs, i) -> new Member(
            rs.getObject("id", UUID.class), rs.getObject("patient_id", UUID.class),
            rs.getString("patient_no"), rs.getString("insurance_type"), rs.getString("region"),
            rs.getString("person_category"), rs.getBigDecimal("deductible"),
            rs.getBigDecimal("reimbursement_ratio"), rs.getBigDecimal("cap"),
            rs.getDate("effective_from").toLocalDate(),
            rs.getDate("effective_to") == null ? null : rs.getDate("effective_to").toLocalDate(),
            rs.getString("status"), rs.getLong("version"));

    private static final RowMapper<SettlementBill> BILL_MAPPER = (rs, i) -> new SettlementBill(
            rs.getObject("id", UUID.class), rs.getString("bill_no"),
            rs.getObject("visit_id", UUID.class), rs.getObject("patient_id", UUID.class),
            rs.getString("patient_no"), rs.getObject("member_id", UUID.class),
            rs.getBigDecimal("total_amount"), rs.getBigDecimal("eligible_amount"),
            rs.getBigDecimal("deductible"), rs.getBigDecimal("insurance_amount"),
            rs.getBigDecimal("self_pay_amount"), rs.getString("status"),
            rs.getString("detail"), rs.getString("created_by"), rs.getString("confirmed_by"),
            rs.getTimestamp("confirmed_at") == null ? null : rs.getTimestamp("confirmed_at").toInstant(),
            rs.getLong("version"), rs.getTimestamp("created_at").toInstant());

    private final JdbcClient jdbc;

    public InsuranceRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public String nextBillNo() {
        Long seq = jdbc.sql("SELECT nextval(CAST(:seq AS regclass))")
                .param("seq", "insurance_svc.bill_no_seq").query(Long.class).single();
        return "SB" + BusinessTime.today().format(DateTimeFormatter.BASIC_ISO_DATE)
                + String.format("%06d", seq);
    }

    // ---------------------------------------------------------------- 医保目录

    public List<InsuranceItem> listItems(String category, Boolean enabled, String pattern, int limit) {
        return jdbc.sql("""
                        SELECT id, insurance_code, name, category, pay_category,
                               self_pay_ratio, price_limit, enabled, version
                          FROM insurance_svc.md_insurance_item
                         WHERE (CAST(:category AS text) IS NULL OR category = CAST(:category AS text))
                           AND (CAST(:enabled AS boolean) IS NULL OR enabled = CAST(:enabled AS boolean))
                           AND (CAST(:pattern AS text) IS NULL
                                OR insurance_code ILIKE CAST(:pattern AS text) ESCAPE '\\'
                                OR name ILIKE CAST(:pattern AS text) ESCAPE '\\')
                         ORDER BY category, insurance_code
                         LIMIT :limit
                        """)
                .param("category", category).param("enabled", enabled)
                .param("pattern", pattern).param("limit", limit)
                .query(ITEM_MAPPER).list();
    }

    public Optional<InsuranceItem> findItemByCode(String insuranceCode) {
        return jdbc.sql("""
                        SELECT id, insurance_code, name, category, pay_category,
                               self_pay_ratio, price_limit, enabled, version
                          FROM insurance_svc.md_insurance_item WHERE insurance_code = ?
                        """).param(insuranceCode).query(ITEM_MAPPER).optional();
    }

    public Optional<InsuranceItem> findItemById(UUID id) {
        return jdbc.sql("""
                        SELECT id, insurance_code, name, category, pay_category,
                               self_pay_ratio, price_limit, enabled, version
                          FROM insurance_svc.md_insurance_item WHERE id = ?
                        """).param(id).query(ITEM_MAPPER).optional();
    }

    /** 目录导入：按医保编码 UPSERT，返回主键（已存在时返回原主键）。 */
    public UUID upsertItem(InsuranceItem item) {
        return jdbc.sql("""
                        INSERT INTO insurance_svc.md_insurance_item
                            (id, insurance_code, name, category, pay_category, self_pay_ratio,
                             price_limit, enabled)
                        VALUES (:id, :code, :name, :category, :payCategory, :selfPayRatio,
                                CAST(:priceLimit AS numeric), :enabled)
                        ON CONFLICT (insurance_code) DO UPDATE
                           SET name = EXCLUDED.name,
                               category = EXCLUDED.category,
                               pay_category = EXCLUDED.pay_category,
                               self_pay_ratio = EXCLUDED.self_pay_ratio,
                               price_limit = EXCLUDED.price_limit,
                               enabled = EXCLUDED.enabled,
                               version = insurance_svc.md_insurance_item.version + 1,
                               updated_at = now()
                        RETURNING id
                        """)
                .param("id", item.id()).param("code", item.insuranceCode())
                .param("name", item.name()).param("category", item.category())
                .param("payCategory", item.payCategory())
                .param("selfPayRatio", item.selfPayRatio())
                .param("priceLimit", item.priceLimit())
                .param("enabled", item.enabled())
                .query(UUID.class).single();
    }

    public boolean setItemEnabled(UUID id, boolean enabled, long expectedVersion) {
        return jdbc.sql("""
                        UPDATE insurance_svc.md_insurance_item
                           SET enabled = :enabled, version = version + 1, updated_at = now()
                         WHERE id = :id AND version = :version
                        """)
                .param("enabled", enabled).param("id", id).param("version", expectedVersion)
                .update() > 0;
    }

    // ---------------------------------------------------------------- 院内映射

    public List<ChargeMapping> listMappings(String pattern, int limit) {
        return jdbc.sql("""
                        SELECT id, charge_item_code, insurance_code, enabled, version
                          FROM insurance_svc.charge_item_mapping
                         WHERE (CAST(:pattern AS text) IS NULL
                                OR charge_item_code ILIKE CAST(:pattern AS text) ESCAPE '\\'
                                OR insurance_code ILIKE CAST(:pattern AS text) ESCAPE '\\')
                         ORDER BY charge_item_code
                         LIMIT :limit
                        """)
                .param("pattern", pattern).param("limit", limit)
                .query(MAPPING_MAPPER).list();
    }

    public Optional<ChargeMapping> findMapping(String chargeItemCode) {
        return jdbc.sql("""
                        SELECT id, charge_item_code, insurance_code, enabled, version
                          FROM insurance_svc.charge_item_mapping WHERE charge_item_code = ?
                        """).param(chargeItemCode).query(MAPPING_MAPPER).optional();
    }

    public void upsertMapping(UUID id, String chargeItemCode, String insuranceCode, boolean enabled) {
        jdbc.sql("""
                        INSERT INTO insurance_svc.charge_item_mapping
                            (id, charge_item_code, insurance_code, enabled)
                        VALUES (:id, :chargeItemCode, :insuranceCode, :enabled)
                        ON CONFLICT (charge_item_code) DO UPDATE
                           SET insurance_code = EXCLUDED.insurance_code,
                               enabled = EXCLUDED.enabled,
                               version = insurance_svc.charge_item_mapping.version + 1,
                               updated_at = now()
                        """)
                .param("id", id).param("chargeItemCode", chargeItemCode)
                .param("insuranceCode", insuranceCode).param("enabled", enabled).update();
    }

    // ---------------------------------------------------------------- 参保登记

    public Optional<Member> findMemberByPatient(UUID patientId) {
        return jdbc.sql("""
                        SELECT id, patient_id, patient_no, insurance_type, region, person_category,
                               deductible, reimbursement_ratio, cap, effective_from, effective_to,
                               status, version
                          FROM insurance_svc.insurance_member WHERE patient_id = ?
                        """).param(patientId).query(MEMBER_MAPPER).optional();
    }

    public Optional<Member> findMemberById(UUID id) {
        return jdbc.sql("""
                        SELECT id, patient_id, patient_no, insurance_type, region, person_category,
                               deductible, reimbursement_ratio, cap, effective_from, effective_to,
                               status, version
                          FROM insurance_svc.insurance_member WHERE id = ?
                        """).param(id).query(MEMBER_MAPPER).optional();
    }

    public UUID upsertMember(Member m) {
        return jdbc.sql("""
                        INSERT INTO insurance_svc.insurance_member
                            (id, patient_id, patient_no, insurance_type, region, person_category,
                             deductible, reimbursement_ratio, cap, effective_from, effective_to,
                             status)
                        VALUES (:id, :patientId, :patientNo, :insuranceType, :region, :personCategory,
                                :deductible, :ratio, :cap, :fromDate, CAST(:toDate AS date), :status)
                        ON CONFLICT (patient_id) DO UPDATE
                           SET patient_no = EXCLUDED.patient_no,
                               insurance_type = EXCLUDED.insurance_type,
                               region = EXCLUDED.region,
                               person_category = EXCLUDED.person_category,
                               deductible = EXCLUDED.deductible,
                               reimbursement_ratio = EXCLUDED.reimbursement_ratio,
                               cap = EXCLUDED.cap,
                               effective_from = EXCLUDED.effective_from,
                               effective_to = EXCLUDED.effective_to,
                               status = EXCLUDED.status,
                               version = insurance_svc.insurance_member.version + 1,
                               updated_at = now()
                        RETURNING id
                        """)
                .param("id", m.id()).param("patientId", m.patientId())
                .param("patientNo", m.patientNo()).param("insuranceType", m.insuranceType())
                .param("region", m.region()).param("personCategory", m.personCategory())
                .param("deductible", m.deductible()).param("ratio", m.reimbursementRatio())
                .param("cap", m.cap()).param("fromDate", m.effectiveFrom())
                .param("toDate", m.effectiveTo()).param("status", m.status())
                .query(UUID.class).single();
    }

    // ---------------------------------------------------------------- 结算清单

    public Optional<SettlementBill> findBillByVisit(UUID visitId) {
        return jdbc.sql("""
                        SELECT id, bill_no, visit_id, patient_id, patient_no, member_id,
                               total_amount, eligible_amount, deductible, insurance_amount,
                               self_pay_amount, status, detail::text AS detail, created_by,
                               confirmed_by, confirmed_at, version, created_at
                          FROM insurance_svc.settlement_bill WHERE visit_id = ?
                        """).param(visitId).query(BILL_MAPPER).optional();
    }

    /** 加行锁；必须在事务内调用。 */
    public Optional<SettlementBill> lockBillByVisit(UUID visitId) {
        return jdbc.sql("""
                        SELECT id, bill_no, visit_id, patient_id, patient_no, member_id,
                               total_amount, eligible_amount, deductible, insurance_amount,
                               self_pay_amount, status, detail::text AS detail, created_by,
                               confirmed_by, confirmed_at, version, created_at
                          FROM insurance_svc.settlement_bill WHERE visit_id = ? FOR UPDATE
                        """).param(visitId).query(BILL_MAPPER).optional();
    }

    public Optional<SettlementBill> findBillById(UUID id) {
        return jdbc.sql("""
                        SELECT id, bill_no, visit_id, patient_id, patient_no, member_id,
                               total_amount, eligible_amount, deductible, insurance_amount,
                               self_pay_amount, status, detail::text AS detail, created_by,
                               confirmed_by, confirmed_at, version, created_at
                          FROM insurance_svc.settlement_bill WHERE id = ?
                        """).param(id).query(BILL_MAPPER).optional();
    }

    public Optional<SettlementBill> lockBillById(UUID id) {
        return jdbc.sql("""
                        SELECT id, bill_no, visit_id, patient_id, patient_no, member_id,
                               total_amount, eligible_amount, deductible, insurance_amount,
                               self_pay_amount, status, detail::text AS detail, created_by,
                               confirmed_by, confirmed_at, version, created_at
                          FROM insurance_svc.settlement_bill WHERE id = ? FOR UPDATE
                        """).param(id).query(BILL_MAPPER).optional();
    }

    public void insertBill(SettlementBill b) {
        jdbc.sql("""
                        INSERT INTO insurance_svc.settlement_bill
                            (id, bill_no, visit_id, patient_id, patient_no, member_id,
                             total_amount, eligible_amount, deductible, insurance_amount,
                             self_pay_amount, status, detail, created_by)
                        VALUES (:id, :billNo, :visitId, :patientId, :patientNo, :memberId,
                                :total, :eligible, :deductible, :insurance, :selfPay, 'DRAFT',
                                CAST(:detail AS jsonb), :createdBy)
                        """)
                .param("id", b.id()).param("billNo", b.billNo())
                .param("visitId", b.visitId()).param("patientId", b.patientId())
                .param("patientNo", b.patientNo()).param("memberId", b.memberId())
                .param("total", b.totalAmount()).param("eligible", b.eligibleAmount())
                .param("deductible", b.deductible()).param("insurance", b.insuranceAmount())
                .param("selfPay", b.selfPayAmount()).param("detail", b.detail())
                .param("createdBy", b.createdBy()).update();
    }

    /** 重算草稿（CAS：仅 DRAFT）。 */
    public boolean updateDraftBill(UUID id, BigDecimal total, BigDecimal eligible,
                                   BigDecimal deductible, BigDecimal insurance, BigDecimal selfPay,
                                   String detail, long expectedVersion) {
        return jdbc.sql("""
                        UPDATE insurance_svc.settlement_bill
                           SET total_amount = :total, eligible_amount = :eligible,
                               deductible = :deductible, insurance_amount = :insurance,
                               self_pay_amount = :selfPay, detail = CAST(:detail AS jsonb),
                               version = version + 1, updated_at = now()
                         WHERE id = :id AND status = 'DRAFT' AND version = :version
                        """)
                .param("total", total).param("eligible", eligible).param("deductible", deductible)
                .param("insurance", insurance).param("selfPay", selfPay).param("detail", detail)
                .param("id", id).param("version", expectedVersion).update() > 0;
    }

    /** 确认（CAS：仅 DRAFT）。 */
    public boolean confirmBill(UUID id, String actor, long expectedVersion) {
        return jdbc.sql("""
                        UPDATE insurance_svc.settlement_bill
                           SET status = 'CONFIRMED', confirmed_by = :actor, confirmed_at = now(),
                               version = version + 1, updated_at = now()
                         WHERE id = :id AND status = 'DRAFT' AND version = :version
                        """)
                .param("actor", actor).param("id", id).param("version", expectedVersion)
                .update() > 0;
    }

    public List<SettlementBill> listBills(UUID patientId, String status, int limit) {
        return jdbc.sql("""
                        SELECT id, bill_no, visit_id, patient_id, patient_no, member_id,
                               total_amount, eligible_amount, deductible, insurance_amount,
                               self_pay_amount, status, detail::text AS detail, created_by,
                               confirmed_by, confirmed_at, version, created_at
                          FROM insurance_svc.settlement_bill
                         WHERE (CAST(:patientId AS uuid) IS NULL OR patient_id = CAST(:patientId AS uuid))
                           AND (CAST(:status AS text) IS NULL OR status = CAST(:status AS text))
                         ORDER BY created_at DESC
                         LIMIT :limit
                        """)
                .param("patientId", patientId).param("status", status).param("limit", limit)
                .query(BILL_MAPPER).list();
    }

    // ---------------------------------------------------------------- 审计

    public void logChange(String actor, String action, String targetType, String targetId,
                          String detailJson) {
        jdbc.sql("""
                        INSERT INTO insurance_svc.insurance_change_log
                            (actor, action, target_type, target_id, detail)
                        VALUES (:actor, :action, :targetType, :targetId, CAST(:detail AS jsonb))
                        """)
                .param("actor", actor).param("action", action)
                .param("targetType", targetType).param("targetId", targetId)
                .param("detail", detailJson == null ? "{}" : detailJson).update();
    }
}
