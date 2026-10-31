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
 *   <li>纳入金额扣除起付线后，按参保报销比例计算统筹支付，并以封顶线截断；</li>
 *   <li>个人自付 = 总费用 − 统筹支付。</li>
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
        repo.upsertMember(member);
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
     */
    @Transactional
    public BillView generate(UUID visitId, String actor) {
        ClinicalClient.ChargeSummary summary = clinicalClient.charges(visitId);
        if (summary.lines().isEmpty()) {
            throw new BusinessException(ErrorCode.INSURANCE_NO_CHARGE);
        }
        Member member = repo.findMemberByPatient(summary.patientId())
                .filter(m -> m.activeOn(BusinessTime.today()))
                .orElseThrow(() -> new BusinessException(ErrorCode.INSURANCE_NOT_ENROLLED));

        Computation c = calculate(summary.lines(), resolver(), member);
        String detail = toJson(c.lines());

        Optional<SettlementBill> existing = repo.lockBillByVisit(visitId);
        SettlementBill bill;
        if (existing.isPresent()) {
            SettlementBill old = existing.get();
            if (SettlementBill.CONFIRMED.equals(old.status())) {
                throw new BusinessException(ErrorCode.INSURANCE_BILL_STATUS_INVALID,
                        "结算清单已确认，不能重算：" + old.billNo());
            }
            if (!repo.updateDraftBill(old.id(), c.total(), c.eligible(), c.deductible(),
                    c.insurance(), c.selfPay(), detail, old.version())) {
                throw new BusinessException(ErrorCode.CONFLICT, "结算清单已被他人修改，请刷新后重试");
            }
            bill = repo.findBillById(old.id())
                    .orElseThrow(() -> new BusinessException(ErrorCode.INSURANCE_BILL_NOT_FOUND));
        } else {
            SettlementBill created = new SettlementBill(UUID.randomUUID(), repo.nextBillNo(),
                    visitId, summary.patientId(), summary.patientNo(), member.id(),
                    c.total(), c.eligible(), c.deductible(), c.insurance(), c.selfPay(),
                    SettlementBill.DRAFT, detail, actor, null, null, 0L, null);
            repo.insertBill(created);
            bill = repo.findBillById(created.id())
                    .orElseThrow(() -> new BusinessException(ErrorCode.INSURANCE_BILL_NOT_FOUND));
        }
        audit(actor, "BILL_GENERATE", "SETTLEMENT_BILL", bill.id().toString(), Map.of(
                "billNo", bill.billNo(), "visitId", visitId.toString(),
                "total", c.total().toPlainString(), "insurance", c.insurance().toPlainString(),
                "selfPay", c.selfPay().toPlainString()));
        return new BillView(bill, c.lines());
    }

    @Transactional
    public BillView confirm(UUID billId, String actor) {
        SettlementBill bill = repo.lockBillById(billId)
                .orElseThrow(() -> new BusinessException(ErrorCode.INSURANCE_BILL_NOT_FOUND));
        if (!SettlementBill.DRAFT.equals(bill.status())) {
            throw new BusinessException(ErrorCode.INSURANCE_BILL_STATUS_INVALID,
                    "只有草稿状态的结算清单可以确认，当前：" + bill.status());
        }
        if (!repo.confirmBill(billId, actor, bill.version())) {
            throw new BusinessException(ErrorCode.CONFLICT, "结算清单状态已变化，请刷新后重试");
        }
        audit(actor, "BILL_CONFIRM", "SETTLEMENT_BILL", billId.toString(), Map.of(
                "billNo", bill.billNo(), "total", bill.totalAmount().toPlainString()));
        return view(billId);
    }

    public BillView detail(UUID billId) {
        return view(billId);
    }

    public BillView byVisit(UUID visitId) {
        SettlementBill bill = repo.findBillByVisit(visitId)
                .orElseThrow(() -> new BusinessException(ErrorCode.INSURANCE_BILL_NOT_FOUND,
                        "该就诊单尚未生成结算清单"));
        return new BillView(bill, parse(bill.detail()));
    }

    public List<SettlementBill> list(UUID patientId, String status, Integer limit) {
        String normalized = (status == null || status.isBlank()) ? null : status.trim().toUpperCase();
        return repo.listBills(patientId, normalized, limit == null || limit <= 0 ? 50 : Math.min(limit, 200));
    }

    private BillView view(UUID billId) {
        SettlementBill bill = repo.findBillById(billId)
                .orElseThrow(() -> new BusinessException(ErrorCode.INSURANCE_BILL_NOT_FOUND));
        return new BillView(bill, parse(bill.detail()));
    }

    // ---------------------------------------------------------------- 计算引擎

    /** 计算结果（金额保留 2 位小数，HALF_UP）。 */
    record Computation(BigDecimal total, BigDecimal eligible, BigDecimal deductible,
                       BigDecimal insurance, BigDecimal selfPay, List<BillLine> lines) {
    }

    /**
     * 本地测算：目录支付类别 + 参保待遇。
     *
     * @param resolver 收费项目编码 → 医保目录项目（未映射/停用返回 null，按丙类处理）
     */
    static Computation calculate(List<ClinicalClient.ChargeLine> lines,
                                 Function<String, InsuranceItem> resolver,
                                 Member member) {
        List<BillLine> detail = new ArrayList<>(lines.size());
        BigDecimal total = ZERO;
        BigDecimal eligible = ZERO;
        for (ClinicalClient.ChargeLine line : lines) {
            BigDecimal amount = money(line.amount());
            InsuranceItem item = resolver.apply(line.itemCode());
            String payCategory = item == null ? InsuranceItem.PAY_C : item.payCategory();
            BigDecimal selfPayRatio = switch (payCategory) {
                case InsuranceItem.PAY_A -> BigDecimal.ZERO;
                case InsuranceItem.PAY_B -> item.selfPayRatio() == null
                        ? BigDecimal.ZERO : item.selfPayRatio();
                default -> BigDecimal.ONE;
            };
            BigDecimal lineEligible = amount.multiply(BigDecimal.ONE.subtract(selfPayRatio))
                    .setScale(2, RoundingMode.HALF_UP);
            total = total.add(amount);
            eligible = eligible.add(lineEligible);
            detail.add(new BillLine(line.chargeNo(), line.itemCode(), line.itemName(),
                    line.category(), amount,
                    item == null ? null : item.insuranceCode(), payCategory, selfPayRatio,
                    lineEligible, item == null));
        }
        total = money(total);
        eligible = money(eligible);
        BigDecimal deductible = money(member.deductible() == null ? ZERO : member.deductible());
        BigDecimal base = eligible.subtract(deductible).max(ZERO);
        BigDecimal ratio = member.reimbursementRatio() == null
                ? BigDecimal.ZERO : member.reimbursementRatio();
        BigDecimal insurance = base.multiply(ratio).setScale(2, RoundingMode.HALF_UP);
        BigDecimal cap = money(member.cap() == null ? ZERO : member.cap());
        if (cap.signum() > 0 && insurance.compareTo(cap) > 0) {
            insurance = cap;
        }
        if (insurance.compareTo(total) > 0) {
            insurance = total;
        }
        BigDecimal selfPay = money(total.subtract(insurance));
        return new Computation(total, eligible, deductible, insurance, selfPay, detail);
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
