package com.medassist.insurance.repo;

import com.medassist.common.time.BusinessTime;
import com.medassist.insurance.domain.InsuranceModel.ChargeMapping;
import com.medassist.insurance.domain.InsuranceModel.InsuranceItem;
import com.medassist.insurance.domain.InsuranceModel.Member;
import com.medassist.insurance.domain.InsuranceModel.MemberHistory;
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

    private static final RowMapper<MemberHistory> MEMBER_HISTORY_MAPPER = (rs, i) -> new MemberHistory(
            rs.getObject("id", UUID.class), rs.getObject("member_id", UUID.class),
            rs.getObject("patient_id", UUID.class), rs.getString("patient_no"),
            rs.getString("insurance_type"), rs.getString("region"), rs.getString("person_category"),
            rs.getBigDecimal("deductible"), rs.getBigDecimal("reimbursement_ratio"),
            rs.getBigDecimal("cap"), rs.getDate("effective_from").toLocalDate(),
            rs.getDate("effective_to") == null ? null : rs.getDate("effective_to").toLocalDate(),
            rs.getString("status"), rs.getLong("version"), rs.getString("change_type"),
            rs.getString("changed_by"), rs.getTimestamp("changed_at").toInstant(),
            rs.getString("note"));

    private static final RowMapper<SettlementBill> BILL_MAPPER = (rs, i) -> new SettlementBill(
            rs.getObject("id", UUID.class), rs.getString("bill_no"),
            rs.getObject("visit_id", UUID.class), rs.getObject("patient_id", UUID.class),
            rs.getString("patient_no"), rs.getObject("member_id", UUID.class),
            rs.getBigDecimal("total_amount"), rs.getBigDecimal("eligible_amount"),
            rs.getBigDecimal("deductible"), rs.getBigDecimal("insurance_amount"),
            rs.getBigDecimal("self_pay_amount"), rs.getString("status"),
            rs.getString("detail"), rs.getString("created_by"), rs.getString("confirmed_by"),
            rs.getTimestamp("confirmed_at") == null ? null : rs.getTimestamp("confirmed_at").toInstant(),
            rs.getString("void_reason"), rs.getString("voided_by"),
            rs.getTimestamp("voided_at") == null ? null : rs.getTimestamp("voided_at").toInstant(),
            rs.getBigDecimal("snapshot_ratio"), rs.getBigDecimal("snapshot_cap"),
            rs.getLong("version"), rs.getTimestamp("created_at").toInstant());

    /** 结算清单统一列清单（含作废留痕与待遇快照）。 */
    private static final String BILL_COLUMNS = """
            id, bill_no, visit_id, patient_id, patient_no, member_id,
            total_amount, eligible_amount, deductible, insurance_amount, self_pay_amount,
            status, detail::text AS detail, created_by, confirmed_by, confirmed_at,
            void_reason, voided_by, voided_at, snapshot_ratio, snapshot_cap,
            version, created_at
            """;

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

    /** 按医保编码批量取目录项（文件导入做差异比对待用，一次查询避免 N+1）。 */
    public List<InsuranceItem> findItemsByCodes(List<String> codes) {
        if (codes == null || codes.isEmpty()) {
            return List.of();
        }
        return jdbc.sql("""
                        SELECT id, insurance_code, name, category, pay_category,
                               self_pay_ratio, price_limit, enabled, version
                          FROM insurance_svc.md_insurance_item
                         WHERE insurance_code IN (:codes)
                        """).param("codes", codes).query(ITEM_MAPPER).list();
    }

    /** 文件未覆盖到的启用中目录项数量（只统计，不自动停用：停用必须显式操作）。 */
    public long countEnabledItemsNotInCodes(List<String> codes) {
        if (codes == null || codes.isEmpty()) {
            return jdbc.sql("SELECT count(*) FROM insurance_svc.md_insurance_item WHERE enabled")
                    .query(Long.class).single();
        }
        return jdbc.sql("""
                        SELECT count(*) FROM insurance_svc.md_insurance_item
                         WHERE enabled AND insurance_code NOT IN (:codes)
                        """).param("codes", codes).query(Long.class).single();
    }

    /**
     * 按医保编码 UPSERT 目录项目。
     *
     * @param expectedVersion 乐观锁版本：非空时仅当库里版本一致才更新（不一致返回空，调用方转 409）；
     *                        批量导入等"强制覆盖"场景传 null
     */
    public Optional<UUID> upsertItem(InsuranceItem item, Long expectedVersion) {
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
                         WHERE CAST(:expectedVersion AS bigint) IS NULL
                            OR insurance_svc.md_insurance_item.version = CAST(:expectedVersion AS bigint)
                        RETURNING id
                        """)
                .param("id", item.id()).param("code", item.insuranceCode())
                .param("name", item.name()).param("category", item.category())
                .param("payCategory", item.payCategory())
                .param("selfPayRatio", item.selfPayRatio())
                .param("priceLimit", item.priceLimit())
                .param("enabled", item.enabled())
                .param("expectedVersion", expectedVersion)
                .query(UUID.class).optional();
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

    /** 参保变更历史（追加式，最新在前）。 */
    public List<MemberHistory> listMemberHistory(UUID patientId, int limit) {
        return jdbc.sql("""
                        SELECT id, member_id, patient_id, patient_no, insurance_type, region,
                               person_category, deductible, reimbursement_ratio, cap,
                               effective_from, effective_to, status, version, change_type,
                               changed_by, changed_at, note
                          FROM insurance_svc.insurance_member_history
                         WHERE patient_id = :patientId
                         ORDER BY changed_at DESC
                         LIMIT :limit
                        """)
                .param("patientId", patientId).param("limit", limit)
                .query(MEMBER_HISTORY_MAPPER).list();
    }

    public void appendMemberHistory(MemberHistory h) {
        jdbc.sql("""
                        INSERT INTO insurance_svc.insurance_member_history
                            (id, member_id, patient_id, patient_no, insurance_type, region,
                             person_category, deductible, reimbursement_ratio, cap,
                             effective_from, effective_to, status, version, change_type,
                             changed_by, note)
                        VALUES (:id, :memberId, :patientId, :patientNo, :insuranceType, :region,
                                :personCategory, :deductible, :ratio, :cap,
                                :fromDate, CAST(:toDate AS date), :status, :version, :changeType,
                                :changedBy, :note)
                        """)
                .param("id", h.id()).param("memberId", h.memberId())
                .param("patientId", h.patientId()).param("patientNo", h.patientNo())
                .param("insuranceType", h.insuranceType()).param("region", h.region())
                .param("personCategory", h.personCategory()).param("deductible", h.deductible())
                .param("ratio", h.reimbursementRatio()).param("cap", h.cap())
                .param("fromDate", h.effectiveFrom()).param("toDate", h.effectiveTo())
                .param("status", h.status()).param("version", h.version())
                .param("changeType", h.changeType()).param("changedBy", h.changedBy())
                .param("note", h.note()).update();
    }

    /**
     * 参保登记 UPSERT（带版本 CAS）。
     *
     * <p>已存在参保时必须匹配 {@code expectedVersion}，否则不更新、返回空——
     * 由服务层转 409。不覆盖 version 的"最后写赢"会让并发编辑静默丢失待遇参数。
     */
    public Optional<UUID> upsertMember(Member m, long expectedVersion) {
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
                         WHERE insurance_svc.insurance_member.version = :version
                        RETURNING id
                        """)
                .param("id", m.id()).param("patientId", m.patientId())
                .param("patientNo", m.patientNo()).param("insuranceType", m.insuranceType())
                .param("region", m.region()).param("personCategory", m.personCategory())
                .param("deductible", m.deductible()).param("ratio", m.reimbursementRatio())
                .param("cap", m.cap()).param("fromDate", m.effectiveFrom())
                .param("toDate", m.effectiveTo()).param("status", m.status())
                .param("version", expectedVersion)
                .query(UUID.class).optional();
    }

    // ---------------------------------------------------------------- 结算清单

    /** 该就诊的"有效"清单：作废单不算，因此作废后可以重新生成。 */
    public Optional<SettlementBill> findBillByVisit(UUID visitId) {
        return jdbc.sql("SELECT " + BILL_COLUMNS
                        + " FROM insurance_svc.settlement_bill WHERE visit_id = ? AND status <> 'VOIDED'")
                .param(visitId).query(BILL_MAPPER).optional();
    }

    /** 加行锁；必须在事务内调用（同样只看有效清单）。 */
    public Optional<SettlementBill> lockBillByVisit(UUID visitId) {
        return jdbc.sql("SELECT " + BILL_COLUMNS
                        + " FROM insurance_svc.settlement_bill WHERE visit_id = ? AND status <> 'VOIDED'"
                        + " FOR UPDATE")
                .param(visitId).query(BILL_MAPPER).optional();
    }

    /**
     * 年度已用统筹额度：同一参保人、同一自然年内已确认（CONFIRMED）清单的统筹支付合计。
     *
     * <p>口径说明：按**确认时间**归属年度（基金实际支付年度），作废单与草稿不计入，
     * 重算/对账时通过 {@code excludeBillId} 排除当前清单自身，保证"生成时 = 复核时 = 对账时"。
     */
    public BigDecimal insuranceUsedInYear(UUID patientId, LocalDate day, UUID excludeBillId) {
        LocalDate yearStart = LocalDate.of(day.getYear(), 1, 1);
        return jdbc.sql("""
                        SELECT COALESCE(SUM(insurance_amount), 0)
                          FROM insurance_svc.settlement_bill
                         WHERE patient_id = :patientId
                           AND status = 'CONFIRMED'
                           AND confirmed_at >= CAST(:yearStart AS date)
                           AND confirmed_at < CAST(:yearStart AS date) + INTERVAL '1 year'
                           AND (CAST(:excludeId AS uuid) IS NULL OR id <> CAST(:excludeId AS uuid))
                        """)
                .param("patientId", patientId).param("yearStart", yearStart)
                .param("excludeId", excludeBillId)
                .query(BigDecimal.class).single();
    }

    public Optional<SettlementBill> findBillById(UUID id) {
        return jdbc.sql("SELECT " + BILL_COLUMNS
                        + " FROM insurance_svc.settlement_bill WHERE id = ?")
                .param(id).query(BILL_MAPPER).optional();
    }

    /** 加行锁；作废单也需要能加锁（作废校验要用）。 */
    public Optional<SettlementBill> lockBillById(UUID id) {
        return jdbc.sql("SELECT " + BILL_COLUMNS
                        + " FROM insurance_svc.settlement_bill WHERE id = ? FOR UPDATE")
                .param(id).query(BILL_MAPPER).optional();
    }

    /**
     * 插入草稿清单；同一就诊并发首插时靠有效清单唯一索引兜底。
     *
     * @return false 表示已被并发请求抢先插入（唯一索引冲突被 ON CONFLICT 吞掉），调用方应返回 409
     */
    public boolean insertBill(SettlementBill b) {
        return jdbc.sql("""
                        INSERT INTO insurance_svc.settlement_bill
                            (id, bill_no, visit_id, patient_id, patient_no, member_id,
                             total_amount, eligible_amount, deductible, insurance_amount,
                             self_pay_amount, status, detail, created_by,
                             snapshot_ratio, snapshot_cap)
                        VALUES (:id, :billNo, :visitId, :patientId, :patientNo, :memberId,
                                :total, :eligible, :deductible, :insurance, :selfPay, 'DRAFT',
                                CAST(:detail AS jsonb), :createdBy, :snapshotRatio, :snapshotCap)
                        ON CONFLICT DO NOTHING
                        """)
                .param("id", b.id()).param("billNo", b.billNo())
                .param("visitId", b.visitId()).param("patientId", b.patientId())
                .param("patientNo", b.patientNo()).param("memberId", b.memberId())
                .param("total", b.totalAmount()).param("eligible", b.eligibleAmount())
                .param("deductible", b.deductible()).param("insurance", b.insuranceAmount())
                .param("selfPay", b.selfPayAmount()).param("detail", b.detail())
                .param("createdBy", b.createdBy())
                .param("snapshotRatio", b.snapshotRatio()).param("snapshotCap", b.snapshotCap())
                .update() > 0;
    }

    /** 重算草稿（CAS：仅 DRAFT）；待遇参数可能已调整，快照同步刷新。 */
    public boolean updateDraftBill(UUID id, BigDecimal total, BigDecimal eligible,
                                   BigDecimal deductible, BigDecimal insurance, BigDecimal selfPay,
                                   String detail, BigDecimal snapshotRatio, BigDecimal snapshotCap,
                                   long expectedVersion) {
        return jdbc.sql("""
                        UPDATE insurance_svc.settlement_bill
                           SET total_amount = :total, eligible_amount = :eligible,
                               deductible = :deductible, insurance_amount = :insurance,
                               self_pay_amount = :selfPay, detail = CAST(:detail AS jsonb),
                               snapshot_ratio = :snapshotRatio, snapshot_cap = :snapshotCap,
                               version = version + 1, updated_at = now()
                         WHERE id = :id AND status = 'DRAFT' AND version = :version
                        """)
                .param("total", total).param("eligible", eligible).param("deductible", deductible)
                .param("insurance", insurance).param("selfPay", selfPay).param("detail", detail)
                .param("snapshotRatio", snapshotRatio).param("snapshotCap", snapshotCap)
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

    /** 作废（CAS：仅 CONFIRMED）；作废后该就诊可重新生成清单。 */
    public boolean voidBill(UUID id, String reason, String actor, long expectedVersion) {
        return jdbc.sql("""
                        UPDATE insurance_svc.settlement_bill
                           SET status = 'VOIDED', void_reason = :reason, voided_by = :actor,
                               voided_at = now(), version = version + 1, updated_at = now()
                         WHERE id = :id AND status = 'CONFIRMED' AND version = :version
                        """)
                .param("reason", reason).param("actor", actor)
                .param("id", id).param("version", expectedVersion).update() > 0;
    }

    public List<SettlementBill> listBills(UUID patientId, String status, int limit) {
        return jdbc.sql("SELECT " + BILL_COLUMNS + """
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
