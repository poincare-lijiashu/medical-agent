package com.medassist.insurance.service;

import com.medassist.common.api.ErrorCode;
import com.medassist.common.exception.BusinessException;
import com.medassist.common.time.BusinessTime;
import com.medassist.insurance.client.ClinicalClient;
import com.medassist.insurance.client.PatientClient;
import com.medassist.insurance.domain.InsuranceModel.BillLine;
import com.medassist.insurance.domain.InsuranceModel.ChargeMapping;
import com.medassist.insurance.domain.InsuranceModel.InsuranceItem;
import com.medassist.insurance.domain.InsuranceModel.Member;
import com.medassist.insurance.domain.InsuranceModel.SettlementBill;
import com.medassist.insurance.repo.InsuranceRepository;
import com.medassist.insurance.web.InsuranceDtos.BillView;
import com.medassist.insurance.web.InsuranceDtos.ImportResult;
import com.medassist.insurance.web.InsuranceDtos.ItemReq;
import com.medassist.insurance.web.InsuranceDtos.MappingReq;
import com.medassist.insurance.web.InsuranceDtos.MemberReq;
import com.medassist.insurance.web.InsuranceDtos.ReconcileView;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;

/**
 * 医保目录、映射、参保登记与结算清单测算。
 *
 * <p>结算口径（本地测算模型，真实结算以省局接口为准）：
 * <ol>
 *   <li>甲类：全额纳入；乙类：先自付 selfPayRatio，其余纳入；丙类：全额自费；</li>
 *   <li>未映射或映射已停用的收费项目按丙类处理，并在明细里标记 unmapped，便于对账；</li>
 *   <li>纳入金额扣除起付线后，按参保报销比例计算统筹支付，并以**年度剩余封顶额度**与总额双重截断；</li>
 *   <li>个人自付 = 总费用 − 统筹支付；</li>
 *   <li>参保有效期按就诊业务日期校验，年度额度按清单确认时间归属自然年。</li>
 * </ol>
 */
@Service
public class InsuranceService {

