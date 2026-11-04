package com.medassist.insurance.repo;

import com.medassist.common.time.BusinessTime;
import com.medassist.insurance.domain.InsuranceModel.ChargeMapping;
import com.medassist.insurance.domain.InsuranceModel.ExportBatch;
import com.medassist.insurance.domain.InsuranceModel.GroupSimulation;
import com.medassist.insurance.domain.InsuranceModel.InsuranceItem;
import com.medassist.insurance.domain.InsuranceModel.Member;
import com.medassist.insurance.domain.InsuranceModel.MemberHistory;
import com.medassist.insurance.domain.InsuranceModel.PaymentGroup;
import com.medassist.insurance.domain.InsuranceModel.PaymentGroupRule;
import com.medassist.insurance.domain.InsuranceModel.PaymentScheme;
import com.medassist.insurance.domain.InsuranceModel.RealtimeSettlement;
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

    /** 插入交易占位；同一清单同一请求号已存在时不插入（幂等由唯一索引兜底）。 */
    public boolean insertRealtime(RealtimeSettlement r) {
        return jdbc.sql("""
                        INSERT INTO insurance_svc.realtime_settlement
                            (id, bill_id, visit_id, request_id, biz_type, platform, status,
                             request_payload, created_by)
                        VALUES (:id, :billId, :visitId, :requestId, :bizType, :platform, 'PENDING',
                                CAST(:requestPayload AS jsonb), :createdBy)
                        ON CONFLICT (bill_id, request_id) DO NOTHING
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

    /** 交易结果回写（CAS：版本一致才更新，避免并发重试覆盖彼此结果）。 */
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
                         WHERE id = :id AND version = :version
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
            rs.getString("payload"), rs.getString("payload_hash"), rs.getString("status"),
            rs.getString("receipt_no"),
            rs.getTimestamp("receipt_at") == null ? null : rs.getTimestamp("receipt_at").toInstant(),
            rs.getString("receipt_message"), rs.getString("cancelled_reason"),
            rs.getString("cancelled_by"),
            rs.getTimestamp("cancelled_at") == null ? null : rs.getTimestamp("cancelled_at").toInstant(),
            rs.getString("created_by"), rs.getLong("version"),
            rs.getTimestamp("created_at").toInstant());

    private static final String EXPORT_COLUMNS = """
            id, batch_no, period_from, period_to, bill_count, total_amount, insurance_amount,
            self_pay_amount, payload, payload_hash, status, receipt_no, receipt_at,
            receipt_message, cancelled_reason, cancelled_by, cancelled_at, created_by,
            version, created_at
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
        return jdbc.sql("SELECT " + BILL_COLUMNS + """
                         FROM insurance_svc.settlement_bill b
                         WHERE b.status = 'CONFIRMED'
                           AND b.confirmed_at >= :fromTs AND b.confirmed_at < :toTs
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
                .param("limit", limit)
                .query(BILL_MAPPER).list();
    }

    /** 期间内已确认但**已报盘**的清单数量（用于生成前回显"被跳过多少张"）。 */
    public long countConfirmedBillsAlreadyExported(LocalDate from, LocalDate to) {
        return jdbc.sql("""
                        SELECT count(*)
                          FROM insurance_svc.settlement_bill b
                         WHERE b.status = 'CONFIRMED'
                           AND b.confirmed_at >= :fromTs AND b.confirmed_at < :toTs
                           AND EXISTS (
                               SELECT 1
                                 FROM insurance_svc.settlement_export_item i
                                 JOIN insurance_svc.settlement_export_batch x ON x.id = i.batch_id
                                WHERE i.bill_id = b.id
                                  AND x.status IN ('GENERATED', 'ACCEPTED'))
                        """)
                .param("fromTs", from.atStartOfDay(BusinessTime.zone()).toOffsetDateTime())
                .param("toTs", to.plusDays(1).atStartOfDay(BusinessTime.zone()).toOffsetDateTime())
                .query(Long.class).single();
    }

    /** 期间内**待报盘**（已确认且未属有效批次）的清单数量；用于分批导出时回显剩余张数。 */
    public long countConfirmedBillsPending(LocalDate from, LocalDate to) {
        return jdbc.sql("""
                        SELECT count(*)
                          FROM insurance_svc.settlement_bill b
                         WHERE b.status = 'CONFIRMED'
                           AND b.confirmed_at >= :fromTs AND b.confirmed_at < :toTs
                           AND NOT EXISTS (
                               SELECT 1
                                 FROM insurance_svc.settlement_export_item i
                                 JOIN insurance_svc.settlement_export_batch x ON x.id = i.batch_id
                                WHERE i.bill_id = b.id
                                  AND x.status IN ('GENERATED', 'ACCEPTED'))
                        """)
                .param("fromTs", from.atStartOfDay(BusinessTime.zone()).toOffsetDateTime())
                .param("toTs", to.plusDays(1).atStartOfDay(BusinessTime.zone()).toOffsetDateTime())
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
                             insurance_amount, self_pay_amount, payload, payload_hash, status,
                             created_by, version)
                        VALUES (:id, :no, :from, :to, :count, :total, :insurance, :selfPay,
                                :payload, :hash, 'GENERATED', :actor, 0)
                        """)
                .param("id", b.id()).param("no", b.batchNo())
                .param("from", b.periodFrom()).param("to", b.periodTo())
                .param("count", b.billCount()).param("total", b.totalAmount())
                .param("insurance", b.insuranceAmount()).param("selfPay", b.selfPayAmount())
                .param("payload", b.payload()).param("hash", b.payloadHash())
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
