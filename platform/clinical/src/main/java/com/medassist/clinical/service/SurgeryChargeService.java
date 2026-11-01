package com.medassist.clinical.service;

import com.medassist.clinical.domain.ClinicalModel.Charge;
import com.medassist.clinical.domain.ClinicalModel.ChargeItemDef;
import com.medassist.clinical.domain.ClinicalModel.SurgeryCase;
import com.medassist.clinical.domain.ClinicalModel.SurgeryChargeRule;
import com.medassist.clinical.domain.ClinicalModel.Visit;
import com.medassist.clinical.repo.ClinicalRepository;
import com.medassist.clinical.web.SurgeryChargeDtos.ChargeLineView;
import com.medassist.clinical.web.SurgeryChargeDtos.SurgeryChargePreview;
import com.medassist.clinical.web.SurgeryChargeDtos.SurgeryChargeResult;
import com.medassist.common.api.ErrorCode;
import com.medassist.common.exception.BusinessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 术中收费打包：手术费 + 麻醉费 + 耗材一次计费。
 *
 * <p>三条不变量：
 * <ol>
 *   <li><b>只能在手术完成后计费</b>：未完成的手术随时可能取消，先收费再取消会留下无法解释的收入；</li>
 *   <li><b>价格只取物价字典</b>：规则表只说明"计哪一项、计几份"，金额一律
 *       {@code md_charge_item.price × quantity}，避免规则表里出现第二个价；</li>
 *   <li><b>幂等</b>：幂等键 {@code source_key = SURGERY:{caseId}:{itemCode}} 有唯一索引，
 *       重复点击/重试只会返回 {@code addedAmount=0}，绝不会多收一次。</li>
 * </ol>
 *
 * <p>并发：先对就诊行加锁（{@code lockVisit}），同一就诊的打包计费、结算、按日计费串行化；
 * 收费单金额用增量累加 + 版本号 CAS，不会覆盖别人刚记的账。
 */
@Service
public class SurgeryChargeService {

    private final ClinicalRepository repo;

    public SurgeryChargeService(ClinicalRepository repo) {
        this.repo = repo;
    }

    /** 打包预览：不写库，供护士核对"这台手术应收哪些费用"。 */
    public SurgeryChargePreview preview(UUID caseId) {
        SurgeryCase surgery = requireCase(caseId);
        List<ChargeLineView> lines = resolveLines(surgery);
        Set<String> charged = new HashSet<>(repo.listChargedSurgeryItemCodes(caseId));
        List<ChargeLineView> views = lines.stream()
                .map(l -> withCharged(l, charged.contains(l.itemCode())))
                .toList();
        List<ChargeLineView> pending = views.stream().filter(v -> !v.charged()).toList();
        return new SurgeryChargePreview(caseId, surgery.status(), views, sum(views),
                sum(pending), pending.isEmpty());
    }

