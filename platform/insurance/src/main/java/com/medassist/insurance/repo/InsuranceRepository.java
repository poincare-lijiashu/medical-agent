package com.medassist.insurance.repo;

import com.medassist.common.time.BusinessTime;
import com.medassist.insurance.domain.InsuranceModel.ChargeMapping;
import com.medassist.insurance.domain.InsuranceModel.ExportBatch;
import com.medassist.insurance.domain.InsuranceModel.ExportMessageTemplate;
import com.medassist.insurance.domain.InsuranceModel.GroupSimulation;
import com.medassist.insurance.domain.InsuranceModel.GrouperAdrgRule;
import com.medassist.insurance.domain.InsuranceModel.GrouperCcExclusion;
import com.medassist.insurance.domain.InsuranceModel.GrouperCcRule;
import com.medassist.insurance.domain.InsuranceModel.GrouperCodeCount;
import com.medassist.insurance.domain.InsuranceModel.GrouperDipCatalog;
import com.medassist.insurance.domain.InsuranceModel.GrouperDrgDef;
import com.medassist.insurance.domain.InsuranceModel.GrouperEngineStat;
import com.medassist.insurance.domain.InsuranceModel.GrouperMdcRule;
import com.medassist.insurance.domain.InsuranceModel.GrouperReasonCount;
import com.medassist.insurance.domain.InsuranceModel.GrouperResult;
import com.medassist.insurance.domain.InsuranceModel.GrouperRuleSet;
import com.medassist.insurance.domain.InsuranceModel.InsuranceItem;
import com.medassist.insurance.domain.InsuranceModel.Member;
import com.medassist.insurance.domain.InsuranceModel.MemberHistory;
import com.medassist.insurance.domain.InsuranceModel.PaymentGroup;
import com.medassist.insurance.domain.InsuranceModel.PaymentGroupRule;
import com.medassist.insurance.domain.InsuranceModel.PaymentScheme;
import com.medassist.insurance.domain.InsuranceModel.RealtimeSettlement;
import com.medassist.insurance.domain.InsuranceModel.ReconBatch;
import com.medassist.insurance.domain.InsuranceModel.ReconBillRow;
import com.medassist.insurance.domain.InsuranceModel.ReconDiff;
import com.medassist.insurance.domain.InsuranceModel.PayerConfig;
import com.medassist.insurance.domain.InsuranceModel.SettlementAllocation;
import com.medassist.insurance.domain.InsuranceModel.PayerAllocationLine;
import com.medassist.insurance.domain.InsuranceModel.AllocationBillRow;
import com.medassist.insurance.domain.InsuranceModel.SchemeRate;
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
            rs.getString("patient_no"), rs.getDate("service_date").toLocalDate(),
            rs.getObject("member_id", UUID.class),
            rs.getBigDecimal("total_amount"), rs.getBigDecimal("eligible_amount"),
            rs.getBigDecimal("deductible"), rs.getBigDecimal("insurance_amount"),
            rs.getBigDecimal("self_pay_amount"), rs.getString("status"),
            rs.getString("detail"), rs.getString("created_by"), rs.getString("confirmed_by"),
            rs.getTimestamp("confirmed_at") == null ? null : rs.getTimestamp("confirmed_at").toInstant(),
            rs.getString("void_reason"), rs.getString("voided_by"),
            rs.getTimestamp("voided_at") == null ? null : rs.getTimestamp("voided_at").toInstant(),
            rs.getBigDecimal("snapshot_ratio"), rs.getBigDecimal("snapshot_cap"),
            rs.getLong("version"), rs.getTimestamp("created_at").toInstant(),
            rs.getString("primary_diagnosis_code"), rs.getString("primary_diagnosis_name"),
            rs.getInt("diagnosis_count"),
            rs.getString("diagnoses") == null ? "[]" : rs.getString("diagnoses"));

    /** 结算清单统一列清单（含作废留痕与待遇快照）。 */
    private static final String BILL_COLUMNS = """
            id, bill_no, visit_id, patient_id, patient_no, service_date, member_id,
            total_amount, eligible_amount, deductible, insurance_amount, self_pay_amount,
            status, detail::text AS detail, created_by, confirmed_by, confirmed_at,
            void_reason, voided_by, voided_at, snapshot_ratio, snapshot_cap,
            primary_diagnosis_code, primary_diagnosis_name, diagnosis_count,
            diagnoses::text AS diagnoses,
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
     * 年度已用统筹额度：同一参保人、同一业务年度内已确认（CONFIRMED）清单的统筹支付合计。
     *
     * <p>口径（P1-D2）：按清单的**就诊业务日期** {@code service_date} 归年，不按确认时刻。
     * 按确认时刻归年时，12/31 生成的草稿在用"今天"估算额度、1/1 确认时"今天"已经跨年，
     * 两侧年度桶不同 → 重算金额必然不一致 → 清单永久 409，只能作废重出。
     * 落库的业务日期在生成时固化，生成/复核/对账读同一个值，口径才自洽。
     *
     * <p>作废单与草稿不计入；重算/对账时通过 {@code excludeBillId} 排除当前清单自身。
     */
    public BigDecimal insuranceUsedInYear(UUID patientId, LocalDate serviceDate, UUID excludeBillId) {
        LocalDate yearStart = LocalDate.of(serviceDate.getYear(), 1, 1);
        return jdbc.sql("""
                        SELECT COALESCE(SUM(insurance_amount), 0)
                          FROM insurance_svc.settlement_bill
                         WHERE patient_id = :patientId
                           AND status = 'CONFIRMED'
                           AND service_date >= :fromDate
                           AND service_date < :toDate
                           AND (CAST(:excludeId AS uuid) IS NULL OR id <> CAST(:excludeId AS uuid))
                        """)
                .param("patientId", patientId)
                .param("fromDate", yearStart)
                .param("toDate", yearStart.plusYears(1))
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
        return insertBill(b, com.medassist.common.security.DataScope.DEFAULT_CAMPUS_ID);
    }

    /** 插入草稿清单并固化院区（G48）；并发冲突语义同 {@link #insertBill(SettlementBill)}。 */
    public boolean insertBill(SettlementBill b, UUID campusId) {
        return jdbc.sql("""
                        INSERT INTO insurance_svc.settlement_bill
                            (id, bill_no, visit_id, patient_id, patient_no, service_date, member_id,
                             total_amount, eligible_amount, deductible, insurance_amount,
                             self_pay_amount, status, detail, created_by,
                             snapshot_ratio, snapshot_cap, campus_id)
                        VALUES (:id, :billNo, :visitId, :patientId, :patientNo, :serviceDate, :memberId,
                                :total, :eligible, :deductible, :insurance, :selfPay, 'DRAFT',
                                CAST(:detail AS jsonb), :createdBy, :snapshotRatio, :snapshotCap,
                                :campusId)
                        ON CONFLICT DO NOTHING
                        """)
                .param("id", b.id()).param("billNo", b.billNo())
                .param("visitId", b.visitId()).param("patientId", b.patientId())
                .param("patientNo", b.patientNo()).param("serviceDate", b.serviceDate())
                .param("memberId", b.memberId())
                .param("total", b.totalAmount()).param("eligible", b.eligibleAmount())
                .param("deductible", b.deductible()).param("insurance", b.insuranceAmount())
                .param("selfPay", b.selfPayAmount()).param("detail", b.detail())
                .param("createdBy", b.createdBy())
                .param("snapshotRatio", b.snapshotRatio()).param("snapshotCap", b.snapshotCap())
                .param("campusId", campusId)
                .update() > 0;
    }

    /** 清单所属院区（行级权限校验用；empty 表示清单不存在）。 */
    public Optional<UUID> findBillCampus(UUID id) {
        return jdbc.sql("SELECT campus_id FROM insurance_svc.settlement_bill WHERE id = ?")
                .param(id)
                .query((rs, i) -> rs.getObject("campus_id", UUID.class))
                .optional();
    }

    /** 实时结算交易所属院区（经清单间接归属，G48）。 */
    public Optional<UUID> findRealtimeBillCampus(UUID id) {
        return jdbc.sql("""
                        SELECT b.campus_id
                          FROM insurance_svc.realtime_settlement r
                          JOIN insurance_svc.settlement_bill b ON b.id = r.bill_id
                         WHERE r.id = ?
                        """)
                .param(id)
                .query((rs, i) -> rs.getObject("campus_id", UUID.class))
                .optional();
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

    /**
     * 写入诊断快照（生成/重算清单时调用）。
     *
     * <p>单独一个 UPDATE 而不是塞进 insert/update：诊断是"内容快照"，
     * 与金额、待遇快照分开维护，报盘时只读清单即可拿到完整诊断。
     */
    public boolean updateBillDiagnoses(UUID id, String primaryCode, String primaryName,
                                       int diagnosisCount, String diagnosesJson) {
        return jdbc.sql("""
                        UPDATE insurance_svc.settlement_bill
                           SET primary_diagnosis_code = CAST(:primaryCode AS text),
                               primary_diagnosis_name = CAST(:primaryName AS text),
                               diagnosis_count = :diagnosisCount,
                               diagnoses = CAST(:diagnoses AS jsonb),
                               version = version + 1, updated_at = now()
                         WHERE id = :id AND status = 'DRAFT'
                        """)
                .param("primaryCode", primaryCode).param("primaryName", primaryName)
                .param("diagnosisCount", diagnosisCount)
                .param("diagnoses", diagnosesJson == null ? "[]" : diagnosesJson)
                .param("id", id).update() > 0;
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
        return listBills(patientId, status, limit, "");
    }

    /** 结算清单列表（G48 按院区收敛；空 CSV 表示不限院区）。 */
    public List<SettlementBill> listBills(UUID patientId, String status, int limit, String campusCsv) {
        return jdbc.sql("SELECT " + BILL_COLUMNS + """
                         FROM insurance_svc.settlement_bill
                         WHERE (CAST(:patientId AS uuid) IS NULL OR patient_id = CAST(:patientId AS uuid))
                           AND (CAST(:status AS text) IS NULL OR status = CAST(:status AS text))
                           AND (CAST(:campusCsv AS text) = ''
                                OR campus_id = ANY(string_to_array(CAST(:campusCsv AS text), ',')::uuid[]))
                         ORDER BY created_at DESC
                         LIMIT :limit
                        """)
                .param("patientId", patientId).param("status", status).param("limit", limit)
                .param("campusCsv", campusCsv == null ? "" : campusCsv)
                .query(BILL_MAPPER).list();
    }

    // ---------------------------------------------------------------- 实时结算交易（G22）

    private static final RowMapper<RealtimeSettlement> REALTIME_MAPPER =
            (rs, i) -> new RealtimeSettlement(
                    rs.getObject("id", UUID.class), rs.getObject("bill_id", UUID.class),
                    rs.getObject("visit_id", UUID.class), rs.getString("request_id"),
                    rs.getString("biz_type"), rs.getString("platform"), rs.getString("status"),
                    rs.getInt("attempts"), rs.getString("external_no"), rs.getString("last_error"),
                    rs.getString("request_payload"), rs.getString("response_payload"),
                    rs.getString("created_by"), rs.getTimestamp("created_at").toInstant(),
                    rs.getTimestamp("settled_at") == null
                            ? null : rs.getTimestamp("settled_at").toInstant(),
                    rs.getLong("version"));

    private static final String REALTIME_COLUMNS = """
            id, bill_id, visit_id, request_id, biz_type, platform, status, attempts,
            external_no, last_error, request_payload::text AS request_payload,
            response_payload::text AS response_payload, created_by, created_at, settled_at, version
            """;

    /** JOIN 清单时所有列必须带别名：status/created_at 在两张表同名，裸列会歧义。 */
    private static final String REALTIME_COLUMNS_JOINED = """
            r.id, r.bill_id, r.visit_id, r.request_id, r.biz_type, r.platform, r.status, r.attempts,
            r.external_no, r.last_error, r.request_payload::text AS request_payload,
            r.response_payload::text AS response_payload, r.created_by, r.created_at, r.settled_at,
            r.version
            """;

    /**
     * 插入交易占位；同一清单同一请求号、或同一清单已有在途/成功的 SETTLE 时不插入。
     *
     * <p>{@code ON CONFLICT DO NOTHING} 不指定目标：既要吸收
     * {@code ux_realtime_settlement (bill_id, request_id)}，也要吸收 V21 的
     * {@code ux_realtime_settle_once}（一单一次 SETTLE）；调用方据返回值回查既有交易。
     */
    public boolean insertRealtime(RealtimeSettlement r) {
        return jdbc.sql("""
                        INSERT INTO insurance_svc.realtime_settlement
                            (id, bill_id, visit_id, request_id, biz_type, platform, status,
                             request_payload, created_by)
                        VALUES (:id, :billId, :visitId, :requestId, :bizType, :platform, 'PENDING',
                                CAST(:requestPayload AS jsonb), :createdBy)
                        ON CONFLICT DO NOTHING
                        """)
                .param("id", r.id()).param("billId", r.billId())
                .param("visitId", r.visitId()).param("requestId", r.requestId())
                .param("bizType", r.bizType()).param("platform", r.platform())
                .param("requestPayload", r.requestPayload()).param("createdBy", r.createdBy())
                .update() > 0;
    }

    public Optional<RealtimeSettlement> findRealtimeById(UUID id) {
        return jdbc.sql("SELECT " + REALTIME_COLUMNS
                        + " FROM insurance_svc.realtime_settlement WHERE id = ?")
                .param(id).query(REALTIME_MAPPER).optional();
    }

    public Optional<RealtimeSettlement> findRealtimeByBillAndRequest(UUID billId, String requestId) {
        return jdbc.sql("SELECT " + REALTIME_COLUMNS
                        + " FROM insurance_svc.realtime_settlement"
                        + " WHERE bill_id = ? AND request_id = ?")
                .param(billId).param(requestId).query(REALTIME_MAPPER).optional();
    }

    /**
     * 该清单已存在的 SETTLE 交易（任意状态，按创建时间取最早）。
     *
     * <p>用于"一单一次 SETTLE"判定：同一清单即使换请求号，也不能再开一笔新的出账交易；
     * 失败交易只能走 {@code retry} 复用原交易号与尝试次数。
     */
    public Optional<RealtimeSettlement> findSettleByBill(UUID billId) {
        return jdbc.sql("SELECT " + REALTIME_COLUMNS
                        + " FROM insurance_svc.realtime_settlement"
                        + " WHERE bill_id = ? AND biz_type = 'SETTLE'"
                        + " ORDER BY created_at, id LIMIT 1")
                .param(billId).query(REALTIME_MAPPER).optional();
    }

    /**
     * 投递抢占：PENDING/FAILED → DELIVERING（CAS 版本号）。
     *
     * <p>并发投递只允许一个线程成功抢到，抢不到的按库内最新状态返回，不再外呼省局；
     * 进程崩溃遗留的 DELIVERING 超过 10 分钟可由下一次重试重新抢占。
     * {@code attempts} 在抢占时自增：即使后续进程崩溃，也算作一次尝试，避免无限重投。
     */
    public boolean claimRealtime(UUID id, long expectedVersion) {
        return jdbc.sql("""
                        UPDATE insurance_svc.realtime_settlement
                           SET status = 'DELIVERING',
                               attempts = attempts + 1,
                               version = version + 1,
                               updated_at = now()
                         WHERE id = :id AND version = :version
                           AND (status IN ('PENDING', 'FAILED')
                                OR (status = 'DELIVERING'
                                    AND updated_at < now() - interval '10 minutes'))
                        """)
                .param("id", id).param("version", expectedVersion).update() > 0;
    }

    /** 交易结果回写（CAS：版本一致且仍处于投递抢占态才更新，避免并发覆盖彼此结果）。 */
    public boolean markRealtime(UUID id, String status, int attempts, String externalNo,
                                String lastError, String responsePayload, long expectedVersion) {
        return jdbc.sql("""
                        UPDATE insurance_svc.realtime_settlement
                           SET status = :status, attempts = :attempts,
                               external_no = CAST(:externalNo AS text),
                               last_error = CAST(:lastError AS text),
                               response_payload = CAST(:responsePayload AS jsonb),
                               settled_at = CASE WHEN :status = 'SETTLED' THEN now() ELSE settled_at END,
                               version = version + 1, updated_at = now()
                         WHERE id = :id AND version = :version AND status = 'DELIVERING'
                        """)
                .param("status", status).param("attempts", attempts)
                .param("externalNo", externalNo).param("lastError", lastError)
                .param("responsePayload", responsePayload)
                .param("id", id).param("version", expectedVersion).update() > 0;
    }

    public List<RealtimeSettlement> listRealtime(UUID billId, String status, int limit) {
        return listRealtime(billId, status, limit, "");
    }

    /** 实时结算交易列表（G48 经清单院区收敛；空 CSV 表示不限院区）。 */
    public List<RealtimeSettlement> listRealtime(UUID billId, String status, int limit,
                                                 String campusCsv) {
        return jdbc.sql("SELECT " + REALTIME_COLUMNS_JOINED + """
                         FROM insurance_svc.realtime_settlement r
                         JOIN insurance_svc.settlement_bill b ON b.id = r.bill_id
                         WHERE (CAST(:billId AS uuid) IS NULL OR r.bill_id = CAST(:billId AS uuid))
                           AND (CAST(:status AS text) IS NULL OR r.status = CAST(:status AS text))
                           AND (CAST(:campusCsv AS text) = ''
                                OR b.campus_id = ANY(string_to_array(CAST(:campusCsv AS text), ',')::uuid[]))
                         ORDER BY r.created_at DESC
                         LIMIT :limit
                        """)
                .param("billId", billId).param("status", status).param("limit", limit)
                .param("campusCsv", campusCsv == null ? "" : campusCsv)
                .query(REALTIME_MAPPER).list();
    }

    // ---------------------------------------------------------------- 导出批次（N5 第二批 B）

    private static final RowMapper<ExportBatch> EXPORT_MAPPER = (rs, i) -> new ExportBatch(
            rs.getObject("id", UUID.class), rs.getString("batch_no"),
            rs.getDate("period_from").toLocalDate(), rs.getDate("period_to").toLocalDate(),
            rs.getInt("bill_count"), rs.getBigDecimal("total_amount"),
            rs.getBigDecimal("insurance_amount"), rs.getBigDecimal("self_pay_amount"),
            rs.getString("payload"), rs.getString("payload_hash"),
            rs.getString("template_code"), rs.getInt("template_version"), rs.getString("status"),
            rs.getString("receipt_no"),
            rs.getTimestamp("receipt_at") == null ? null : rs.getTimestamp("receipt_at").toInstant(),
            rs.getString("receipt_message"), rs.getString("cancelled_reason"),
            rs.getString("cancelled_by"),
            rs.getTimestamp("cancelled_at") == null ? null : rs.getTimestamp("cancelled_at").toInstant(),
            rs.getString("created_by"), rs.getLong("version"),
            rs.getTimestamp("created_at").toInstant());

    private static final String EXPORT_COLUMNS = """
            id, batch_no, period_from, period_to, bill_count, total_amount, insurance_amount,
            self_pay_amount, payload, payload_hash, template_code, template_version, status,
            receipt_no, receipt_at, receipt_message, cancelled_reason, cancelled_by,
            cancelled_at, created_by, version, created_at
            """;

    public String nextExportBatchNo() {
        Long seq = jdbc.sql("SELECT nextval(CAST(:seq AS regclass))")
                .param("seq", "insurance_svc.export_batch_no_seq").query(Long.class).single();
        return "EB" + BusinessTime.today().format(DateTimeFormatter.BASIC_ISO_DATE)
                + String.format("%05d", seq);
    }

    /**
     * 期间级串行化锁（事务级 advisory lock）。
     *
     * <p>为什么必须加：{@link #listConfirmedBills} 的 `FOR SHARE` 只锁"查到的行"，
     * 两个并发 create 事务各自的 NOT EXISTS 都在对方提交前查空，`FOR SHARE` 之间互相兼容，
     * 于是同一批 CONFIRMED 清单会同时进入两个 GENERATED 批次 → 省局重复结算（第五轮 R5-M5）。
     * 事务提交/回滚时自动释放，不需要显式解锁。
     *
     * <p>锁粒度取"期间"而不是全表：不同月份可以并发生成，只有同期间互斥。
     */
    public void lockExportPeriod(LocalDate from, LocalDate to) {
        // pg_advisory_xact_lock 返回 void，直接 query(Long.class) 会因无法映射 void 列而报错；
        // 用 CTE 包一层并返回常量 1，既拿到锁又有可映射的结果列。
        jdbc.sql("""
                        WITH acquired AS (
                            SELECT pg_advisory_xact_lock(hashtextextended(CAST(:key AS text), 0))
                        )
                        SELECT 1 FROM acquired
                        """)
                .param("key", "insurance:settlement-export:" + from + ":" + to)
                .query(Integer.class).single();
    }

    /**
     * 期间内**尚未报盘**的已确认清单，按确认时间升序。
     *
     * <p>排除已属 GENERATED / ACCEPTED 批次的清单（第四轮 MF-01：同一清单不得重复报盘）；
     * REJECTED（退回）与 CANCELLED（撤销）批次不阻止重报。
     *
     * <p>`FOR SHARE` 锁住被选中的清单行：与作废（UPDATE）互斥，
     * 避免"查完清单→写批次"之间被并发作废（第四轮 SF-07）。
     * 注意它**不能**防两个 create 之间的竞争，那由 {@link #lockExportPeriod} 负责。
     */
    public List<SettlementBill> listConfirmedBills(LocalDate from, LocalDate to, int limit) {
        return listConfirmedBills(from, to, "", limit);
    }

    /**
     * 待报盘的已确认清单（G53）：{@code campusCsv} 为空串表示不限院区。
     *
     * <p>报盘批次会连同患者号与逐条费用一起下发省局，必须按调用方可及院区收敛，
     * 否则单院区医保办可以生成含其他院区患者明细的整批报文。
     */
    public List<SettlementBill> listConfirmedBills(LocalDate from, LocalDate to, String campusCsv,
                                                   int limit) {
        return jdbc.sql("SELECT " + BILL_COLUMNS + """
                         FROM insurance_svc.settlement_bill b
                         WHERE b.status = 'CONFIRMED'
                           AND b.confirmed_at >= :fromTs AND b.confirmed_at < :toTs
                           AND (CAST(:campusCsv AS text) = ''
                                OR b.campus_id = ANY (string_to_array(CAST(:campusCsv AS text), ',')::uuid[]))
                           AND NOT EXISTS (
                               SELECT 1
                                 FROM insurance_svc.settlement_export_item i
                                 JOIN insurance_svc.settlement_export_batch x ON x.id = i.batch_id
                                WHERE i.bill_id = b.id
                                  AND x.status IN ('GENERATED', 'ACCEPTED'))
                         ORDER BY b.confirmed_at, b.bill_no
                         LIMIT :limit
                        FOR SHARE OF b
                        """)
                .param("fromTs", from.atStartOfDay(BusinessTime.zone()).toOffsetDateTime())
                .param("toTs", to.plusDays(1).atStartOfDay(BusinessTime.zone()).toOffsetDateTime())
                .param("campusCsv", campusCsv == null ? "" : campusCsv)
                .param("limit", limit)
                .query(BILL_MAPPER).list();
    }

    /**
     * 批次内清单涉及的院区（G53）：下载报文前校验"这批数据是否全在当前账号范围内"。
     */
    public List<UUID> listBatchCampusIds(UUID batchId) {
        return jdbc.sql("""
                        SELECT DISTINCT b.campus_id
                          FROM insurance_svc.settlement_export_item i
                          JOIN insurance_svc.settlement_bill b ON b.id = i.bill_id
                         WHERE i.batch_id = ? AND b.campus_id IS NOT NULL
                        """).param(batchId).query(UUID.class).list();
    }

    /** 期间内已确认但**已报盘**的清单数量（用于生成前回显"被跳过多少张"）。 */
    public long countConfirmedBillsAlreadyExported(LocalDate from, LocalDate to) {
        return countConfirmedBillsAlreadyExported(from, to, "");
    }

    /** G53：按院区收敛的"已报盘"计数（参数口径同 {@link #listConfirmedBills}）。 */
    public long countConfirmedBillsAlreadyExported(LocalDate from, LocalDate to, String campusCsv) {
        return jdbc.sql("""
                        SELECT count(*)
                          FROM insurance_svc.settlement_bill b
                         WHERE b.status = 'CONFIRMED'
                           AND b.confirmed_at >= :fromTs AND b.confirmed_at < :toTs
                           AND (CAST(:campusCsv AS text) = ''
                                OR b.campus_id = ANY (string_to_array(CAST(:campusCsv AS text), ',')::uuid[]))
                           AND EXISTS (
                               SELECT 1
                                 FROM insurance_svc.settlement_export_item i
                                 JOIN insurance_svc.settlement_export_batch x ON x.id = i.batch_id
                                WHERE i.bill_id = b.id
                                  AND x.status IN ('GENERATED', 'ACCEPTED'))
                        """)
                .param("fromTs", from.atStartOfDay(BusinessTime.zone()).toOffsetDateTime())
                .param("toTs", to.plusDays(1).atStartOfDay(BusinessTime.zone()).toOffsetDateTime())
                .param("campusCsv", campusCsv == null ? "" : campusCsv)
                .query(Long.class).single();
    }

    /** 期间内**待报盘**（已确认且未属有效批次）的清单数量；用于分批导出时回显剩余张数。 */
    public long countConfirmedBillsPending(LocalDate from, LocalDate to) {
        return countConfirmedBillsPending(from, to, "");
    }

    /** G53：按院区收敛的"待报盘"计数（参数口径同 {@link #listConfirmedBills}）。 */
    public long countConfirmedBillsPending(LocalDate from, LocalDate to, String campusCsv) {
        return jdbc.sql("""
                        SELECT count(*)
                          FROM insurance_svc.settlement_bill b
                         WHERE b.status = 'CONFIRMED'
                           AND b.confirmed_at >= :fromTs AND b.confirmed_at < :toTs
                           AND (CAST(:campusCsv AS text) = ''
                                OR b.campus_id = ANY (string_to_array(CAST(:campusCsv AS text), ',')::uuid[]))
                           AND NOT EXISTS (
                               SELECT 1
                                 FROM insurance_svc.settlement_export_item i
                                 JOIN insurance_svc.settlement_export_batch x ON x.id = i.batch_id
                                WHERE i.bill_id = b.id
                                  AND x.status IN ('GENERATED', 'ACCEPTED'))
                        """)
                .param("fromTs", from.atStartOfDay(BusinessTime.zone()).toOffsetDateTime())
                .param("toTs", to.plusDays(1).atStartOfDay(BusinessTime.zone()).toOffsetDateTime())
                .param("campusCsv", campusCsv == null ? "" : campusCsv)
                .query(Long.class).single();
    }

    /** 某张清单当前所在的有效批次（GENERATED / ACCEPTED），用于作废前的报盘校验。 */
    public Optional<ExportBatch> findActiveBatchByBill(UUID billId) {
        return jdbc.sql("SELECT " + EXPORT_COLUMNS + """
                         FROM insurance_svc.settlement_export_batch
                         WHERE status IN ('GENERATED', 'ACCEPTED')
                           AND id IN (SELECT batch_id
                                        FROM insurance_svc.settlement_export_item
                                       WHERE bill_id = ?)
                         ORDER BY created_at DESC
                         LIMIT 1
                        """).param(billId).query(EXPORT_MAPPER).optional();
    }

    public void insertExportBatch(ExportBatch b) {
        jdbc.sql("""
                        INSERT INTO insurance_svc.settlement_export_batch
                            (id, batch_no, period_from, period_to, bill_count, total_amount,
                             insurance_amount, self_pay_amount, payload, payload_hash,
                             template_code, template_version, status, created_by, version)
                        VALUES (:id, :no, :from, :to, :count, :total, :insurance, :selfPay,
                                :payload, :hash, :templateCode, :templateVersion, 'GENERATED',
                                :actor, 0)
                        """)
                .param("id", b.id()).param("no", b.batchNo())
                .param("from", b.periodFrom()).param("to", b.periodTo())
                .param("count", b.billCount()).param("total", b.totalAmount())
                .param("insurance", b.insuranceAmount()).param("selfPay", b.selfPayAmount())
                .param("payload", b.payload()).param("hash", b.payloadHash())
                .param("templateCode", b.templateCode())
                .param("templateVersion", b.templateVersion())
                .param("actor", b.createdBy()).update();
    }

    public void insertExportItem(UUID batchId, UUID billId, String billNo) {
        jdbc.sql("""
                        INSERT INTO insurance_svc.settlement_export_item (batch_id, bill_id, bill_no)
                        VALUES (?, ?, ?)
                        ON CONFLICT DO NOTHING
                        """).param(batchId).param(billId).param(billNo).update();
    }

    public Optional<ExportBatch> findExportBatch(UUID id) {
        return jdbc.sql("SELECT " + EXPORT_COLUMNS + """
                         FROM insurance_svc.settlement_export_batch WHERE id = ?
                        """).param(id).query(EXPORT_MAPPER).optional();
    }

    public List<ExportBatch> listExportBatches(int limit) {
        return jdbc.sql("SELECT " + EXPORT_COLUMNS + """
                         FROM insurance_svc.settlement_export_batch
                         ORDER BY created_at DESC
                         LIMIT :limit
                        """).param("limit", limit).query(EXPORT_MAPPER).list();
    }

    /** 登记回执：仅 GENERATED 可写，CAS 防止两个窗口重复登记。 */
    public boolean updateExportReceipt(UUID id, String status, String receiptNo, String message,
                                       long expectedVersion) {
        return jdbc.sql("""
                        UPDATE insurance_svc.settlement_export_batch
                           SET status = :status, receipt_no = :receiptNo, receipt_message = :message,
                               receipt_at = now(), version = version + 1
                         WHERE id = :id AND status = 'GENERATED' AND version = :version
                        """)
                .param("status", status).param("receiptNo", receiptNo).param("message", message)
                .param("id", id).param("version", expectedVersion).update() > 0;
    }

    /** 撤销批次：仅 GENERATED 可撤销（已回执的必须走省局冲销），CAS。 */
    public boolean cancelExportBatch(UUID id, String reason, String actor, long expectedVersion) {
        return jdbc.sql("""
                        UPDATE insurance_svc.settlement_export_batch
                           SET status = 'CANCELLED', cancelled_reason = :reason,
                               cancelled_by = :actor, cancelled_at = now(),
                               version = version + 1
                         WHERE id = :id AND status = 'GENERATED' AND version = :version
                        """)
                .param("reason", reason).param("actor", actor)
                .param("id", id).param("version", expectedVersion).update() > 0;
    }

    // ---------------------------------------------------------------- 报文模板（G85）

    private static final RowMapper<ExportMessageTemplate> TEMPLATE_MAPPER =
            (rs, i) -> new ExportMessageTemplate(
                    rs.getObject("id", UUID.class), rs.getString("code"), rs.getString("name"),
                    rs.getInt("version"), rs.getString("status"), rs.getString("fields"),
                    rs.getString("created_by"), rs.getString("updated_by"),
                    rs.getString("published_by"),
                    rs.getTimestamp("published_at") == null
                            ? null : rs.getTimestamp("published_at").toInstant(),
                    rs.getLong("row_version"), rs.getTimestamp("created_at").toInstant(),
                    rs.getTimestamp("updated_at").toInstant());

    private static final String TEMPLATE_COLUMNS = """
            id, code, name, version, status, fields::text AS fields,
            created_by, updated_by, published_by, published_at,
            row_version, created_at, updated_at
            """;

    /** 当前生效模板（数据库部分唯一索引保证至多一条）。 */
    public Optional<ExportMessageTemplate> findPublishedExportTemplate() {
        return jdbc.sql("SELECT " + TEMPLATE_COLUMNS + """
                        FROM insurance_svc.export_message_template
                        WHERE status = 'PUBLISHED'
                        """).query(TEMPLATE_MAPPER).optional();
    }

    public Optional<ExportMessageTemplate> findExportTemplate(UUID id) {
        return jdbc.sql("SELECT " + TEMPLATE_COLUMNS + """
                        FROM insurance_svc.export_message_template WHERE id = ?
                        """).param(id).query(TEMPLATE_MAPPER).optional();
    }

    public Optional<ExportMessageTemplate> findExportTemplateByCode(String code) {
        return jdbc.sql("SELECT " + TEMPLATE_COLUMNS + """
                        FROM insurance_svc.export_message_template WHERE code = ?
                        """).param(code).query(TEMPLATE_MAPPER).optional();
    }

    public List<ExportMessageTemplate> listExportTemplates(int limit) {
        return jdbc.sql("SELECT " + TEMPLATE_COLUMNS + """
                        FROM insurance_svc.export_message_template
                        ORDER BY updated_at DESC
                        LIMIT :limit
                        """).param("limit", limit).query(TEMPLATE_MAPPER).list();
    }

    public void insertExportTemplate(ExportMessageTemplate t) {
        jdbc.sql("""
                        INSERT INTO insurance_svc.export_message_template
                            (id, code, name, version, status, fields,
                             created_by, updated_by, row_version)
                        VALUES (:id, :code, :name, :version, :status, CAST(:fields AS jsonb),
                                :actor, :actor, 0)
                        """)
                .param("id", t.id()).param("code", t.code()).param("name", t.name())
                .param("version", t.version()).param("status", t.status())
                .param("fields", t.fields()).param("actor", t.createdBy()).update();
    }

    /** 修改草稿（仅 DRAFT），CAS。 */
    public boolean updateExportTemplate(UUID id, String name, String fieldsJson, String actor,
                                        long expectedRowVersion) {
        return jdbc.sql("""
                        UPDATE insurance_svc.export_message_template
                           SET name = :name, fields = CAST(:fields AS jsonb),
                               updated_by = :actor, updated_at = now(),
                               row_version = row_version + 1
                         WHERE id = :id AND status = 'DRAFT' AND row_version = :rowVersion
                        """)
                .param("name", name).param("fields", fieldsJson).param("actor", actor)
                .param("id", id).param("rowVersion", expectedRowVersion).update() > 0;
    }

    /**
     * 发布草稿：version+1（报文口径版本），CAS。
     *
     * <p>并发发布由 `ux_export_template_single_published` 唯一索引兜底，
     * 服务层捕获唯一冲突并转 409（不能让两个模板同时"生效"）。
     */
    public boolean publishExportTemplate(UUID id, String actor, long expectedRowVersion) {
        return jdbc.sql("""
                        UPDATE insurance_svc.export_message_template
                           SET status = 'PUBLISHED', version = version + 1,
                               published_by = :actor, published_at = now(),
                               updated_by = :actor, updated_at = now(),
                               row_version = row_version + 1
                         WHERE id = :id AND status = 'DRAFT' AND row_version = :rowVersion
                        """)
                .param("actor", actor).param("id", id)
                .param("rowVersion", expectedRowVersion).update() > 0;
    }

    /** 停用生效模板（PUBLISHED → DISABLED），CAS；停用后导出回落内置布局。 */
    public boolean disableExportTemplate(UUID id, String actor, long expectedRowVersion) {
        return jdbc.sql("""
                        UPDATE insurance_svc.export_message_template
                           SET status = 'DISABLED', updated_by = :actor, updated_at = now(),
                               row_version = row_version + 1
                         WHERE id = :id AND status = 'PUBLISHED' AND row_version = :rowVersion
                        """)
                .param("actor", actor).param("id", id)
                .param("rowVersion", expectedRowVersion).update() > 0;
    }

    // ---------------------------------------------------------------- DRG/DIP 分组（N5 第二批 C）

    private static final RowMapper<PaymentScheme> SCHEME_MAPPER = (rs, i) -> new PaymentScheme(
            rs.getString("code"), rs.getString("name"), rs.getString("type"),
            rs.getBigDecimal("rate_amount"), rs.getDate("rate_effective_from").toLocalDate(),
            rs.getBoolean("enabled"), rs.getLong("version"), rs.getString("updated_by"),
            rs.getTimestamp("updated_at").toInstant());

    private static final RowMapper<PaymentGroup> GROUP_MAPPER = (rs, i) -> new PaymentGroup(
            rs.getString("scheme_code"), rs.getString("group_code"), rs.getString("group_name"),
            rs.getBigDecimal("weight"), rs.getBoolean("enabled"), rs.getLong("version"),
            rs.getTimestamp("updated_at").toInstant());

    private static final RowMapper<PaymentGroupRule> RULE_MAPPER = (rs, i) -> new PaymentGroupRule(
            rs.getObject("id", UUID.class), rs.getString("scheme_code"), rs.getInt("priority"),
            rs.getString("match_type"), rs.getString("diag_prefix"), rs.getString("proc_prefix"),
            rs.getString("group_code"), rs.getBoolean("enabled"),
            rs.getTimestamp("created_at").toInstant());

    private static final RowMapper<GroupSimulation> SIMULATION_MAPPER = (rs, i) -> new GroupSimulation(
            rs.getObject("id", UUID.class), rs.getObject("visit_id", UUID.class),
            rs.getString("scheme_code"), rs.getString("group_code"), rs.getBoolean("matched"),
            rs.getString("reason"), rs.getBigDecimal("weight"), rs.getBigDecimal("rate_amount"),
            rs.getBigDecimal("payment_standard"), rs.getString("diagnoses"),
            rs.getString("procedures"), rs.getString("diagnoses_source"),
            rs.getString("procedures_source"), rs.getString("input_hash"),
            rs.getString("created_by"), rs.getTimestamp("created_at").toInstant());

    public List<PaymentScheme> listSchemes() {
        return jdbc.sql("""
                        SELECT code, name, type, rate_amount, rate_effective_from, enabled,
                               version, updated_by, updated_at
                          FROM insurance_svc.payment_scheme ORDER BY code
                        """).query(SCHEME_MAPPER).list();
    }

    public Optional<PaymentScheme> findScheme(String code) {
        return jdbc.sql("""
                        SELECT code, name, type, rate_amount, rate_effective_from, enabled,
                               version, updated_by, updated_at
                          FROM insurance_svc.payment_scheme WHERE code = ?
                        """).param(code).query(SCHEME_MAPPER).optional();
    }

    public void insertScheme(PaymentScheme s) {
        jdbc.sql("""
                        INSERT INTO insurance_svc.payment_scheme
                            (code, name, type, rate_amount, rate_effective_from, enabled,
                             version, updated_by, updated_at)
                        VALUES (:code, :name, :type, :rate, :eff, :enabled, 0, :actor, now())
                        """)
                .param("code", s.code()).param("name", s.name()).param("type", s.type())
                .param("rate", s.rateAmount()).param("eff", s.rateEffectiveFrom())
                .param("enabled", s.enabled()).param("actor", s.updatedBy()).update();
    }

    /** 修改方案（CAS；type 不可改由服务层保证）。 */
    public boolean updateScheme(String code, String name, BigDecimal rateAmount,
                                LocalDate rateEffectiveFrom, boolean enabled, long expectedVersion,
                                String actor) {
        return jdbc.sql("""
                        UPDATE insurance_svc.payment_scheme
                           SET name = :name, rate_amount = :rate, rate_effective_from = :eff,
                               enabled = :enabled, version = version + 1, updated_by = :actor,
                               updated_at = now()
                         WHERE code = :code AND version = :version
                        """)
                .param("name", name).param("rate", rateAmount).param("eff", rateEffectiveFrom)
                .param("enabled", enabled).param("code", code).param("version", expectedVersion)
                .param("actor", actor).update() > 0;
    }

    // ---------------------------------------------------------------- 费率生效期（G84）

    private static final RowMapper<SchemeRate> SCHEME_RATE_MAPPER = (rs, i) -> new SchemeRate(
            rs.getString("scheme_code"), rs.getDate("effective_from").toLocalDate(),
            rs.getBigDecimal("rate_amount"), rs.getString("created_by"),
            rs.getTimestamp("created_at").toInstant());

    public List<SchemeRate> listSchemeRates(String schemeCode) {
        return jdbc.sql("""
                        SELECT scheme_code, effective_from, rate_amount, created_by, created_at
                          FROM insurance_svc.payment_scheme_rate
                         WHERE scheme_code = ? ORDER BY effective_from DESC
                        """).param(schemeCode).query(SCHEME_RATE_MAPPER).list();
    }

    /**
     * 取某日期适用的费率：{@code effective_from <= date} 的最近一条。
     *
     * <p>没有记录时返回空——调用方必须显式报"该日期没有生效费率"，
     * 不能回落到"当前费率"（那正是重新引入"历史被覆盖"的问题）。
     */
    public Optional<SchemeRate> findRateEffectiveOn(String schemeCode, LocalDate date) {
        return jdbc.sql("""
                        SELECT scheme_code, effective_from, rate_amount, created_by, created_at
                          FROM insurance_svc.payment_scheme_rate
                         WHERE scheme_code = :code AND effective_from <= :date
                         ORDER BY effective_from DESC LIMIT 1
                        """)
                .param("code", schemeCode).param("date", date)
                .query(SCHEME_RATE_MAPPER).optional();
    }

    /** 同一 (方案, 生效日) 只有一档费率；重复新增由唯一键拒绝。 */
    public void insertSchemeRate(SchemeRate r) {
        jdbc.sql("""
                        INSERT INTO insurance_svc.payment_scheme_rate
                            (scheme_code, effective_from, rate_amount, created_by)
                        VALUES (:code, :eff, :rate, :actor)
                        """)
                .param("code", r.schemeCode()).param("eff", r.effectiveFrom())
                .param("rate", r.rateAmount()).param("actor", r.createdBy()).update();
    }

    /** 修改方案时同步该生效日的费率：同一日期即更正，不产生第二档。 */
    public void upsertSchemeRate(String schemeCode, LocalDate effectiveFrom,
                                 BigDecimal rateAmount, String actor) {
        jdbc.sql("""
                        INSERT INTO insurance_svc.payment_scheme_rate
                            (scheme_code, effective_from, rate_amount, created_by)
                        VALUES (:code, :eff, :rate, :actor)
                        ON CONFLICT (scheme_code, effective_from)
                        DO UPDATE SET rate_amount = EXCLUDED.rate_amount,
                                      created_by = EXCLUDED.created_by,
                                      created_at = now()
                        """)
                .param("code", schemeCode).param("eff", effectiveFrom)
                .param("rate", rateAmount).param("actor", actor).update();
    }

    /** 方案是否已存在某生效日的费率（新增接口用于给出 409，而不是撞唯一键）。 */
    public boolean existsSchemeRate(String schemeCode, LocalDate effectiveFrom) {
        Boolean exists = jdbc.sql("""
                        SELECT EXISTS (SELECT 1 FROM insurance_svc.payment_scheme_rate
                                        WHERE scheme_code = :code AND effective_from = :eff)
                        """)
                .param("code", schemeCode).param("eff", effectiveFrom)
                .query(Boolean.class).single();
        return Boolean.TRUE.equals(exists);
    }

    /**
     * 同步方案上的"当前费率"展示值（权威值在 payment_scheme_rate）。
     *
     * <p>任何费率/生效日变化都升版本：并发保存的旧版本应当 409 并刷新，
     * 而不是用旧口径静默覆盖。
     */
    public void syncSchemeCurrentRate(String schemeCode, BigDecimal rateAmount,
                                      LocalDate effectiveFrom, String actor) {
        jdbc.sql("""
                        UPDATE insurance_svc.payment_scheme
                           SET rate_amount = :rate, rate_effective_from = :eff,
                               updated_by = :actor, version = version + 1, updated_at = now()
                         WHERE code = :code
                        """)
                .param("rate", rateAmount).param("eff", effectiveFrom)
                .param("actor", actor).param("code", schemeCode).update();
    }

    public List<PaymentGroup> listGroups(String schemeCode, Boolean enabled) {
        return jdbc.sql("""
                        SELECT scheme_code, group_code, group_name, weight, enabled, version, updated_at
                          FROM insurance_svc.payment_group
                         WHERE scheme_code = :scheme
                           AND (CAST(:enabled AS boolean) IS NULL OR enabled = CAST(:enabled AS boolean))
                         ORDER BY group_code
                        """)
                .param("scheme", schemeCode).param("enabled", enabled)
                .query(GROUP_MAPPER).list();
    }

    public Optional<PaymentGroup> findGroup(String schemeCode, String groupCode) {
        return jdbc.sql("""
                        SELECT scheme_code, group_code, group_name, weight, enabled, version, updated_at
                          FROM insurance_svc.payment_group
                         WHERE scheme_code = ? AND group_code = ?
                        """).param(schemeCode).param(groupCode).query(GROUP_MAPPER).optional();
    }

    /** 导入分组：以文件为准的强制覆盖（变更留痕由服务层逐条写审计）。 */
    public void upsertGroup(PaymentGroup g) {
        jdbc.sql("""
                        INSERT INTO insurance_svc.payment_group
                            (scheme_code, group_code, group_name, weight, enabled, version)
                        VALUES (:scheme, :code, :name, :weight, :enabled, 0)
                        ON CONFLICT (scheme_code, group_code) DO UPDATE
                           SET group_name = EXCLUDED.group_name,
                               weight = EXCLUDED.weight,
                               enabled = EXCLUDED.enabled,
                               version = insurance_svc.payment_group.version + 1,
                               updated_at = now()
                        """)
                .param("scheme", g.schemeCode()).param("code", g.groupCode())
                .param("name", g.groupName()).param("weight", g.weight())
                .param("enabled", g.enabled()).update();
    }

    public List<PaymentGroupRule> listRules(String schemeCode, Boolean enabled) {
        return jdbc.sql("""
                        SELECT id, scheme_code, priority, match_type, diag_prefix, proc_prefix,
                               group_code, enabled, created_at
                          FROM insurance_svc.payment_group_rule
                         WHERE scheme_code = :scheme
                           AND (CAST(:enabled AS boolean) IS NULL OR enabled = CAST(:enabled AS boolean))
                         ORDER BY priority
                        """)
                .param("scheme", schemeCode).param("enabled", enabled)
                .query(RULE_MAPPER).list();
    }

    public Optional<PaymentGroupRule> findRule(String schemeCode, int priority) {
        return jdbc.sql("""
                        SELECT id, scheme_code, priority, match_type, diag_prefix, proc_prefix,
                               group_code, enabled, created_at
                          FROM insurance_svc.payment_group_rule
                         WHERE scheme_code = ? AND priority = ?
                        """).param(schemeCode).param(priority).query(RULE_MAPPER).optional();
    }

    /** 按 (方案, 优先级) UPSERT：优先级就是规则身份，导入即覆盖。 */
    public void upsertRule(PaymentGroupRule r) {
        jdbc.sql("""
                        INSERT INTO insurance_svc.payment_group_rule
                            (id, scheme_code, priority, match_type, diag_prefix, proc_prefix,
                             group_code, enabled)
                        VALUES (:id, :scheme, :priority, :matchType, :diag, :proc, :group, :enabled)
                        ON CONFLICT (scheme_code, priority) DO UPDATE
                           SET match_type = EXCLUDED.match_type,
                               diag_prefix = EXCLUDED.diag_prefix,
                               proc_prefix = EXCLUDED.proc_prefix,
                               group_code = EXCLUDED.group_code,
                               enabled = EXCLUDED.enabled
                        """)
                .param("id", r.id()).param("scheme", r.schemeCode())
                .param("priority", r.priority()).param("matchType", r.matchType())
                .param("diag", r.diagPrefix()).param("proc", r.procPrefix())
                .param("group", r.groupCode()).param("enabled", r.enabled()).update();
    }

    public void insertSimulation(GroupSimulation s) {
        jdbc.sql("""
                        INSERT INTO insurance_svc.payment_group_simulation
                            (id, visit_id, scheme_code, group_code, matched, reason, weight,
                             rate_amount, payment_standard, diagnoses, procedures,
                             diagnoses_source, procedures_source, input_hash, created_by)
                        VALUES (:id, :visitId, :scheme, :group, :matched, :reason,
                                CAST(:weight AS numeric), :rate, CAST(:payment AS numeric),
                                CAST(:diagnoses AS jsonb), CAST(:procedures AS jsonb),
                                :diagnosesSource, :proceduresSource, :hash, :actor)
                        """)
                .param("id", s.id()).param("visitId", s.visitId()).param("scheme", s.schemeCode())
                .param("group", s.groupCode()).param("matched", s.matched())
                .param("reason", s.reason()).param("weight", s.weight())
                .param("rate", s.rateAmount()).param("payment", s.paymentStandard())
                .param("diagnoses", s.diagnoses()).param("procedures", s.procedures())
                .param("diagnosesSource", s.diagnosesSource())
                .param("proceduresSource", s.proceduresSource())
                .param("hash", s.inputHash()).param("actor", s.createdBy()).update();
    }

    public Optional<GroupSimulation> findLatestSimulation(UUID visitId) {
        return jdbc.sql("""
                        SELECT id, visit_id, scheme_code, group_code, matched, reason, weight,
                               rate_amount, payment_standard, diagnoses::text AS diagnoses,
                               procedures::text AS procedures, diagnoses_source, procedures_source,
                               input_hash, created_by, created_at
                          FROM insurance_svc.payment_group_simulation
                         WHERE visit_id = ?
                         ORDER BY created_at DESC
                         LIMIT 1
                        """).param(visitId).query(SIMULATION_MAPPER).optional();
    }

    public List<GroupSimulation> listSimulations(UUID visitId, int limit) {
        return jdbc.sql("""
                        SELECT id, visit_id, scheme_code, group_code, matched, reason, weight,
                               rate_amount, payment_standard, diagnoses::text AS diagnoses,
                               procedures::text AS procedures, diagnoses_source, procedures_source,
                               input_hash, created_by, created_at
                          FROM insurance_svc.payment_group_simulation
                         WHERE visit_id = :visitId
                         ORDER BY created_at DESC
                         LIMIT :limit
                        """).param("visitId", visitId).param("limit", limit)
                .query(SIMULATION_MAPPER).list();
    }

    // ---------------------------------------------------------------- 分组引擎（§263）

    private static final RowMapper<GrouperRuleSet> RULE_SET_MAPPER = (rs, i) ->
            new GrouperRuleSet(rs.getObject("id", UUID.class), rs.getString("code"),
                    rs.getString("version"), rs.getString("name"), rs.getString("type"),
                    rs.getDate("effective_from").toLocalDate(), rs.getString("source"),
                    rs.getString("checksum"), rs.getString("status"),
                    rs.getString("imported_by"), rs.getTimestamp("imported_at").toInstant(),
                    rs.getString("activated_by"),
                    rs.getTimestamp("activated_at") == null ? null
                            : rs.getTimestamp("activated_at").toInstant());

    private static final RowMapper<GrouperMdcRule> MDC_MAPPER = (rs, i) -> new GrouperMdcRule(
            rs.getObject("id", UUID.class), rs.getObject("rule_set_id", UUID.class),
            rs.getInt("priority"), rs.getString("mdc_code"), rs.getString("mdc_name"),
            rs.getString("diag_from"), rs.getString("diag_to"), rs.getBoolean("enabled"));

    private static final RowMapper<GrouperAdrgRule> ADRG_MAPPER = (rs, i) -> new GrouperAdrgRule(
            rs.getObject("id", UUID.class), rs.getObject("rule_set_id", UUID.class),
            rs.getInt("priority"), rs.getString("adrg_code"), rs.getString("adrg_name"),
            rs.getString("mdc_code"), rs.getString("match_type"), rs.getString("diag_prefix"),
            rs.getString("proc_prefix"), rs.getBoolean("enabled"));

    private static final RowMapper<GrouperDrgDef> DRG_DEF_MAPPER = (rs, i) -> new GrouperDrgDef(
            rs.getObject("rule_set_id", UUID.class), rs.getString("drg_code"),
            rs.getString("drg_name"), rs.getString("adrg_code"), rs.getString("severity"),
            rs.getBigDecimal("weight"));

    private static final RowMapper<GrouperCcRule> CC_RULE_MAPPER = (rs, i) -> new GrouperCcRule(
            rs.getObject("id", UUID.class), rs.getObject("rule_set_id", UUID.class),
            rs.getString("severity"), rs.getString("diag_prefix"));

    private static final RowMapper<GrouperCcExclusion> CC_EXCLUSION_MAPPER = (rs, i) ->
            new GrouperCcExclusion(rs.getObject("id", UUID.class),
                    rs.getObject("rule_set_id", UUID.class),
                    rs.getString("primary_diag_prefix"), rs.getString("secondary_diag_prefix"));

    private static final RowMapper<GrouperDipCatalog> DIP_MAPPER = (rs, i) ->
            new GrouperDipCatalog(rs.getObject("id", UUID.class),
                    rs.getObject("rule_set_id", UUID.class), rs.getString("dip_code"),
                    rs.getString("dip_name"), rs.getString("diag_code"), rs.getString("proc_code"),
                    rs.getString("type"), rs.getBigDecimal("score"));

    private static final RowMapper<GrouperResult> GROUPER_RESULT_MAPPER = (rs, i) ->
            new GrouperResult(rs.getObject("id", UUID.class), rs.getObject("visit_id", UUID.class),
                    rs.getObject("rule_set_id", UUID.class), rs.getString("rule_set_code"),
                    rs.getString("rule_set_version"), rs.getString("engine"),
                    rs.getString("type"), rs.getString("mdc_code"), rs.getString("adrg_code"),
                    rs.getString("drg_code"), rs.getString("dip_code"), rs.getString("severity"),
                    rs.getBigDecimal("weight"), rs.getBigDecimal("score"),
                    rs.getBigDecimal("payment_standard"), rs.getBoolean("matched"),
                    rs.getString("reason"), orEmpty(rs.getString("trace")),
                    rs.getString("principal_diagnosis"),
                    orEmpty(rs.getString("secondary_diagnoses")),
                    orEmpty(rs.getString("procedures")),
                    rs.getString("input_hash"), rs.getString("created_by"),
                    rs.getTimestamp("created_at").toInstant());

    public Optional<GrouperRuleSet> findRuleSet(UUID id) {
        return jdbc.sql("""
                        SELECT id, code, version, name, type, effective_from, source, checksum,
                               status, imported_by, imported_at, activated_by, activated_at
                          FROM insurance_svc.grouper_rule_set WHERE id = ?
                        """).param(id).query(RULE_SET_MAPPER).optional();
    }

    public Optional<GrouperRuleSet> findRuleSet(String code, String version) {
        return jdbc.sql("""
                        SELECT id, code, version, name, type, effective_from, source, checksum,
                               status, imported_by, imported_at, activated_by, activated_at
                          FROM insurance_svc.grouper_rule_set
                         WHERE code = :code AND version = :version
                        """).param("code", code).param("version", version)
                .query(RULE_SET_MAPPER).optional();
    }

    /**
     * 取生效规则包：可按 code 精确取，也可按 type 取最近生效的一版。
     */
    public Optional<GrouperRuleSet> findActiveRuleSet(String type, String code) {
        return jdbc.sql("""
                        SELECT id, code, version, name, type, effective_from, source, checksum,
                               status, imported_by, imported_at, activated_by, activated_at
                          FROM insurance_svc.grouper_rule_set
                         WHERE status = 'ACTIVE' AND type = :type
                           AND (CAST(:code AS text) IS NULL OR code = :code)
                         ORDER BY effective_from DESC, activated_at DESC
                         LIMIT 1
                        """).param("type", type).param("code", code)
                .query(RULE_SET_MAPPER).optional();
    }

    /** 按 code 取生效版本（不看 type）：避免先列表再筛选被 LIMIT 截断。 */
    public Optional<GrouperRuleSet> findActiveByCode(String code) {
        return jdbc.sql("""
                        SELECT id, code, version, name, type, effective_from, source, checksum,
                               status, imported_by, imported_at, activated_by, activated_at
                          FROM insurance_svc.grouper_rule_set
                         WHERE status = 'ACTIVE' AND code = :code
                         ORDER BY effective_from DESC, activated_at DESC
                         LIMIT 1
                        """).param("code", code).query(RULE_SET_MAPPER).optional();
    }

    public List<GrouperRuleSet> listRuleSets(String type) {
        return jdbc.sql("""
                        SELECT id, code, version, name, type, effective_from, source, checksum,
                               status, imported_by, imported_at, activated_by, activated_at
                          FROM insurance_svc.grouper_rule_set
                         WHERE (CAST(:type AS text) IS NULL OR type = :type)
                         ORDER BY effective_from DESC, imported_at DESC
                         LIMIT 100
                        """).param("type", type).query(RULE_SET_MAPPER).list();
    }

    public void insertRuleSet(GrouperRuleSet set) {
        jdbc.sql("""
                        INSERT INTO insurance_svc.grouper_rule_set
                            (id, code, version, name, type, effective_from, source, checksum,
                             status, imported_by)
                        VALUES (:id, :code, :version, :name, :type, :effectiveFrom, :source,
                                :checksum, 'DRAFT', :importedBy)
                        """)
                .param("id", set.id()).param("code", set.code()).param("version", set.version())
                .param("name", set.name()).param("type", set.type())
                .param("effectiveFrom", set.effectiveFrom()).param("source", set.source())
                .param("checksum", set.checksum()).param("importedBy", set.importedBy()).update();
    }

    /** 停用同 code 的 ACTIVE 版本（激活新版本前调用）。 */
    public int retireActiveRuleSets(String code) {
        return jdbc.sql("""
                        UPDATE insurance_svc.grouper_rule_set
                           SET status = 'RETIRED'
                         WHERE code = :code AND status = 'ACTIVE'
                        """).param("code", code).update();
    }

    public boolean activateRuleSet(UUID id, String actor) {
        return jdbc.sql("""
                        UPDATE insurance_svc.grouper_rule_set
                           SET status = 'ACTIVE', activated_by = :actor, activated_at = now()
                         WHERE id = :id AND status = 'DRAFT'
                        """).param("actor", actor).param("id", id).update() == 1;
    }

    public void insertMdcRules(List<GrouperMdcRule> rules) {
        for (GrouperMdcRule rule : rules) {
            jdbc.sql("""
                            INSERT INTO insurance_svc.grouper_mdc_rule
                                (id, rule_set_id, priority, mdc_code, mdc_name, diag_from,
                                 diag_to, enabled)
                            VALUES (:id, :setId, :priority, :mdcCode, :mdcName, :from, :to,
                                    :enabled)
                            """)
                    .param("id", rule.id()).param("setId", rule.ruleSetId())
                    .param("priority", rule.priority()).param("mdcCode", rule.mdcCode())
                    .param("mdcName", rule.mdcName()).param("from", rule.diagFrom())
                    .param("to", rule.diagTo()).param("enabled", rule.enabled()).update();
        }
    }

    public void insertAdrgRules(List<GrouperAdrgRule> rules) {
        for (GrouperAdrgRule rule : rules) {
            jdbc.sql("""
                            INSERT INTO insurance_svc.grouper_adrg_rule
                                (id, rule_set_id, priority, adrg_code, adrg_name, mdc_code,
                                 match_type, diag_prefix, proc_prefix, enabled)
                            VALUES (:id, :setId, :priority, :adrgCode, :adrgName, :mdcCode,
                                    :matchType, :diag, :proc, :enabled)
                            """)
                    .param("id", rule.id()).param("setId", rule.ruleSetId())
                    .param("priority", rule.priority()).param("adrgCode", rule.adrgCode())
                    .param("adrgName", rule.adrgName()).param("mdcCode", rule.mdcCode())
                    .param("matchType", rule.matchType()).param("diag", rule.diagPrefix())
                    .param("proc", rule.procPrefix()).param("enabled", rule.enabled()).update();
        }
    }

    public void insertDrgDefs(List<GrouperDrgDef> defs) {
        for (GrouperDrgDef def : defs) {
            jdbc.sql("""
                            INSERT INTO insurance_svc.grouper_drg_def
                                (rule_set_id, drg_code, drg_name, adrg_code, severity, weight)
                            VALUES (:setId, :drgCode, :drgName, :adrgCode, :severity, :weight)
                            """)
                    .param("setId", def.ruleSetId()).param("drgCode", def.drgCode())
                    .param("drgName", def.drgName()).param("adrgCode", def.adrgCode())
                    .param("severity", def.severity()).param("weight", def.weight()).update();
        }
    }

    public void insertCcRules(List<GrouperCcRule> rules) {
        for (GrouperCcRule rule : rules) {
            jdbc.sql("""
                            INSERT INTO insurance_svc.grouper_cc_rule
                                (id, rule_set_id, severity, diag_prefix)
                            VALUES (:id, :setId, :severity, :diag)
                            """)
                    .param("id", rule.id()).param("setId", rule.ruleSetId())
                    .param("severity", rule.severity()).param("diag", rule.diagPrefix()).update();
        }
    }

    public void insertCcExclusions(List<GrouperCcExclusion> exclusions) {
        for (GrouperCcExclusion exclusion : exclusions) {
            jdbc.sql("""
                            INSERT INTO insurance_svc.grouper_cc_exclusion
                                (id, rule_set_id, primary_diag_prefix, secondary_diag_prefix)
                            VALUES (:id, :setId, :primary, :secondary)
                            """)
                    .param("id", exclusion.id()).param("setId", exclusion.ruleSetId())
                    .param("primary", exclusion.primaryDiagPrefix())
                    .param("secondary", exclusion.secondaryDiagPrefix()).update();
        }
    }

    public void insertDipCatalog(List<GrouperDipCatalog> items) {
        for (GrouperDipCatalog item : items) {
            jdbc.sql("""
                            INSERT INTO insurance_svc.grouper_dip_catalog
                                (id, rule_set_id, dip_code, dip_name, diag_code, proc_code,
                                 type, score)
                            VALUES (:id, :setId, :dipCode, :dipName, :diag, :proc, :type, :score)
                            """)
                    .param("id", item.id()).param("setId", item.ruleSetId())
                    .param("dipCode", item.dipCode()).param("dipName", item.dipName())
                    .param("diag", item.diagCode()).param("proc", item.procCode())
                    .param("type", item.type()).param("score", item.score()).update();
        }
    }

    public List<GrouperMdcRule> listMdcRules(UUID ruleSetId) {
        return jdbc.sql("""
                        SELECT id, rule_set_id, priority, mdc_code, mdc_name, diag_from, diag_to,
                               enabled
                          FROM insurance_svc.grouper_mdc_rule
                         WHERE rule_set_id = ? ORDER BY priority
                        """).param(ruleSetId).query(MDC_MAPPER).list();
    }

    public List<GrouperAdrgRule> listAdrgRules(UUID ruleSetId) {
        return jdbc.sql("""
                        SELECT id, rule_set_id, priority, adrg_code, adrg_name, mdc_code,
                               match_type, diag_prefix, proc_prefix, enabled
                          FROM insurance_svc.grouper_adrg_rule
                         WHERE rule_set_id = ? ORDER BY priority
                        """).param(ruleSetId).query(ADRG_MAPPER).list();
    }

    public List<GrouperDrgDef> listDrgDefs(UUID ruleSetId) {
        return jdbc.sql("""
                        SELECT rule_set_id, drg_code, drg_name, adrg_code, severity, weight
                          FROM insurance_svc.grouper_drg_def
                         WHERE rule_set_id = ? ORDER BY drg_code
                        """).param(ruleSetId).query(DRG_DEF_MAPPER).list();
    }

    public List<GrouperCcRule> listCcRules(UUID ruleSetId) {
        return jdbc.sql("""
                        SELECT id, rule_set_id, severity, diag_prefix
                          FROM insurance_svc.grouper_cc_rule
                         WHERE rule_set_id = ? ORDER BY severity DESC, diag_prefix
                        """).param(ruleSetId).query(CC_RULE_MAPPER).list();
    }

    public List<GrouperCcExclusion> listCcExclusions(UUID ruleSetId) {
        return jdbc.sql("""
                        SELECT id, rule_set_id, primary_diag_prefix, secondary_diag_prefix
                          FROM insurance_svc.grouper_cc_exclusion WHERE rule_set_id = ?
                        """).param(ruleSetId).query(CC_EXCLUSION_MAPPER).list();
    }

    public List<GrouperDipCatalog> listDipCatalog(UUID ruleSetId) {
        return jdbc.sql("""
                        SELECT id, rule_set_id, dip_code, dip_name, diag_code, proc_code, type, score
                          FROM insurance_svc.grouper_dip_catalog WHERE rule_set_id = ?
                        """).param(ruleSetId).query(DIP_MAPPER).list();
    }

    public void insertGrouperResult(GrouperResult result) {
        jdbc.sql("""
                        INSERT INTO insurance_svc.grouper_result
                            (id, visit_id, rule_set_id, rule_set_code, rule_set_version, engine,
                             type, mdc_code, adrg_code, drg_code, dip_code, severity, weight,
                             score, payment_standard, matched, reason, trace,
                             principal_diagnosis, secondary_diagnoses, procedures, input_hash,
                             created_by)
                        VALUES (:id, :visitId, :setId, :setCode, :setVersion, :engine, :type,
                                :mdc, :adrg, :drg, :dip, :severity, :weight, :score, :payment,
                                :matched, :reason, CAST(:trace AS jsonb), :principal,
                                CAST(:secondary AS jsonb), CAST(:procedures AS jsonb),
                                :hash, :actor)
                        """)
                .param("id", result.id()).param("visitId", result.visitId())
                .param("setId", result.ruleSetId()).param("setCode", result.ruleSetCode())
                .param("setVersion", result.ruleSetVersion()).param("engine", result.engine())
                .param("type", result.type()).param("mdc", result.mdcCode())
                .param("adrg", result.adrgCode()).param("drg", result.drgCode())
                .param("dip", result.dipCode()).param("severity", result.severity())
                .param("weight", result.weight()).param("score", result.score())
                .param("payment", result.paymentStandard()).param("matched", result.matched())
                .param("reason", result.reason()).param("trace", result.trace())
                .param("principal", result.principalDiagnosis())
                .param("secondary", result.secondaryDiagnoses())
                .param("procedures", result.procedures()).param("hash", result.inputHash())
                .param("actor", result.createdBy()).update();
    }

    private static final String GROUPER_RESULT_SELECT = """
            SELECT id, visit_id, rule_set_id, rule_set_code, rule_set_version, engine, type,
                   mdc_code, adrg_code, drg_code, dip_code, severity, weight, score,
                   payment_standard, matched, reason, trace::text AS trace,
                   principal_diagnosis, secondary_diagnoses::text AS secondary_diagnoses,
                   procedures::text AS procedures, input_hash, created_by, created_at
              FROM insurance_svc.grouper_result
            """;

    public Optional<GrouperResult> findLatestGrouperResult(UUID visitId, String engine) {
        return jdbc.sql(GROUPER_RESULT_SELECT + """
                         WHERE visit_id = :visitId AND engine = :engine
                         ORDER BY created_at DESC LIMIT 1
                        """).param("visitId", visitId).param("engine", engine)
                .query(GROUPER_RESULT_MAPPER).optional();
    }

    public List<GrouperResult> listGrouperResults(UUID visitId, int limit) {
        return jdbc.sql(GROUPER_RESULT_SELECT + """
                         WHERE visit_id = :visitId
                         ORDER BY created_at DESC LIMIT :limit
                        """).param("visitId", visitId).param("limit", limit)
                .query(GROUPER_RESULT_MAPPER).list();
    }

    /** 分组报表：按引擎/类型统计（半开区间 [from, to)）。 */
    public List<GrouperEngineStat> grouperEngineStats(java.time.Instant from,
                                                      java.time.Instant to) {
        return jdbc.sql("""
                        SELECT engine, type, count(*) AS total,
                               count(*) FILTER (WHERE matched) AS matched,
                               count(DISTINCT visit_id) AS visits
                          FROM insurance_svc.grouper_result
                         WHERE created_at >= :from AND created_at < :to
                         GROUP BY engine, type
                         ORDER BY engine, type
                        """)
                .param("from", java.sql.Timestamp.from(from))
                .param("to", java.sql.Timestamp.from(to))
                .query((rs, i) -> new GrouperEngineStat(rs.getString("engine"),
                        rs.getString("type"), rs.getLong("total"), rs.getLong("matched"),
                        rs.getLong("visits")))
                .list();
    }

    /** 分组报表：入组次数 Top N（DRG 取 drg_code、DIP 取 dip_code）。 */
    public List<GrouperCodeCount> grouperTopGroups(java.time.Instant from,
                                                   java.time.Instant to, int limit) {
        return jdbc.sql("""
                        SELECT type, COALESCE(drg_code, dip_code) AS code, count(*) AS hits
                          FROM insurance_svc.grouper_result
                         WHERE matched AND created_at >= :from AND created_at < :to
                         GROUP BY type, COALESCE(drg_code, dip_code)
                         ORDER BY hits DESC
                         LIMIT :limit
                        """)
                .param("from", java.sql.Timestamp.from(from))
                .param("to", java.sql.Timestamp.from(to)).param("limit", limit)
                .query((rs, i) -> new GrouperCodeCount(rs.getString("type"),
                        rs.getString("code"), rs.getLong("hits")))
                .list();
    }

    /** 分组报表：未入组原因 Top N（病案质控入口）。 */
    public List<GrouperReasonCount> grouperTopReasons(java.time.Instant from,
                                                      java.time.Instant to, int limit) {
        return jdbc.sql("""
                        SELECT COALESCE(reason, '未记录原因') AS reason, count(*) AS hits
                          FROM insurance_svc.grouper_result
                         WHERE NOT matched AND created_at >= :from AND created_at < :to
                         GROUP BY COALESCE(reason, '未记录原因')
                         ORDER BY hits DESC
                         LIMIT :limit
                        """)
                .param("from", java.sql.Timestamp.from(from))
                .param("to", java.sql.Timestamp.from(to)).param("limit", limit)
                .query((rs, i) -> new GrouperReasonCount(rs.getString("reason"),
                        rs.getLong("hits")))
                .list();
    }

    /** jsonb 列读回为 null 时按空数组处理，避免拼出 "null" 字符串。 */
    private static String orEmpty(String json) {
        return json == null ? "[]" : json;
    }

    // ---------------------------------------------------------------- 结算对账（§291）

    private static final RowMapper<ReconBatch> RECON_BATCH_MAPPER = (rs, i) -> new ReconBatch(
            rs.getObject("id", UUID.class), rs.getString("period_type"),
            rs.getDate("period_from").toLocalDate(), rs.getDate("period_to").toLocalDate(),
            rs.getString("status"), rs.getInt("bill_count"), rs.getInt("settled_count"),
            rs.getInt("not_settled_count"), rs.getInt("failed_count"),
            rs.getInt("mismatch_count"), rs.getInt("voided_after_settle_count"),
            rs.getInt("unverified_count"), rs.getInt("clinic_mismatch_count"),
            rs.getInt("clinic_unmatched_count"), rs.getInt("open_diff_count"),
            rs.getBigDecimal("local_total"), rs.getBigDecimal("local_insurance"),
            rs.getBigDecimal("local_self_pay"), rs.getBigDecimal("counterparty_insurance"),
            rs.getBigDecimal("counterparty_self_pay"), rs.getBigDecimal("clinic_total"),
            rs.getBigDecimal("clinic_refund_total"), rs.getBigDecimal("diff_amount"),
            rs.getString("computed_by"), rs.getTimestamp("computed_at").toInstant(),
            rs.getString("closed_by"),
            rs.getTimestamp("closed_at") == null ? null : rs.getTimestamp("closed_at").toInstant(),
            rs.getLong("version"));

    private static final String RECON_BATCH_COLUMNS = """
            id, period_type, period_from, period_to, status, bill_count, settled_count,
            not_settled_count, failed_count, mismatch_count, voided_after_settle_count,
            unverified_count, clinic_mismatch_count, clinic_unmatched_count, open_diff_count,
            local_total, local_insurance, local_self_pay,
            counterparty_insurance, counterparty_self_pay, clinic_total, clinic_refund_total,
            diff_amount, computed_by, computed_at, closed_by, closed_at, version
            """;

    private static final RowMapper<ReconDiff> RECON_DIFF_MAPPER = (rs, i) -> new ReconDiff(
            rs.getObject("id", UUID.class), rs.getObject("batch_id", UUID.class),
            rs.getObject("bill_id", UUID.class), rs.getString("bill_no"),
            rs.getObject("visit_id", UUID.class), rs.getString("category"),
            rs.getBigDecimal("local_insurance"), rs.getBigDecimal("local_self_pay"),
            rs.getBigDecimal("counterparty_insurance"), rs.getBigDecimal("counterparty_self_pay"),
            rs.getBigDecimal("clinic_total"), rs.getBigDecimal("diff_amount"),
            rs.getString("detail"), rs.getString("status"),
            rs.getString("resolved_by"),
            rs.getTimestamp("resolved_at") == null
                    ? null : rs.getTimestamp("resolved_at").toInstant(),
            rs.getString("resolve_note"), rs.getTimestamp("created_at").toInstant());

    private static final String RECON_DIFF_COLUMNS = """
            id, batch_id, bill_id, bill_no, visit_id, category, local_insurance, local_self_pay,
            counterparty_insurance, counterparty_self_pay, clinic_total, diff_amount, detail,
            status, resolved_by, resolved_at, resolve_note, created_at
            """;

    private static final RowMapper<ReconBillRow> RECON_ROW_MAPPER = (rs, i) -> new ReconBillRow(
            rs.getObject("bill_id", UUID.class), rs.getString("bill_no"),
            rs.getObject("visit_id", UUID.class), rs.getDate("service_date").toLocalDate(),
            rs.getString("bill_status"), rs.getBigDecimal("total_amount"),
            rs.getBigDecimal("insurance_amount"), rs.getBigDecimal("self_pay_amount"),
            rs.getString("settle_status"), rs.getInt("attempts"), rs.getString("last_error"),
            rs.getString("response_payload"));

    /**
     * 对账读取：期间内已确认清单 + 作废但已结算成功的清单（用于 VOIDED_AFTER_SETTLE）。
     *
     * <p>期间按 service_date 归属；无院区过滤——对账是全院财务动作，
     * 调用方必须持有全院视角（由服务层校验）。
     */
    public List<ReconBillRow> listReconBills(LocalDate from, LocalDate to, int limit) {
        return jdbc.sql("""
                        SELECT b.id AS bill_id, b.bill_no, b.visit_id, b.service_date,
                               b.status AS bill_status, b.total_amount, b.insurance_amount,
                               b.self_pay_amount,
                               r.status AS settle_status, COALESCE(r.attempts, 0) AS attempts,
                               r.last_error, r.response_payload::text AS response_payload
                          FROM insurance_svc.settlement_bill b
                          LEFT JOIN LATERAL (
                                SELECT t.status, t.attempts, t.last_error, t.response_payload
                                  FROM insurance_svc.realtime_settlement t
                                 WHERE t.bill_id = b.id AND t.biz_type = 'SETTLE'
                                 ORDER BY t.created_at DESC
                                 LIMIT 1) r ON true
                         WHERE b.service_date BETWEEN :from AND :to
                           AND (b.status = 'CONFIRMED'
                                OR (b.status = 'VOIDED' AND r.status = 'SETTLED'))
                         ORDER BY b.bill_no
                         LIMIT :limit
                        """)
                .param("from", from).param("to", to).param("limit", limit)
                .query(RECON_ROW_MAPPER).list();
    }

    /** 期间内参与对账的清单数（先计数再决定是否允许生成，避免超大期间拖垮服务）。 */
    public long countReconBills(LocalDate from, LocalDate to) {
        return jdbc.sql("""
                        SELECT count(*)
                          FROM insurance_svc.settlement_bill b
                          LEFT JOIN LATERAL (
                                SELECT t.status
                                  FROM insurance_svc.realtime_settlement t
                                 WHERE t.bill_id = b.id AND t.biz_type = 'SETTLE'
                                 ORDER BY t.created_at DESC
                                 LIMIT 1) r ON true
                         WHERE b.service_date BETWEEN :from AND :to
                           AND (b.status = 'CONFIRMED'
                                OR (b.status = 'VOIDED' AND r.status = 'SETTLED'))
                        """)
                .param("from", from).param("to", to)
                .query(Long.class).single();
    }

    public Optional<ReconBatch> findReconBatch(String periodType, LocalDate from, LocalDate to) {
        return jdbc.sql("SELECT " + RECON_BATCH_COLUMNS + """
                          FROM insurance_svc.settlement_recon_batch
                         WHERE period_type = :type AND period_from = :from AND period_to = :to
                        """)
                .param("type", periodType).param("from", from).param("to", to)
                .query(RECON_BATCH_MAPPER).optional();
    }

    public Optional<ReconBatch> findReconBatchById(UUID id) {
        return jdbc.sql("SELECT " + RECON_BATCH_COLUMNS
                        + " FROM insurance_svc.settlement_recon_batch WHERE id = ?")
                .param(id).query(RECON_BATCH_MAPPER).optional();
    }

    public List<ReconBatch> listReconBatches(int limit) {
        return jdbc.sql("SELECT " + RECON_BATCH_COLUMNS + """
                          FROM insurance_svc.settlement_recon_batch
                         ORDER BY period_from DESC, period_type, computed_at DESC
                         LIMIT :limit
                        """)
                .param("limit", limit).query(RECON_BATCH_MAPPER).list();
    }

    /** 新批次：唯一键冲突返回 false（并发下由服务层回查既有批次）。 */
    public boolean insertReconBatch(ReconBatch b) {
        try {
            jdbc.sql("""
                            INSERT INTO insurance_svc.settlement_recon_batch
                                (id, period_type, period_from, period_to, status, bill_count,
                                 settled_count, not_settled_count, failed_count, mismatch_count,
                                 voided_after_settle_count, unverified_count,
                                 clinic_mismatch_count, clinic_unmatched_count, open_diff_count,
                                 local_total, local_insurance, local_self_pay,
                                 counterparty_insurance, counterparty_self_pay,
                                 clinic_total, clinic_refund_total, diff_amount, computed_by)
                            VALUES (:id, :periodType, :from, :to, 'COMPUTED', :billCount,
                                    :settledCount, :notSettled, :failed, :mismatch,
                                    :voidedAfterSettle, :unverified,
                                    :clinicMismatch, :clinicUnmatched, :openDiff,
                                    :localTotal, :localInsurance, :localSelfPay,
                                    :cpInsurance, :cpSelfPay,
                                    :clinicTotal, :clinicRefundTotal, :diffAmount, :by)
                            """)
                    .param("id", b.id()).param("periodType", b.periodType())
                    .param("from", b.periodFrom()).param("to", b.periodTo())
                    .param("billCount", b.billCount()).param("settledCount", b.settledCount())
                    .param("notSettled", b.notSettledCount()).param("failed", b.failedCount())
                    .param("mismatch", b.mismatchCount())
                    .param("voidedAfterSettle", b.voidedAfterSettleCount())
                    .param("unverified", b.unverifiedCount())
                    .param("clinicMismatch", b.clinicMismatchCount())
                    .param("clinicUnmatched", b.clinicUnmatchedCount())
                    .param("openDiff", b.openDiffCount())
                    .param("localTotal", b.localTotal()).param("localInsurance", b.localInsurance())
                    .param("localSelfPay", b.localSelfPay())
                    .param("cpInsurance", b.counterpartyInsurance())
                    .param("cpSelfPay", b.counterpartySelfPay())
                    .param("clinicTotal", b.clinicTotal())
                    .param("clinicRefundTotal", b.clinicRefundTotal())
                    .param("diffAmount", b.diffAmount()).param("by", b.computedBy())
                    .update();
            return true;
        } catch (org.springframework.dao.DuplicateKeyException e) {
            return false;
        }
    }

    /**
     * 重算写回：只有 COMPUTED 且版本一致才更新（CAS）。
     * 返回 false 表示已被他人重算/关闭，调用方按冲突处理。
     */
    public boolean updateReconBatch(ReconBatch b) {
        return jdbc.sql("""
                        UPDATE insurance_svc.settlement_recon_batch
                           SET bill_count = :billCount, settled_count = :settledCount,
                               not_settled_count = :notSettled, failed_count = :failed,
                               mismatch_count = :mismatch,
                               voided_after_settle_count = :voidedAfterSettle,
                               unverified_count = :unverified,
                               clinic_mismatch_count = :clinicMismatch,
                               clinic_unmatched_count = :clinicUnmatched,
                               open_diff_count = :openDiff,
                               local_total = :localTotal, local_insurance = :localInsurance,
                               local_self_pay = :localSelfPay,
                               counterparty_insurance = :cpInsurance,
                               counterparty_self_pay = :cpSelfPay,
                               clinic_total = :clinicTotal,
                               clinic_refund_total = :clinicRefundTotal,
                               diff_amount = :diffAmount, computed_by = :by,
                               computed_at = now(), version = version + 1
                         WHERE id = :id AND status = 'COMPUTED' AND version = :version
                        """)
                .param("id", b.id()).param("version", b.version())
                .param("billCount", b.billCount()).param("settledCount", b.settledCount())
                .param("notSettled", b.notSettledCount()).param("failed", b.failedCount())
                .param("mismatch", b.mismatchCount())
                .param("voidedAfterSettle", b.voidedAfterSettleCount())
                .param("unverified", b.unverifiedCount())
                .param("clinicMismatch", b.clinicMismatchCount())
                .param("clinicUnmatched", b.clinicUnmatchedCount())
                .param("openDiff", b.openDiffCount())
                .param("localTotal", b.localTotal()).param("localInsurance", b.localInsurance())
                .param("localSelfPay", b.localSelfPay())
                .param("cpInsurance", b.counterpartyInsurance())
                .param("cpSelfPay", b.counterpartySelfPay())
                .param("clinicTotal", b.clinicTotal())
                .param("clinicRefundTotal", b.clinicRefundTotal())
                .param("diffAmount", b.diffAmount()).param("by", b.computedBy())
                .update() > 0;
    }

    /** 关闭批次：仅 COMPUTED + 版本一致；返回 false 表示状态/版本已变。 */
    public boolean closeReconBatch(UUID id, long version, String actor) {
        return jdbc.sql("""
                        UPDATE insurance_svc.settlement_recon_batch
                           SET status = 'CLOSED', closed_by = :actor, closed_at = now(),
                               version = version + 1
                         WHERE id = :id AND status = 'COMPUTED' AND version = :version
                           AND open_diff_count = 0
                        """)
                .param("id", id).param("version", version).param("actor", actor)
                .update() > 0;
    }

    public List<ReconDiff> listReconDiffs(UUID batchId, String status, int limit) {
        return jdbc.sql("SELECT " + RECON_DIFF_COLUMNS + """
                          FROM insurance_svc.settlement_recon_diff
                         WHERE batch_id = :batchId
                           AND (CAST(:status AS text) = '' OR status = CAST(:status AS text))
                         ORDER BY status = 'OPEN' DESC, category, bill_no
                         LIMIT :limit
                        """)
                .param("batchId", batchId)
                .param("status", status == null ? "" : status.trim().toUpperCase())
                .param("limit", limit).query(RECON_DIFF_MAPPER).list();
    }

    public Optional<ReconDiff> findReconDiff(UUID id) {
        return jdbc.sql("SELECT " + RECON_DIFF_COLUMNS
                        + " FROM insurance_svc.settlement_recon_diff WHERE id = ?")
                .param(id).query(RECON_DIFF_MAPPER).optional();
    }

    /** 新差异：同 (batch,bill,category) 已存在时什么都不做（保留人工处理状态）。 */
    public void insertReconDiff(ReconDiff d) {
        jdbc.sql("""
                        INSERT INTO insurance_svc.settlement_recon_diff
                            (id, batch_id, bill_id, bill_no, visit_id, category, local_insurance,
                             local_self_pay, counterparty_insurance, counterparty_self_pay,
                             clinic_total, diff_amount, detail, status)
                        VALUES (:id, :batchId, :billId, :billNo, :visitId, :category,
                                :localInsurance, :localSelfPay, :cpInsurance, :cpSelfPay,
                                :clinicTotal, :diffAmount, :detail, 'OPEN')
                        ON CONFLICT (batch_id, bill_id, category) DO NOTHING
                        """)
                .param("id", d.id()).param("batchId", d.batchId()).param("billId", d.billId())
                .param("billNo", d.billNo()).param("visitId", d.visitId())
                .param("category", d.category()).param("localInsurance", d.localInsurance())
                .param("localSelfPay", d.localSelfPay())
                .param("cpInsurance", d.counterpartyInsurance())
                .param("cpSelfPay", d.counterpartySelfPay())
                .param("clinicTotal", d.clinicTotal())
                .param("diffAmount", d.diffAmount()).param("detail", d.detail())
                .update();
    }

    /** 仍存在的 OPEN 差异：刷新金额与原因（人工已处理的终态不动）。 */
    public boolean refreshOpenReconDiff(ReconDiff d) {
        return jdbc.sql("""
                        UPDATE insurance_svc.settlement_recon_diff
                           SET local_insurance = :localInsurance, local_self_pay = :localSelfPay,
                               counterparty_insurance = :cpInsurance,
                               counterparty_self_pay = :cpSelfPay,
                               clinic_total = :clinicTotal,
                               diff_amount = :diffAmount, detail = :detail,
                               updated_at = now()
                         WHERE batch_id = :batchId AND bill_id = :billId
                           AND category = :category AND status = 'OPEN'
                        """)
                .param("batchId", d.batchId()).param("billId", d.billId())
                .param("category", d.category()).param("localInsurance", d.localInsurance())
                .param("localSelfPay", d.localSelfPay())
                .param("cpInsurance", d.counterpartyInsurance())
                .param("cpSelfPay", d.counterpartySelfPay())
                .param("clinicTotal", d.clinicTotal())
                .param("diffAmount", d.diffAmount()).param("detail", d.detail())
                .update() > 0;
    }

    /** 重算后已不存在的 OPEN 差异：自动关闭（不算人工处理）。 */
    public int autoCloseReconDiff(UUID id) {
        return jdbc.sql("""
                        UPDATE insurance_svc.settlement_recon_diff
                           SET status = 'AUTO_CLOSED', updated_at = now()
                         WHERE id = :id AND status = 'OPEN'
                        """).param("id", id).update();
    }

    /**
     * 重算前先把本批次所有 OPEN 置为 AUTO_CLOSED，再由"仍然存在的差异"重新打开。
     *
     * <p>为什么不逐个比对：差异数可达上万，逐个查询会把重算拖成 N+1；
     * 一条 UPDATE + 逐条 reopen 是 O(1)+O(n)，且不会漏掉第 501 条之后的陈旧差异。
     */
    public int autoCloseAllOpenDiffs(UUID batchId) {
        return jdbc.sql("""
                        UPDATE insurance_svc.settlement_recon_diff
                           SET status = 'AUTO_CLOSED', updated_at = now()
                         WHERE batch_id = :batchId AND status = 'OPEN'
                        """).param("batchId", batchId).update();
    }

    /**
     * 自动关闭后问题又出现：AUTO_CLOSED → OPEN（刷新金额与原因）。
     *
     * <p>RESOLVED / IGNORED 不在此列：那是人工结论，重算不能擅自推翻；
     * AUTO_CLOSED 只是"上次重算时已消失"，重新出现必须再次提醒。
     */
    public boolean reopenAutoClosedReconDiff(ReconDiff d) {
        return jdbc.sql("""
                        UPDATE insurance_svc.settlement_recon_diff
                           SET status = 'OPEN', local_insurance = :localInsurance,
                               local_self_pay = :localSelfPay,
                               counterparty_insurance = :cpInsurance,
                               counterparty_self_pay = :cpSelfPay,
                               clinic_total = :clinicTotal,
                               diff_amount = :diffAmount, detail = :detail, updated_at = now()
                         WHERE batch_id = :batchId AND bill_id = :billId
                           AND category = :category AND status = 'AUTO_CLOSED'
                        """)
                .param("batchId", d.batchId()).param("billId", d.billId())
                .param("category", d.category()).param("localInsurance", d.localInsurance())
                .param("localSelfPay", d.localSelfPay())
                .param("cpInsurance", d.counterpartyInsurance())
                .param("cpSelfPay", d.counterpartySelfPay())
                .param("clinicTotal", d.clinicTotal())
                .param("diffAmount", d.diffAmount()).param("detail", d.detail())
                .update() > 0;
    }

    /** 人工处理：OPEN → RESOLVED / IGNORED（说明与处理人必填，服务层已校验）。 */
    public boolean resolveReconDiff(UUID id, String status, String actor, String note) {
        return jdbc.sql("""
                        UPDATE insurance_svc.settlement_recon_diff
                           SET status = :status, resolved_by = :actor, resolved_at = now(),
                               resolve_note = :note, updated_at = now()
                         WHERE id = :id AND status = 'OPEN'
                        """)
                .param("id", id).param("status", status).param("actor", actor)
                .param("note", note).update() > 0;
    }

    /** 批量重算 OPEN 差异数（关闭前判断"是否还有未处理差异"）。 */
    public int countOpenReconDiffs(UUID batchId) {
        return jdbc.sql("""
                        SELECT count(*) FROM insurance_svc.settlement_recon_diff
                         WHERE batch_id = ? AND status = 'OPEN'
                        """).param(batchId).query(Integer.class).single();
    }

    /** 同步批次上的 open_diff_count（差异处理后调用；CLOSED 批次由触发器兜底拒绝）。 */
    public boolean updateOpenDiffCount(UUID batchId, int openCount) {
        return jdbc.sql("""
                        UPDATE insurance_svc.settlement_recon_batch
                           SET open_diff_count = :openCount, version = version + 1
                         WHERE id = :id AND status = 'COMPUTED'
                        """)
                .param("id", batchId).param("openCount", openCount).update() > 0;
    }

    // ---------------------------------------------------------------- 支付方分摊（§292）

    private static final RowMapper<PayerConfig> PAYER_MAPPER = (rs, i) -> new PayerConfig(
            rs.getString("code"), rs.getString("name"), rs.getString("payer_type"),
            rs.getBoolean("enabled"), rs.getInt("sort_no"), rs.getString("remark"),
            rs.getLong("version"), rs.getString("updated_by"),
            rs.getTimestamp("updated_at").toInstant(),
            rs.getTimestamp("created_at").toInstant());

    private static final RowMapper<SettlementAllocation> ALLOCATION_MAPPER = (rs, i) ->
            new SettlementAllocation(
                    rs.getObject("id", UUID.class), rs.getObject("bill_id", UUID.class),
                    rs.getObject("visit_id", UUID.class), rs.getString("payer_code"),
                    rs.getString("payer_type"), rs.getString("kind"),
                    rs.getObject("original_id", UUID.class), rs.getBigDecimal("amount"),
                    rs.getString("reason"), rs.getString("operator"),
                    rs.getTimestamp("created_at").toInstant());

    public List<PayerConfig> listPayers(boolean onlyEnabled) {
        return jdbc.sql("""
                        SELECT code, name, payer_type, enabled, sort_no, remark, version,
                               updated_by, updated_at, created_at
                          FROM insurance_svc.payer_config
                         WHERE (NOT :onlyEnabled OR enabled)
                         ORDER BY sort_no, code
                        """)
                .param("onlyEnabled", onlyEnabled).query(PAYER_MAPPER).list();
    }

    public Optional<PayerConfig> findPayer(String code) {
        return jdbc.sql("""
                        SELECT code, name, payer_type, enabled, sort_no, remark, version,
                               updated_by, updated_at, created_at
                          FROM insurance_svc.payer_config WHERE code = ?
                        """).param(code).query(PAYER_MAPPER).optional();
    }

    /** 新增支付方；重复编码返回 false。 */
    public boolean insertPayer(PayerConfig p) {
        try {
            jdbc.sql("""
                            INSERT INTO insurance_svc.payer_config
                                (code, name, payer_type, enabled, sort_no, remark, updated_by)
                            VALUES (:code, :name, :type, :enabled, :sortNo, :remark, :by)
                            """)
                    .param("code", p.code()).param("name", p.name())
                    .param("type", p.payerType()).param("enabled", p.enabled())
                    .param("sortNo", p.sortNo()).param("remark", p.remark())
                    .param("by", p.updatedBy()).update();
            return true;
        } catch (org.springframework.dao.DuplicateKeyException e) {
            return false;
        }
    }

    /** 修改支付方（名称/类型/排序/备注）；CAS 版本不一致返回 false。 */
    public boolean updatePayer(PayerConfig p) {
        return jdbc.sql("""
                        UPDATE insurance_svc.payer_config
                           SET name = :name, payer_type = :type, sort_no = :sortNo,
                               remark = :remark, updated_by = :by, updated_at = now(),
                               version = version + 1
                         WHERE code = :code AND version = :version
                        """)
                .param("code", p.code()).param("name", p.name())
                .param("type", p.payerType()).param("sortNo", p.sortNo())
                .param("remark", p.remark()).param("by", p.updatedBy())
                .param("version", p.version()).update() > 0;
    }

    /** 启用/停用支付方（停用只影响新分摊，不改写历史台账）。 */
    public boolean setPayerEnabled(String code, boolean enabled, long version, String actor) {
        return jdbc.sql("""
                        UPDATE insurance_svc.payer_config
                           SET enabled = :enabled, updated_by = :by, updated_at = now(),
                               version = version + 1
                         WHERE code = :code AND version = :version
                        """)
                .param("code", code).param("enabled", enabled).param("by", actor)
                .param("version", version).update() > 0;
    }

    public List<SettlementAllocation> listAllocations(UUID billId) {
        return jdbc.sql("""
                        SELECT id, bill_id, visit_id, payer_code, payer_type, kind, original_id,
                               amount, reason, operator, created_at
                          FROM insurance_svc.settlement_allocation
                         WHERE bill_id = ?
                         ORDER BY created_at, id
                        """).param(billId).query(ALLOCATION_MAPPER).list();
    }

    public Optional<SettlementAllocation> findChargeAllocation(UUID billId, String payerCode) {
        return jdbc.sql("""
                        SELECT id, bill_id, visit_id, payer_code, payer_type, kind, original_id,
                               amount, reason, operator, created_at
                          FROM insurance_svc.settlement_allocation
                         WHERE bill_id = :billId AND payer_code = :code AND kind = 'CHARGE'
                        """)
                .param("billId", billId).param("code", payerCode)
                .query(ALLOCATION_MAPPER).optional();
    }

    public Optional<SettlementAllocation> findAllocation(UUID id) {
        return jdbc.sql("""
                        SELECT id, bill_id, visit_id, payer_code, payer_type, kind, original_id,
                               amount, reason, operator, created_at
                          FROM insurance_svc.settlement_allocation WHERE id = ?
                        """).param(id).query(ALLOCATION_MAPPER).optional();
    }

    public void insertAllocation(SettlementAllocation a) {
        jdbc.sql("""
                        INSERT INTO insurance_svc.settlement_allocation
                            (id, bill_id, visit_id, payer_code, payer_type, kind, original_id,
                             amount, reason, operator)
                        VALUES (:id, :billId, :visitId, :code, :type, :kind, :originalId,
                                :amount, :reason, :operator)
                        """)
                .param("id", a.id()).param("billId", a.billId()).param("visitId", a.visitId())
                .param("code", a.payerCode()).param("type", a.payerType())
                .param("kind", a.kind()).param("originalId", a.originalId())
                .param("amount", a.amount()).param("reason", a.reason())
                .param("operator", a.operator()).update();
    }

    /** 某条应收分摊已退金额（用于"退费不得超过原分摊"）。 */
    public BigDecimal sumRefunds(UUID originalId) {
        return jdbc.sql("""
                        SELECT COALESCE(sum(amount), 0) FROM insurance_svc.settlement_allocation
                         WHERE original_id = ? AND kind = 'REFUND'
                        """).param(originalId).query(BigDecimal.class).single();
    }

    /** 清单尚未冲销的分摊净额（作废清单前必须为 0，否则财务账不平）。 */
    public BigDecimal outstandingAllocation(UUID billId) {
        return jdbc.sql("""
                        SELECT COALESCE(sum(CASE WHEN kind = 'CHARGE' THEN amount
                                                 ELSE -amount END), 0)
                          FROM insurance_svc.settlement_allocation WHERE bill_id = ?
                        """).param(billId).query(BigDecimal.class).single();
    }

    /** 按支付方汇总一张清单的应收/已退/净额。 */
    public List<PayerAllocationLine> summarizeAllocations(UUID billId) {
        return jdbc.sql("""
                        SELECT a.payer_code, p.name AS payer_name, p.payer_type,
                               COALESCE(sum(CASE WHEN a.kind = 'CHARGE' THEN a.amount END), 0)
                                   AS charged,
                               COALESCE(sum(CASE WHEN a.kind = 'REFUND' THEN a.amount END), 0)
                                   AS refunded
                          FROM insurance_svc.settlement_allocation a
                          JOIN insurance_svc.payer_config p ON p.code = a.payer_code
                         WHERE a.bill_id = :billId
                         GROUP BY a.payer_code, p.name, p.payer_type, p.sort_no
                         ORDER BY p.sort_no, a.payer_code
                        """)
                .param("billId", billId)
                .query((rs, i) -> new PayerAllocationLine(
                        rs.getString("payer_code"), rs.getString("payer_name"),
                        rs.getString("payer_type"), rs.getBigDecimal("charged"),
                        rs.getBigDecimal("refunded"),
                        rs.getBigDecimal("charged").subtract(rs.getBigDecimal("refunded"))))
                .list();
    }

    /**
     * 清单分摊台账列表：pending=true 只看尚未分摊的已确认清单。
     *
     * <p>只查已确认清单（DRAFT/VOIDED 不应分摊），按业务日期倒序；
     * 返回已分摊汇总，供页面区分"待分摊 / 已分摊"。
     */
    public List<AllocationBillRow> listBillsForAllocation(boolean pending, int limit) {
        return jdbc.sql("""
                        SELECT b.id AS bill_id, b.bill_no, b.visit_id, b.service_date,
                               b.total_amount, b.insurance_amount, b.self_pay_amount,
                               COALESCE(a.charged, 0) AS charged_amount,
                               COALESCE(a.refunded, 0) AS refunded_amount,
                               COALESCE(a.cnt, 0) AS allocation_count
                          FROM insurance_svc.settlement_bill b
                          LEFT JOIN LATERAL (
                                SELECT sum(CASE WHEN x.kind = 'CHARGE' THEN x.amount END)
                                           AS charged,
                                       sum(CASE WHEN x.kind = 'REFUND' THEN x.amount END)
                                           AS refunded,
                                       count(*)::int AS cnt
                                  FROM insurance_svc.settlement_allocation x
                                 WHERE x.bill_id = b.id) a ON true
                         WHERE b.status = 'CONFIRMED'
                           AND (NOT :pending OR COALESCE(a.cnt, 0) = 0)
                         ORDER BY b.service_date DESC, b.bill_no DESC
                         LIMIT :limit
                        """)
                .param("pending", pending).param("limit", limit)
                .query((rs, i) -> new AllocationBillRow(
                        rs.getObject("bill_id", UUID.class), rs.getString("bill_no"),
                        rs.getObject("visit_id", UUID.class),
                        rs.getDate("service_date").toLocalDate(),
                        rs.getBigDecimal("total_amount"), rs.getBigDecimal("insurance_amount"),
                        rs.getBigDecimal("self_pay_amount"), rs.getBigDecimal("charged_amount"),
                        rs.getBigDecimal("refunded_amount"), rs.getInt("allocation_count")))
                .list();
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
