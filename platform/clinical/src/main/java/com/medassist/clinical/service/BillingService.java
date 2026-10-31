package com.medassist.clinical.service;

import com.medassist.clinical.domain.ClinicalModel.Charge;
import com.medassist.clinical.domain.ClinicalModel.Visit;
import com.medassist.clinical.domain.ClinicalModel.VisitOrder;
import com.medassist.clinical.repo.ClinicalRepository;
import com.medassist.clinical.web.ClinicalDtos.BillingPreview;
import com.medassist.clinical.web.ClinicalDtos.ChargeItemView;
import com.medassist.clinical.web.ClinicalDtos.ChargeView;
import com.medassist.common.api.ErrorCode;
import com.medassist.common.exception.BusinessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 门诊计费与收费。
 *
 * <p>幂等设计（这是收费系统最容易出事故的地方）：
 * <ul>
 *   <li>结算前先 {@code SELECT ... FOR UPDATE} 锁就诊与收费单，同一就诊的并发结算串行化；</li>
 *   <li>已 PAID 的收费单直接返回原单（重复提交收费请求不会多收一次）；</li>
 *   <li>费用明细 {@code order_id} 有唯一约束，即使锁失效也不会为同一医嘱生成第二条明细；</li>
 *   <li>金额用增量累加（{@code total_amount = total_amount + ?}），不覆盖写，避免并发丢更新。</li>
 * </ul>
 */
@Service
public class BillingService {

    private final ClinicalRepository repo;
    private final ObjectMapper objectMapper;

    public BillingService(ClinicalRepository repo, ObjectMapper objectMapper) {
        this.repo = repo;
        this.objectMapper = objectMapper;
    }

    /** 收费前预览：已计金额、待计医嘱、当前收费单。 */
    public BillingPreview preview(UUID visitId) {
        Visit visit = repo.findVisit(visitId)
                .orElseThrow(() -> new BusinessException(ErrorCode.VISIT_NOT_FOUND));
        Charge charge = repo.findChargeByVisit(visitId)
                .orElseThrow(() -> new BusinessException(ErrorCode.CHARGE_NOT_FOUND, "该就诊没有收费单：" + visit.visitNo()));
        List<VisitOrder> pending = repo.listOrders(visitId, VisitOrder.PLACED);
        BigDecimal pendingAmount = pending.stream()
                .map(VisitOrder::amount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        return new BillingPreview(charge.totalAmount(), pendingAmount, pending.size(), toView(charge));
    }

    /**
     * 结算：把待计医嘱并入收费单并置为已收费。
     *
     * <p>已结算的就诊返回原收费单（HTTP 200），调用方可安全重试。
     */
    @Transactional
    public ChargeView settle(UUID visitId, String paymentMethod, String actor) {
        Visit visit = repo.lockVisit(visitId)
                .orElseThrow(() -> new BusinessException(ErrorCode.VISIT_NOT_FOUND));
        if (Visit.CANCELLED.equals(visit.status())) {
            throw new BusinessException(ErrorCode.VISIT_STATUS_INVALID, "该就诊已取消，不能收费");
        }
        Charge charge = repo.lockChargeByVisit(visitId)
                .orElseThrow(() -> new BusinessException(ErrorCode.CHARGE_NOT_FOUND));
        if (Charge.PAID.equals(charge.status())) {
            audit(actor, "CHARGE_SETTLE_IDEMPOTENT", visitId.toString(),
                    Map.of("visitNo", visit.visitNo(), "chargeNo", charge.chargeNo()));
            return toView(charge);
        }
        if (Charge.REFUNDED.equals(charge.status())) {
            throw new BusinessException(ErrorCode.CHARGE_STATUS_INVALID, "该收费单已退费，不能再收费");
        }

        List<VisitOrder> pending = repo.listOrders(visitId, VisitOrder.PLACED);
        BigDecimal addAmount = BigDecimal.ZERO;
        for (VisitOrder o : pending) {
            boolean inserted = repo.insertChargeItem(UUID.randomUUID(), charge.id(), o.id(),
                    o.itemCode(), o.itemName(), categoryOf(o), o.amount());
            if (inserted) {
                addAmount = addAmount.add(o.amount());
            }
        }

        if (!repo.markChargePaid(charge.id(), addAmount, paymentMethod.trim(), charge.version())) {
            // 版本冲突说明另一个窗口刚结算/退费；交给调用方重试，避免"以为失败其实已扣费"
            throw new BusinessException(ErrorCode.CONFLICT, "收费单状态已变化，请刷新后重试");
        }
        repo.markOrdersCharged(pending.stream().map(VisitOrder::id).toList());

        audit(actor, "CHARGE_SETTLE", visitId.toString(), Map.of(
                "visitNo", visit.visitNo(), "chargeNo", charge.chargeNo(),
                "orderCount", pending.size(), "addedAmount", addAmount,
                "paymentMethod", paymentMethod.trim()));
        return toView(repo.findChargeByVisit(visitId).orElseThrow());
    }

    public ChargeView chargeOf(UUID visitId) {
        repo.findVisit(visitId).orElseThrow(() -> new BusinessException(ErrorCode.VISIT_NOT_FOUND));
        Charge charge = repo.findChargeByVisit(visitId)
                .orElseThrow(() -> new BusinessException(ErrorCode.CHARGE_NOT_FOUND));
        return toView(charge);
    }

    // ---------------------------------------------------------------- 内部

    private String categoryOf(VisitOrder o) {
        return switch (o.orderType()) {
            case "DRUG" -> "DRUG";
            case "TREATMENT" -> "TREATMENT";
            case "EXAM" -> "EXAM";
            default -> "MATERIAL";
        };
    }

    private ChargeView toView(Charge c) {
        List<ChargeItemView> items = repo.listChargeItems(c.id()).stream()
                .map(a -> new ChargeItemView((UUID) a[0], (String) a[1], (String) a[2],
                        (String) a[3], (BigDecimal) a[4]))
                .toList();
        return new ChargeView(c.id(), c.chargeNo(), c.visitId(), c.totalAmount(), c.status(),
                c.paymentMethod(), c.paidAt(), items);
    }

    private void audit(String actor, String action, String targetId, Map<String, Object> detail) {
        try {
            repo.logChange(actor, action, "VISIT", targetId,
                    objectMapper.writeValueAsString(new LinkedHashMap<>(detail)));
        } catch (JacksonException e) {
            repo.logChange(actor, action, "VISIT", targetId, "{\"_detail_serialize_failed\":true}");
        }
    }
}