    /**
     * 打包计费：把规则展开的费用行写入当前未结收费单。
     *
     * <p>已全部计费时直接返回 {@code addedAmount=0}（HTTP 200），
     * 既不会续开空收费单，也不会重复累加金额。
     */
    @Transactional
    public SurgeryChargeResult charge(UUID caseId, String actor) {
        SurgeryCase surgery = requireCase(caseId);
        if (!SurgeryCase.COMPLETED.equals(surgery.status())) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "只有已完成的手术可以打包计费，当前状态：" + surgery.status());
        }
        Visit visit = repo.lockVisit(surgery.visitId())
                .orElseThrow(() -> new BusinessException(ErrorCode.VISIT_NOT_FOUND));
        if (Visit.CANCELLED.equals(visit.status())) {
            throw new BusinessException(ErrorCode.VISIT_STATUS_INVALID, "该就诊已取消，不能计费");
        }
        List<ChargeLineView> lines = resolveLines(surgery);
        if (lines.isEmpty()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "未匹配到任何收费标准，请检查手术类别、麻醉方式与打包规则");
        }

        Set<String> charged = new HashSet<>(repo.listChargedSurgeryItemCodes(caseId));
        List<ChargeLineView> pending = lines.stream()
                .filter(l -> !charged.contains(l.itemCode()))
                .toList();
        if (pending.isEmpty()) {
            Charge latest = repo.findLatestCharge(visit.id()).orElse(null);
            audit(actor, "SURGERY_CHARGE_IDEMPOTENT", caseId, surgery, BigDecimal.ZERO,
                    lines.size(), 0);
            return new SurgeryChargeResult(caseId,
                    latest == null ? null : latest.id(),
                    latest == null ? null : latest.chargeNo(),
                    BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP),
                    latest == null ? BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP)
                            : latest.totalAmount(),
                    List.of());
        }

        Charge charge = repo.lockOpenCharge(visit.id()).orElse(null);
        if (charge == null) {
            // 中途结算后继续住院：续开一张收费单（数据库保证每就诊最多一张 UNPAID）
            charge = new Charge(UUID.randomUUID(), repo.nextChargeNo(), visit.id(),
                    BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP), Charge.UNPAID,
                    null, null, 0L);
            repo.insertCharge(charge);
        }

        BigDecimal added = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        List<ChargeLineView> addedLines = new ArrayList<>();
        for (ChargeLineView line : pending) {
            boolean inserted = repo.insertChargeItem(UUID.randomUUID(), charge.id(), null,
                    sourceKey(caseId, line.itemCode()), line.itemCode(), line.itemName(),
                    line.category(), line.amount());
            if (inserted) {
                added = added.add(line.amount());
                addedLines.add(withCharged(line, true));
            }
        }
        if (added.signum() > 0 && !repo.addChargeAmount(charge.id(), added, charge.version())) {
            throw new BusinessException(ErrorCode.CONFLICT, "收费单状态已变化，请刷新后重试");
        }
        Charge latest = repo.findCharge(charge.id()).orElse(charge);
        audit(actor, "SURGERY_CHARGE", caseId, surgery, added, lines.size(), addedLines.size());
        return new SurgeryChargeResult(caseId, latest.id(), latest.chargeNo(), added,
                latest.totalAmount(), addedLines);
    }

    // ---------------------------------------------------------------- 规则展开

    /**
     * 规则展开顺序：术式类别（无则 DEFAULT 兜底）→ 麻醉方式（NONE/未填则跳过）→ 耗材。
     *
     * <p>同一收费项目只保留一条（幂等键以 itemCode 为粒度），
     * 避免两条规则命中同一项目时既算手术费又算耗材。
     */
    private List<ChargeLineView> resolveLines(SurgeryCase surgery) {
        Map<String, ChargeLineView> lines = new LinkedHashMap<>();
        String category = repo.findOperation(surgery.operationCode())
                .map(c -> c.category()).orElse(null);
        List<SurgeryChargeRule> operationRules = category == null
                ? List.of()
                : repo.listSurgeryChargeRules(SurgeryChargeRule.OPERATION_CATEGORY, category);
        if (operationRules.isEmpty()) {
            operationRules = repo.listSurgeryChargeRules(SurgeryChargeRule.OPERATION_CATEGORY,
                    SurgeryChargeRule.DEFAULT_MATCH);
        }
        appendRules(lines, operationRules, "手术费");
        if (surgery.anesthesiaType() != null
                && !"NONE".equalsIgnoreCase(surgery.anesthesiaType())) {
            appendRules(lines, repo.listSurgeryChargeRules(
                    SurgeryChargeRule.ANESTHESIA_METHOD, surgery.anesthesiaType()), "麻醉费");
        }
        appendRules(lines, repo.listSurgeryChargeRules(SurgeryChargeRule.MATERIAL, null), "耗材");
        return new ArrayList<>(lines.values());
    }

    private void appendRules(Map<String, ChargeLineView> target, List<SurgeryChargeRule> rules,
                             String label) {
        for (SurgeryChargeRule rule : rules) {
            if (target.containsKey(rule.itemCode())) {
                continue;
            }
            ChargeItemDef item = repo.findChargeItem(rule.itemCode())
                    .orElseThrow(() -> new BusinessException(ErrorCode.VALIDATION_FAILED,
                            "打包规则引用的收费项目不存在：" + rule.itemCode() + "（" + label
                                    + "），请先维护物价字典"));
            if (!item.enabled()) {
                throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                        "打包规则引用的收费项目已停用：" + rule.itemCode() + "（" + label + "）");
            }
            BigDecimal quantity = rule.quantity() == null ? BigDecimal.ONE : rule.quantity();
            BigDecimal unitPrice = item.price().setScale(2, RoundingMode.HALF_UP);
            target.put(rule.itemCode(), new ChargeLineView(item.itemCode(), item.name(),
                    item.category(), quantity, unitPrice,
                    unitPrice.multiply(quantity).setScale(2, RoundingMode.HALF_UP), false));
        }
    }

    // ---------------------------------------------------------------- 内部工具

    private SurgeryCase requireCase(UUID caseId) {
        return repo.findSurgeryCase(caseId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "未找到该手术记录"));
    }

    private static String sourceKey(UUID caseId, String itemCode) {
        return "SURGERY:" + caseId + ":" + itemCode;
    }

    private static ChargeLineView withCharged(ChargeLineView line, boolean charged) {
        return new ChargeLineView(line.itemCode(), line.itemName(), line.category(),
                line.quantity(), line.unitPrice(), line.amount(), charged);
    }

    private static BigDecimal sum(List<ChargeLineView> lines) {
        return lines.stream().map(ChargeLineView::amount)
                .reduce(BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP), BigDecimal::add);
    }

    private void audit(String actor, String action, UUID caseId, SurgeryCase surgery,
                       BigDecimal added, int lineCount, int addedCount) {
        repo.logChange(actor, action, "SURGERY_CASE", caseId.toString(),
                "{\"caseNo\":\"" + surgery.caseNo() + "\",\"lineCount\":" + lineCount
                        + ",\"addedCount\":" + addedCount
                        + ",\"addedAmount\":\"" + added.toPlainString() + "\"}");
    }
}
