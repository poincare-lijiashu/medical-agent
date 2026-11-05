package com.medassist.clinical.service;

import com.medassist.clinical.domain.ClinicalModel.Charge;
import com.medassist.clinical.domain.ClinicalModel.PharmacyChargeLink;
import com.medassist.clinical.domain.ClinicalModel.Visit;
import com.medassist.clinical.repo.ClinicalRepository;
import com.medassist.clinical.web.PharmacyChargeDtos.ChargeLine;
import com.medassist.clinical.web.PharmacyChargeDtos.ChargeResult;
import com.medassist.clinical.web.PharmacyChargeDtos.RefundView;
import com.medassist.common.api.ErrorCode;
import com.medassist.common.exception.BusinessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 药房发药费用联动（N2 收尾）：发药计费 + 退药冲减。
 *
 * <p>两条红线：
 * <ul>
 *   <li><b>不重复计费</b>：药房侧只有 {@code billing_mode=DISPENSE} 的处方才调这里；
 *       幂等键是发药明细 id（{@code DISPENSE:{dispenseItemId}}），重放不会多记一笔。</li>
 *   <li><b>不伪造退款</b>：收费单已结算时**不改账**，只登记待财务退费；
 *       财务实际退款后调用 complete 接口核销，金额链路可追溯。</li>
 * </ul>
 *
 * <p>退药用"累计已退金额"而不是"本次退多少"：网络重试、事务回滚后重放都能收敛到同一状态
 * （单调递增校验，减小直接 409）。
 */
@Service
public class PharmacyChargeService {

    /** 单条药房费用上限（防脏数据把收费单写成天文数字）。 */
    private static final BigDecimal MAX_LINE_AMOUNT = new BigDecimal("1000000.00");

    private final ClinicalRepository repo;
    private final ObjectMapper objectMapper;

    public PharmacyChargeService(ClinicalRepository repo, ObjectMapper objectMapper) {
        this.repo = repo;
        this.objectMapper = objectMapper;
    }

    // ---------------------------------------------------------------- 发药计费

    @Transactional
    public ChargeResult charge(UUID visitId, UUID dispenseId, List<ChargeLine> items, String actor) {
        if (visitId == null || items == null || items.isEmpty()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "发药计费缺少就诊或费用明细");
        }
        Visit visit = repo.lockVisit(visitId)
                .orElseThrow(() -> new BusinessException(ErrorCode.VISIT_NOT_FOUND));
        if (Visit.CANCELLED.equals(visit.status())) {
            throw new BusinessException(ErrorCode.VISIT_STATUS_INVALID, "该就诊已取消，不能补记药房费用");
        }
        if (!visit.active()) {
            throw new BusinessException(ErrorCode.VISIT_STATUS_INVALID, "就诊已结束，不能补记药房费用");
        }
        validateLines(items);