    private static final BigDecimal ZERO = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);

    private final InsuranceRepository repo;
    private final PatientClient patientClient;
    private final ClinicalClient clinicalClient;
    private final ObjectMapper objectMapper;

    public InsuranceService(InsuranceRepository repo, PatientClient patientClient,
                            ClinicalClient clinicalClient, ObjectMapper objectMapper) {
        this.repo = repo;
        this.patientClient = patientClient;
        this.clinicalClient = clinicalClient;
        this.objectMapper = objectMapper;
    }

    // ---------------------------------------------------------------- 医保目录

    public List<InsuranceItem> listItems(String category, Boolean enabled, String q, Integer limit) {
        String normalized = (category == null || category.isBlank()) ? null : category.trim().toUpperCase();
        return repo.listItems(normalized, enabled, likePattern(q),
                limit == null || limit <= 0 ? 100 : Math.min(limit, 500));
    }

    @Transactional
    public InsuranceItem createItem(ItemReq req, String actor) {
        InsuranceItem item = toItem(req);
        repo.upsertItem(item);
        audit(actor, "ITEM_UPSERT", "INSURANCE_ITEM", item.insuranceCode(), Map.of(
                "insuranceCode", item.insuranceCode(), "payCategory", item.payCategory()));
        return repo.findItemByCode(item.insuranceCode())
                .orElseThrow(() -> new BusinessException(ErrorCode.INSURANCE_ITEM_NOT_FOUND));
    }

    @Transactional
    public ImportResult importItems(List<ItemReq> items, String actor) {
        List<String> codes = new ArrayList<>();
        for (ItemReq req : items) {
            InsuranceItem item = toItem(req);
            repo.upsertItem(item);
            codes.add(item.insuranceCode());
        }
        audit(actor, "ITEM_IMPORT", "INSURANCE_ITEM", "batch", Map.of(
                "count", codes.size(), "codes", codes.stream().limit(20).toList()));
        return new ImportResult(codes.size(), codes);
    }

    @Transactional
    public InsuranceItem toggleItem(UUID id, boolean enabled, long version, String actor) {
        InsuranceItem item = repo.findItemById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.INSURANCE_ITEM_NOT_FOUND));
        if (!repo.setItemEnabled(id, enabled, version)) {
            throw new BusinessException(ErrorCode.CONFLICT, "目录项目已被他人修改，请刷新后重试");
        }
        audit(actor, "ITEM_TOGGLE", "INSURANCE_ITEM", item.insuranceCode(),
                Map.of("enabled", enabled, "insuranceCode", item.insuranceCode()));
        return repo.findItemByCode(item.insuranceCode())
                .orElseThrow(() -> new BusinessException(ErrorCode.INSURANCE_ITEM_NOT_FOUND));
    }

    // ---------------------------------------------------------------- 院内映射

    public List<ChargeMapping> listMappings(String q, Integer limit) {
        return repo.listMappings(likePattern(q), limit == null || limit <= 0 ? 100 : Math.min(limit, 500));
    }

    @Transactional
    public ChargeMapping upsertMapping(String chargeItemCode, MappingReq req, String actor) {
        String code = chargeItemCode.trim();
        repo.findItemByCode(req.insuranceCode().trim())
                .filter(InsuranceItem::enabled)
                .orElseThrow(() -> new BusinessException(ErrorCode.INSURANCE_ITEM_NOT_FOUND,
                        "医保目录不存在或已停用：" + req.insuranceCode()));
        boolean enabled = req.enabled() == null || req.enabled();
        repo.upsertMapping(UUID.randomUUID(), code, req.insuranceCode().trim(), enabled);
        audit(actor, "MAPPING_UPSERT", "CHARGE_MAPPING", code, Map.of(
                "chargeItemCode", code, "insuranceCode", req.insuranceCode().trim(), "enabled", enabled));
        return repo.findMapping(code)
                .orElseThrow(() -> new BusinessException(ErrorCode.INSURANCE_ITEM_NOT_FOUND));
    }

    // ---------------------------------------------------------------- 参保登记

    @Transactional
    public Member registerMember(MemberReq req, String actor) {
        PatientClient.PatientRef patient = patientClient.requireActive(req.patientId());
        Optional<Member> existing = repo.findMemberByPatient(patient.id());
        Member member = new Member(existing.map(Member::id).orElseGet(UUID::randomUUID),
                patient.id(), patient.patientNo(), req.insuranceType().trim(), req.region().trim(),
                blankToNull(req.personCategory()),
                req.deductible() == null ? ZERO : money(req.deductible()),
                req.reimbursementRatio() == null ? new BigDecimal("0.8000") : req.reimbursementRatio(),
                req.cap() == null ? new BigDecimal("300000.00") : money(req.cap()),
                req.effectiveFrom() == null ? BusinessTime.today() : req.effectiveFrom(),
                req.effectiveTo(),
                req.status() == null ? Member.ACTIVE : req.status(),
                existing.map(Member::version).orElse(0L));
        long expectedVersion = existing.map(Member::version).orElse(0L);
        if (repo.upsertMember(member, expectedVersion).isEmpty()) {
            throw new BusinessException(ErrorCode.CONFLICT,
                    "参保信息已被他人修改，请刷新后重试");
        }
        audit(actor, "MEMBER_UPSERT", "MEMBER", patient.id().toString(), Map.of(
                "patientNo", patient.patientNo(), "insuranceType", member.insuranceType(),
                "region", member.region(), "ratio", member.reimbursementRatio().toPlainString()));
        return repo.findMemberByPatient(patient.id())
                .orElseThrow(() -> new BusinessException(ErrorCode.INSURANCE_MEMBER_NOT_FOUND));
    }

    public Member member(UUID patientId) {
        return repo.findMemberByPatient(patientId)
                .orElseThrow(() -> new BusinessException(ErrorCode.INSURANCE_MEMBER_NOT_FOUND,
                        "该患者尚未登记参保信息"));
    }

    // ---------------------------------------------------------------- 结算清单

    /**
     * 生成/重算结算清单。
     *
     * <p>同一就诊单只有一张清单：DRAFT 可重算（版本号 CAS），CONFIRMED 拒绝重算。
     *
     * <p>参保有效期按**就诊业务日期**（门诊挂号日 / 住院出院日）校验，而不是请求时刻的"今天"：
     * 跨年补结算、月末补录时才不会把当时有效的参保判成失效。封顶线按**年度累计**扣减
     * （已确认清单的统筹合计），单次测算只是年度额度里的一段。
     */
    @Transactional
    public BillView generate(UUID visitId, String actor) {
        ClinicalClient.ChargeSummary summary = clinicalClient.charges(visitId);
        if (summary.lines().isEmpty()) {
            throw new BusinessException(ErrorCode.INSURANCE_NO_CHARGE);
        }
        LocalDate serviceDate = summary.serviceDate() == null
                ? BusinessTime.today() : summary.serviceDate();
        Member member = repo.findMemberByPatient(summary.patientId())
                .filter(m -> m.activeOn(serviceDate))
                .orElseThrow(() -> new BusinessException(ErrorCode.INSURANCE_NOT_ENROLLED,
                        "该患者在就诊日期 " + serviceDate + " 无有效参保信息"));

        Optional<SettlementBill> existing = repo.lockBillByVisit(visitId);
        BigDecimal capUsed = repo.insuranceUsedInYear(summary.patientId(), BusinessTime.today(),
                existing.map(SettlementBill::id).orElse(null));
        Computation c = calculate(summary.lines(), resolver(), member, capUsed);
        if (c.lines().isEmpty()) {
            // 全部收费单已退费：没有可结算的明细，不能出零元清单
            throw new BusinessException(ErrorCode.INSURANCE_NO_CHARGE,
                    "该就诊单收费已全部退费，无可结算费用");
        }
        String detail = toJson(c.lines());

        SettlementBill bill;
        if (existing.isPresent()) {
            SettlementBill old = existing.get();
            if (SettlementBill.CONFIRMED.equals(old.status())) {
                throw new BusinessException(ErrorCode.INSURANCE_BILL_STATUS_INVALID,
                        "结算清单已确认，不能重算：" + old.billNo());
            }
            if (!repo.updateDraftBill(old.id(), c.total(), c.eligible(), c.deductible(),
                    c.insurance(), c.selfPay(), detail,
                    member.reimbursementRatio(), member.cap(), old.version())) {
                throw new BusinessException(ErrorCode.CONFLICT, "结算清单已被他人修改，请刷新后重试");
            }
            bill = repo.findBillById(old.id())
                    .orElseThrow(() -> new BusinessException(ErrorCode.INSURANCE_BILL_NOT_FOUND));
        } else {
            SettlementBill created = new SettlementBill(UUID.randomUUID(), repo.nextBillNo(),
                    visitId, summary.patientId(), summary.patientNo(), member.id(),
                    c.total(), c.eligible(), c.deductible(), c.insurance(), c.selfPay(),
                    SettlementBill.DRAFT, detail, actor, null, null,
                    null, null, null, member.reimbursementRatio(), member.cap(), 0L, null);
            repo.insertBill(created);
            bill = repo.findBillById(created.id())
                    .orElseThrow(() -> new BusinessException(ErrorCode.INSURANCE_BILL_NOT_FOUND));
        }
        audit(actor, "BILL_GENERATE", "SETTLEMENT_BILL", bill.id().toString(), Map.of(
                "billNo", bill.billNo(), "visitId", visitId.toString(),
                "serviceDate", serviceDate.toString(),
                "total", c.total().toPlainString(), "insurance", c.insurance().toPlainString(),
                "selfPay", c.selfPay().toPlainString(), "lines", c.lines().size(),
                "capUsed", c.capUsed().toPlainString(),
                "capRemaining", c.capRemaining() == null ? "UNLIMITED" : c.capRemaining().toPlainString(),
                "skippedRefunded", c.skippedRefunded()));
        return new BillView(bill, c.lines(), c.capUsed(), c.capRemaining());
    }

    @Transactional
    public BillView confirm(UUID billId, String actor) {
        SettlementBill bill = repo.lockBillById(billId)
                .orElseThrow(() -> new BusinessException(ErrorCode.INSURANCE_BILL_NOT_FOUND));
        if (!SettlementBill.DRAFT.equals(bill.status())) {
            throw new BusinessException(ErrorCode.INSURANCE_BILL_STATUS_INVALID,
                    "只有草稿状态的结算清单可以确认，当前：" + bill.status());
        }
        // 确认前复核费用闭合：清单生成后临床仍可能继续计费或退费，金额不一致不得入档
        verifyChargesUnchanged(bill);
        if (!repo.confirmBill(billId, actor, bill.version())) {
            throw new BusinessException(ErrorCode.CONFLICT, "结算清单状态已变化，请刷新后重试");
        }
        audit(actor, "BILL_CONFIRM", "SETTLEMENT_BILL", billId.toString(), Map.of(
                "billNo", bill.billNo(), "total", bill.totalAmount().toPlainString()));
        return view(billId);
    }

    /**
     * 作废（红冲）：CONFIRMED → VOIDED，单向不可逆。
     *
     * <p>作废后该就诊可以重新生成清单（有效清单唯一索引排除 VOIDED），
     * 原单保留全部金额、确认人与作废原因，供对账与审计追溯。
     */
    @Transactional
    public BillView voidBill(UUID billId, String reason, String actor) {
        SettlementBill bill = repo.lockBillById(billId)
                .orElseThrow(() -> new BusinessException(ErrorCode.INSURANCE_BILL_NOT_FOUND));
        if (!SettlementBill.CONFIRMED.equals(bill.status())) {
            throw new BusinessException(ErrorCode.INSURANCE_BILL_STATUS_INVALID,
                    "只有已确认的结算清单可以作废，当前：" + bill.status());
        }
        String normalized = reason == null ? "" : reason.trim();
        if (!repo.voidBill(billId, normalized, actor, bill.version())) {
            throw new BusinessException(ErrorCode.CONFLICT, "结算清单状态已变化，请刷新后重试");
        }
        audit(actor, "BILL_VOID", "SETTLEMENT_BILL", billId.toString(), Map.of(
                "billNo", bill.billNo(), "reason", normalized,
                "total", bill.totalAmount().toPlainString(),
                "insurance", bill.insuranceAmount().toPlainString()));
        return view(billId);
    }

    /**
     * 按就诊对账：清单金额 vs 当前收费重算结果。
     *
     * <p>用清单生成时的待遇快照重算，避免参保参数事后调整造成假差异；
     * 历史清单无快照时退回当前参保参数，参保也查不到则直接按 0 待遇无法比对（matched=false）。
     */
    public ReconcileView reconcile(UUID visitId) {
        SettlementBill bill = repo.findBillByVisit(visitId)
                .orElseThrow(() -> new BusinessException(ErrorCode.INSURANCE_BILL_NOT_FOUND,
                        "该就诊单没有有效结算清单"));
        Optional<Member> snapshot = snapshotMember(bill);
        Computation fresh = snapshot.map(m -> recompute(bill, m)).orElse(null);
        boolean matched = fresh != null
                && fresh.total().compareTo(bill.totalAmount()) == 0
                && fresh.insurance().compareTo(bill.insuranceAmount()) == 0;
        return new ReconcileView(visitId, bill.billNo(), bill.status(), matched,
                bill.totalAmount(), fresh == null ? null : fresh.total(),
                bill.insuranceAmount(), fresh == null ? null : fresh.insurance(),
                bill.selfPayAmount(), fresh == null ? null : fresh.selfPay(),
                parse(bill.detail()).size(), fresh == null ? -1 : fresh.lines().size());
    }

    /**
     * 用指定待遇重算某张清单的当前值。
     *
     * <p>年度封顶累计必须排除清单自身：已确认清单重算时若把自己也算进已用额度，
     * 复核/对账会永远对不上。
     */
    private Computation recompute(SettlementBill bill, Member member) {
        return calculate(clinicalClient.charges(bill.visitId()).lines(), resolver(), member,
                repo.insuranceUsedInYear(bill.patientId(), BusinessTime.today(), bill.id()));
    }

    /** 确认前复核：按生成时的待遇快照重算，费用或口径变了就拒绝确认。 */
    private void verifyChargesUnchanged(SettlementBill bill) {
        Optional<Member> snapshot = snapshotMember(bill);
        if (snapshot.isEmpty()) {
            // 历史清单 + 参保已删除：无法复核，不阻断（但要靠对账接口人工核对）
            return;
        }
        Computation fresh = recompute(bill, snapshot.get());
        boolean changed = fresh.total().compareTo(bill.totalAmount()) != 0
                || fresh.insurance().compareTo(bill.insuranceAmount()) != 0;
        if (changed) {
            throw new BusinessException(ErrorCode.INSURANCE_BILL_STATUS_INVALID,
                    "费用或参保待遇已变化（清单实收 " + bill.totalAmount().toPlainString()
                            + " / 当前 " + fresh.total().toPlainString()
                            + "，含年度封顶累计），请作废后重新生成清单");
        }
    }

    /** 清单生成时的待遇快照；缺快照时退回当前参保（历史数据兼容）。 */
    private Optional<Member> snapshotMember(SettlementBill bill) {
        BigDecimal ratio = bill.snapshotRatio();
        BigDecimal cap = bill.snapshotCap();
        if (ratio == null) {
            return bill.memberId() == null ? Optional.empty() : repo.findMemberById(bill.memberId());
        }
        return Optional.of(new Member(bill.memberId(), bill.patientId(), bill.patientNo(),
                "-", "-", null,
                bill.deductible() == null ? ZERO : bill.deductible(),
                ratio, cap, null, null, Member.ACTIVE, 0L));
    }

    public BillView detail(UUID billId) {
        return view(billId);
    }

    public BillView byVisit(UUID visitId) {
        SettlementBill bill = repo.findBillByVisit(visitId)
                .orElseThrow(() -> new BusinessException(ErrorCode.INSURANCE_BILL_NOT_FOUND,
                        "该就诊单尚未生成结算清单"));
        return new BillView(bill, parse(bill.detail()), null, null);
    }

    public List<SettlementBill> list(UUID patientId, String status, Integer limit) {
        String normalized = (status == null || status.isBlank()) ? null : status.trim().toUpperCase();
        return repo.listBills(patientId, normalized, limit == null || limit <= 0 ? 50 : Math.min(limit, 200));
    }

    private BillView view(UUID billId) {
        SettlementBill bill = repo.findBillById(billId)
                .orElseThrow(() -> new BusinessException(ErrorCode.INSURANCE_BILL_NOT_FOUND));
        return new BillView(bill, parse(bill.detail()), null, null);
    }

    // ---------------------------------------------------------------- 计算引擎

    /**
     * 计算结果（金额保留 2 位小数，HALF_UP）。
     *
     * @param skippedRefunded 被剔除的已退费收费行数
     * @param capUsed         本年度已用统筹（同参保人已确认清单合计）
     * @param capRemaining    本次测算前的剩余封顶额度；为空表示未配置封顶（不限额）
     */
    record Computation(BigDecimal total, BigDecimal eligible, BigDecimal deductible,
                       BigDecimal insurance, BigDecimal selfPay, int skippedRefunded,
                       BigDecimal capUsed, BigDecimal capRemaining, List<BillLine> lines) {
    }

    private static final String STATUS_PAID = "PAID";
    private static final String STATUS_UNPAID = "UNPAID";
    private static final String STATUS_REFUNDED = "REFUNDED";

    /**
     * 本地测算：目录支付类别 + 参保待遇 + 支付限价 + 年度封顶累计。
     *
     * <p>口径（有代码即口径，不允许"带出来但不用"）：
     * <ol>
     *   <li>收费单状态：REFUNDED 整单剔除（不进入清单）；非 PAID/UNPAID 一律 fail-closed；</li>
     *   <li>甲类全额纳入；乙类先自付 selfPayRatio；丙类/未映射/目录停用全额自费；</li>
     *   <li>乙类等有支付限价的项目：限价内部分参与纳入计算，超限部分全额自付（overLimitAmount）；</li>
     *   <li>纳入金额扣除起付线后按报销比例计算统筹支付，并以**年度剩余封顶额度**与总额双重截断；</li>
     *   <li>负数金额直接拒绝（退费走红冲单，不进入本地测算）。</li>
     * </ol>
     */
    static Computation calculate(List<ClinicalClient.ChargeLine> lines,
                                 Function<String, InsuranceItem> resolver,
                                 Member member) {
        return calculate(lines, resolver, member, ZERO);
    }

    /**
     * 带年度累计额度的测算。
     *
     * @param capUsed 本年度已用统筹；封顶线按"已用 + 本次"判断，超出剩额度部分转个人自付
     */
    static Computation calculate(List<ClinicalClient.ChargeLine> lines,
                                 Function<String, InsuranceItem> resolver,
                                 Member member, BigDecimal capUsed) {
        List<BillLine> detail = new ArrayList<>(lines.size());
        BigDecimal total = ZERO;
        BigDecimal eligible = ZERO;
        int skippedRefunded = 0;
        for (ClinicalClient.ChargeLine line : lines) {
            String rawStatus = line.chargeStatus() == null ? "" : line.chargeStatus().trim().toUpperCase();
            if (STATUS_REFUNDED.equals(rawStatus)) {
                skippedRefunded++;
                continue;
            }
            if (!STATUS_PAID.equals(rawStatus) && !STATUS_UNPAID.equals(rawStatus)) {
                throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                        "收费单状态不可用于医保结算：" + line.chargeNo() + "[" + line.chargeStatus() + "]");
            }
            BigDecimal amount = money(line.amount());
            if (amount.signum() < 0) {
                throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                        "收费明细存在负数金额，请走红冲流程：" + line.chargeNo()
                                + "/" + line.itemCode() + " = " + amount.toPlainString());
            }

            InsuranceItem item = resolver.apply(line.itemCode());
            String payCategory = item == null ? InsuranceItem.PAY_C : item.payCategory();
            BigDecimal selfPayRatio = switch (payCategory) {
                case InsuranceItem.PAY_A -> BigDecimal.ZERO;
                case InsuranceItem.PAY_B -> item.selfPayRatio() == null
                        ? BigDecimal.ZERO : item.selfPayRatio();
                default -> BigDecimal.ONE;
            };
            // 支付限价：超出部分全额自费，只有限价内金额参与纳入计算
            BigDecimal billable = amount;
            BigDecimal overLimit = ZERO;
            if (item != null && item.priceLimit() != null
                    && amount.compareTo(item.priceLimit()) > 0) {
                billable = money(item.priceLimit());
                overLimit = money(amount.subtract(billable));
            }
            BigDecimal lineEligible = billable.multiply(BigDecimal.ONE.subtract(selfPayRatio))
                    .setScale(2, RoundingMode.HALF_UP);
            total = total.add(amount);
            eligible = eligible.add(lineEligible);
            detail.add(new BillLine(line.chargeNo(), line.itemCode(), line.itemName(),
                    line.category(), rawStatus, amount,
                    item == null ? null : item.insuranceCode(), payCategory, selfPayRatio,
                    overLimit, lineEligible, item == null));
        }
        total = money(total);
        eligible = money(eligible);
        BigDecimal deductible = money(member.deductible() == null ? ZERO : member.deductible());
        BigDecimal base = eligible.subtract(deductible).max(ZERO);
        BigDecimal ratio = member.reimbursementRatio() == null
                ? BigDecimal.ZERO : member.reimbursementRatio();
        BigDecimal insurance = base.multiply(ratio).setScale(2, RoundingMode.HALF_UP);
        BigDecimal cap = money(member.cap() == null ? ZERO : member.cap());
        BigDecimal used = money(capUsed == null ? ZERO : capUsed);
        // 封顶为 0 或负数视为未配置（不限额）；配置后按"年度剩余额度"截断
        BigDecimal remaining = cap.signum() > 0 ? cap.subtract(used).max(ZERO) : null;
        if (remaining != null && insurance.compareTo(remaining) > 0) {
            insurance = remaining;
        }
        if (insurance.compareTo(total) > 0) {
            insurance = total;
        }
        BigDecimal selfPay = money(total.subtract(insurance));
        return new Computation(total, eligible, deductible, insurance, selfPay, skippedRefunded,
                used, remaining, detail);
    }

    private Function<String, InsuranceItem> resolver() {
        // 同一次生成里同一收费项目只查一次
        Map<String, InsuranceItem> cache = new LinkedHashMap<>();
        Map<String, Boolean> resolved = new LinkedHashMap<>();
        return code -> {
            if (resolved.containsKey(code)) {
                return cache.get(code);
            }
            InsuranceItem item = repo.findMapping(code)
                    .filter(ChargeMapping::enabled)
                    .flatMap(m -> repo.findItemByCode(m.insuranceCode()))
                    .filter(InsuranceItem::enabled)
                    .orElse(null);
            cache.put(code, item);
            resolved.put(code, Boolean.TRUE);
            return item;
        };
    }

    private InsuranceItem toItem(ItemReq req) {
        return new InsuranceItem(UUID.randomUUID(), req.insuranceCode().trim(), req.name().trim(),
                req.category(), req.payCategory(),
                req.selfPayRatio() == null ? BigDecimal.ZERO : req.selfPayRatio(),
                req.priceLimit() == null ? null : money(req.priceLimit()),
                req.enabled() == null || req.enabled(), 0L);
    }

    private static BigDecimal money(BigDecimal v) {
        return v == null ? ZERO : v.setScale(2, RoundingMode.HALF_UP);
    }

    private static String likePattern(String q) {
        if (q == null || q.isBlank()) {
            return null;
        }
        String escaped = q.trim().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
        return "%" + escaped + "%";
    }

    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s.trim();
    }

    private String toJson(List<BillLine> lines) {
        try {
            return objectMapper.writeValueAsString(lines);
        } catch (JacksonException e) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "结算明细无法序列化");
        }
    }

    private List<BillLine> parse(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            BillLine[] arr = objectMapper.readValue(json, BillLine[].class);
            return List.of(arr);
        } catch (JacksonException e) {
            return List.of();
        }
    }

    private void audit(String actor, String action, String targetType, String targetId,
                       Map<String, Object> detail) {
        try {
            repo.logChange(actor, action, targetType, targetId,
                    objectMapper.writeValueAsString(new LinkedHashMap<>(detail)));
        } catch (JacksonException e) {
            repo.logChange(actor, action, targetType, targetId, "{\"_detail_serialize_failed\":true}");
        }
    }
}