        Charge charge = repo.lockOpenCharge(visitId).orElse(null);
        BigDecimal added = BigDecimal.ZERO;
        int created = 0;
        for (ChargeLine line : items) {
            if (repo.findPharmacyCharge(line.dispenseItemId()).isPresent()) {
                continue; // 已计费（幂等重放/并发重试）
            }
            if (charge == null) {
                charge = newCharge(visit);
            }
            UUID chargeItemId = UUID.randomUUID();
            boolean inserted = repo.insertChargeItem(chargeItemId, charge.id(), null,
                    "DISPENSE:" + line.dispenseItemId(), line.drugCode(), line.drugName(),
                    "DRUG", line.amount());
            if (!inserted) {
                continue; // source_key 已被并发写入
            }
            repo.insertPharmacyCharge(line.dispenseItemId(), visitId, charge.id(), chargeItemId,
                    line.drugCode(), line.drugName(), line.amount());
            added = added.add(line.amount());
            created++;
        }
        if (charge == null) {
            // 全部明细都已计费（幂等重放/并发重试）：charge 从未被创建，直接返回既有收费单。
            // 原实现会继续走到 findCharge(null.id()) 抛 NPE，把"可安全重放"变成 500，
            // 而药房侧的网络重试恰恰最容易命中这条路径。
            Charge existing = repo.findLatestCharge(visitId)
                    .orElseThrow(() -> new BusinessException(ErrorCode.CONFLICT,
                            "该就诊暂无收费单，无法返回药房补记结果"));
            return new ChargeResult(existing.id(), existing.chargeNo(), 0, BigDecimal.ZERO,
                    existing.totalAmount());
        }
        if (added.signum() > 0 && !repo.addChargeAmount(charge.id(), added, charge.version())) {
            throw new BusinessException(ErrorCode.CONFLICT, "收费单状态已变化，请刷新后重试");
        }
        Charge latest = repo.findCharge(charge.id()).orElseThrow();
        if (created > 0) {
            audit(actor, "PHARMACY_CHARGE", visitId.toString(), Map.of(
                    "visitNo", visit.visitNo(), "dispenseId", dispenseId == null ? "" : dispenseId.toString(),
                    "itemCount", created, "addedAmount", added, "chargeNo", latest.chargeNo()));
        }
        return new ChargeResult(latest.id(), latest.chargeNo(), created, added, latest.totalAmount());
    }

    // ---------------------------------------------------------------- 退药冲减

    /**
     * 退药冲减（幂等）：{@code cumulativeRefundAmount} 是该明细"累计已退金额"。
     *
     * <p>原收费单未结算 → 直接写负向明细并减少总额；已结算 → 登记待财务退费。
     * 重复提交同一累计值返回当前状态，不重复冲账。
     */
    @Transactional
    public RefundView refund(UUID dispenseItemId, BigDecimal cumulativeRefundAmount,
                             String reason, String actor) {
        if (dispenseItemId == null || cumulativeRefundAmount == null) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "退药冲减缺少发药明细或金额");
        }
        PharmacyChargeLink link = repo.lockPharmacyCharge(dispenseItemId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND,
                        "未找到该发药明细的费用记录（处方可能未启用发药计费）"));
        BigDecimal target = money(cumulativeRefundAmount);
        if (target.signum() < 0) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "退费金额不能为负");
        }
        if (target.compareTo(link.amount()) > 0) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "退费金额不能超过发药金额（" + link.amount() + "）");
        }
        BigDecimal handled = link.handledRefund();
        BigDecimal delta = target.subtract(handled);
        if (delta.signum() < 0) {
            throw new BusinessException(ErrorCode.CONFLICT,
                    "退费金额必须单调递增（已处理 " + handled + "，本次 " + target + "）");
        }
        if (delta.signum() == 0) {
            return view(link);
        }

        Charge charge = repo.lockCharge(link.chargeId())
                .orElseThrow(() -> new BusinessException(ErrorCode.CHARGE_NOT_FOUND));
        boolean settled = !Charge.UNPAID.equals(charge.status());
        if (settled) {
            // 已结算：不能改账，登记待财务退费
            if (!repo.addPharmacyRefundPending(dispenseItemId, delta)) {
                throw new BusinessException(ErrorCode.CONFLICT, "退费金额已变化，请刷新后重试");
            }
        } else {
            boolean inserted = repo.insertChargeItem(UUID.randomUUID(), charge.id(), null,
                    "DISPENSE_RETURN:" + dispenseItemId + ":" + target.toPlainString(),
                    link.drugCode(), "退药冲减 " + link.drugName(), "DRUG", delta.negate());
            if (!inserted) {
                return view(link);
            }
            if (!repo.addChargeAmount(charge.id(), delta.negate(), charge.version())) {
                throw new BusinessException(ErrorCode.CONFLICT, "收费单状态已变化，请刷新后重试");
            }
            if (!repo.addPharmacyRefundApplied(dispenseItemId, delta)) {
                throw new BusinessException(ErrorCode.CONFLICT, "退费金额已变化，请刷新后重试");
            }
        }
        audit(actor, "PHARMACY_REFUND", link.visitId().toString(), Map.of(
                "dispenseItemId", dispenseItemId.toString(), "drugCode", link.drugCode(),
                "cumulativeRefund", target, "delta", delta, "settled", settled,
                "reason", reason == null ? "" : reason));
        return view(repo.findPharmacyCharge(dispenseItemId).orElseThrow());
    }

    /** 财务确认已实际退费（待退费 → 已退费）。 */
    @Transactional
    public RefundView completeRefund(UUID dispenseItemId, BigDecimal amount, String actor) {
        PharmacyChargeLink link = repo.lockPharmacyCharge(dispenseItemId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "未找到该发药明细的费用记录"));
        BigDecimal value = money(amount);
        if (value.signum() <= 0) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "退费金额必须大于 0");
        }
        if (value.compareTo(link.refundPendingAmount()) > 0) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "退费金额超过待退费余额（" + link.refundPendingAmount() + "）");
        }
        if (!repo.completePharmacyRefund(dispenseItemId, value)) {
            throw new BusinessException(ErrorCode.CONFLICT, "待退费金额已变化，请刷新后重试");
        }
        audit(actor, "PHARMACY_REFUND_DONE", link.visitId().toString(), Map.of(
                "dispenseItemId", dispenseItemId.toString(), "amount", value));
        return view(repo.findPharmacyCharge(dispenseItemId).orElseThrow());
    }

    /** 待财务退费的列表（财务工作台用）。 */
    public List<RefundView> pendingRefunds(int limit) {
        int size = limit <= 0 ? 50 : Math.min(limit, 200);
        return repo.listPendingPharmacyRefunds(size).stream().map(this::view).toList();
    }

    // ---------------------------------------------------------------- 内部

    private void validateLines(List<ChargeLine> items) {
        Set<UUID> seen = new HashSet<>();
        for (ChargeLine line : items) {
            if (line == null || line.dispenseItemId() == null) {
                throw new BusinessException(ErrorCode.VALIDATION_FAILED, "发药明细缺少 dispenseItemId");
            }
            if (!seen.add(line.dispenseItemId())) {
                throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                        "同一次发药请求中 dispenseItemId 不能重复：" + line.dispenseItemId());
            }
            if (line.drugCode() == null || line.drugCode().isBlank()) {
                throw new BusinessException(ErrorCode.VALIDATION_FAILED, "发药明细缺少药品编码");
            }
            BigDecimal amount = line.amount();
            if (amount == null || amount.signum() <= 0) {
                throw new BusinessException(ErrorCode.VALIDATION_FAILED, "发药金额必须大于 0");
            }
            if (money(amount).compareTo(MAX_LINE_AMOUNT) > 0) {
                throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                        "单条发药金额超过上限（" + MAX_LINE_AMOUNT + "）");
            }
        }
    }

    private Charge newCharge(Visit visit) {
        Charge charge = new Charge(UUID.randomUUID(), repo.nextChargeNo(), visit.id(),
                BigDecimal.ZERO, Charge.UNPAID, null, null, 0L);
        repo.insertCharge(charge);
        return charge;
    }

    private RefundView view(PharmacyChargeLink link) {
        boolean settled = repo.findCharge(link.chargeId())
                .map(c -> !Charge.UNPAID.equals(c.status()))
                .orElse(true);
        return new RefundView(link.dispenseItemId(), link.visitId(), link.drugCode(), link.drugName(),
                link.amount(), link.refundedAmount(), link.refundPendingAmount(),
                link.refundDoneAmount(), settled);
    }

    /** 金额统一 2 位小数（HALF_UP），与全平台金额纪律一致。 */
    private static BigDecimal money(BigDecimal value) {
        return value.setScale(2, RoundingMode.HALF_UP);
    }

    private void audit(String actor, String action, String targetId, Map<String, Object> detail) {
        try {
            repo.logChange(actor == null || actor.isBlank() ? "pharmacy" : actor, action, "VISIT",
                    targetId, objectMapper.writeValueAsString(new LinkedHashMap<>(detail)));
        } catch (JacksonException e) {
            repo.logChange(actor == null || actor.isBlank() ? "pharmacy" : actor, action, "VISIT",
                    targetId, "{\"_detail_serialize_failed\":true}");
        }
    }
}
