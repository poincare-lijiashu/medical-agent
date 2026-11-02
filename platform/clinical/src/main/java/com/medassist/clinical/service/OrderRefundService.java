package com.medassist.clinical.service;

import com.medassist.clinical.domain.ClinicalModel.Charge;
import com.medassist.clinical.domain.ClinicalModel.OrderRefund;
import com.medassist.clinical.domain.ClinicalModel.VisitOrder;
import com.medassist.clinical.repo.ClinicalRepository;
import com.medassist.clinical.web.PharmacyChargeDtos.OrderRefundReq;
import com.medassist.clinical.web.PharmacyChargeDtos.OrderRefundView;
import com.medassist.common.api.ErrorCode;
import com.medassist.common.exception.BusinessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * 医嘱计费模式（billing_mode=ORDER）的退药冲减（评审 B3）。
 *
 * <p>问题：药房发药计费（DISPENSE）已有负向明细/待退费双路径；但医嘱计费模式下，
 * 钱是临床结算时按医嘱一次性收的，退药只回补库存、**不退钱也没有账务痕迹**。
 *
 * <p>三种时机，各自正确：
 * <ol>
 *   <li><b>尚未计费</b>（收费单还没有 ORDER: 明细）：登记 applied，结算时按 applied 扣减医嘱金额；
 *       若该医嘱已全额退回，则直接作废医嘱，避免结算再收一次；</li>
 *   <li><b>已计费未结算</b>（收费单 UNPAID）：写负向明细 ORDER_RETURN: 并减少收费单总额；</li>
 *   <li><b>已结算</b>：不能改账，登记 pending，由财务工作台确认后退费（done）。</li>
 * </ol>
 *
 * <p>幂等：以 dispense_item_id 为键，调用方传"累计已退金额"，重复提交同一值直接返回当前状态；
 * 金额上限由数据库 CHECK（applied+pending+done ≤ amount）兜底。
 */
@Service
public class OrderRefundService {

    private final ClinicalRepository repo;
    private final ObjectMapper objectMapper;

    public OrderRefundService(ClinicalRepository repo, ObjectMapper objectMapper) {
        this.repo = repo;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public OrderRefundView refund(OrderRefundReq req) {
        if (req == null || req.orderId() == null || req.dispenseItemId() == null
                || req.itemAmount() == null || req.cumulativeRefundAmount() == null) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "退药冲减缺少医嘱、发药明细或金额");
        }
        BigDecimal itemAmount = money(req.itemAmount());
        if (itemAmount.signum() <= 0) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "发药明细金额必须大于 0");
        }
        BigDecimal target = money(req.cumulativeRefundAmount());
        if (target.signum() < 0) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "退费金额不能为负");
        }
        // 与结算同锁：结算侧锁 visit/医嘱行后再算净额，这里必须也拿到医嘱行锁，
        // 否则两边各读各的，会出现"按旧净额收钱 + 退款记在已计费医嘱上"的多收。
        VisitOrder order = repo.lockOrderById(req.orderId())
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "医嘱不存在"));

        // 长嘱：退药属于"某一天的药"，必须按当日 DAILY:{orderId}:{date} 明细冲减。
        // 只按 ORDER:{orderId} 找会永远查不到（长嘱从不产生 ORDER: 明细），
        // 结果是 applied 只增不减、退药不退钱。
        LocalDate supplyDate = parseSupplyDate(req.supplyDate());
        boolean dailyScoped = order.longTerm() && supplyDate != null;
        if (order.longTerm() && supplyDate == null) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "长嘱退药必须带供药日期（supplyDate），否则无法定位当日计费明细");
        }

        // 首次退药登记（幂等键 dispense_item_id；并发同明细只留一条）
        repo.insertOrderRefund(UUID.randomUUID(), order.id(), req.dispenseItemId(), order.visitId(),
                itemAmount, req.reason(), req.actor(), supplyDate);
        OrderRefund link = repo.lockOrderRefund(req.dispenseItemId())
                .orElseThrow(() -> new BusinessException(ErrorCode.CONFLICT,
                        "退药冲减登记失败，请重试"));
        if (!order.id().equals(link.orderId())) {
            throw new BusinessException(ErrorCode.CONFLICT,
                    "该发药明细已登记到其它医嘱，不能重复冲减");
        }
        if (target.compareTo(link.amount()) > 0) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "退费金额不能超过发药明细金额（" + link.amount() + "）");
        }
        BigDecimal delta = target.subtract(link.handled());
        if (delta.signum() < 0) {
            throw new BusinessException(ErrorCode.CONFLICT,
                    "退费金额必须单调递增（已处理 " + link.handled() + "，本次 " + target + "）");
        }
        if (delta.signum() == 0) {
            // 幂等重放：返回当前状态，不再冲账
            boolean settled = locateCharge(order, dailyScoped, supplyDate)
                    .flatMap(repo::findCharge)
                    .map(c -> !Charge.UNPAID.equals(c.status()))
                    .orElse(false);
            return view(link, settled, false);
        }

        // P1-9：医嘱级封顶。药品价（md_drug.price）与收费项目价（md_charge_item.price）
        // 是两个真源；只按"单条发药明细金额"封顶时，同一医嘱多条明细累计仍可能超过
        // 医嘱计费金额，最终把 charge.total_amount 冲成负数（CHECK 抛 500，患者侧看到多退）。
        BigDecimal handled = repo.sumOrderRefundHandled(order.id(),
                dailyScoped ? supplyDate : null);
        BigDecimal cap = order.amount();
        if (handled.add(delta).compareTo(cap) > 0) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "退药冲减累计将超过医嘱计费金额（医嘱 " + cap.toPlainString()
                            + "，已处理 " + handled.toPlainString() + "，本次 " + delta.toPlainString()
                            + "）：请先核对药品价与收费项目价是否一致");
        }

        Optional<UUID> chargeId = locateCharge(order, dailyScoped, supplyDate);
        boolean settled = false;
        boolean cancelled = false;
        if (chargeId.isPresent()) {
            Charge charge = repo.lockCharge(chargeId.get())
                    .orElseThrow(() -> new BusinessException(ErrorCode.CHARGE_NOT_FOUND));
            settled = !Charge.UNPAID.equals(charge.status());
            if (settled) {
                if (!repo.addOrderRefundPending(req.dispenseItemId(), delta)) {
                    throw new BusinessException(ErrorCode.CONFLICT, "退费金额已变化，请刷新后重试");
                }
            } else {
                boolean inserted = repo.insertChargeItem(UUID.randomUUID(), charge.id(), order.id(),
                        "ORDER_RETURN:" + req.dispenseItemId() + ":" + target.toPlainString(),
                        order.itemCode(), "退药冲减 " + order.itemName(),
                        order.drug() ? "DRUG" : order.orderType(), delta.negate());
                if (inserted) {
                    if (!repo.addChargeAmount(charge.id(), delta.negate(), charge.version())) {
                        throw new BusinessException(ErrorCode.CONFLICT, "收费单状态已变化，请刷新后重试");
                    }
                    if (!repo.addOrderRefundApplied(req.dispenseItemId(), delta)) {
                        throw new BusinessException(ErrorCode.CONFLICT, "退费金额已变化，请刷新后重试");
                    }
                }
            }
        } else {
            // 尚未生成费用明细：先记 applied，结算时扣减
            if (!repo.addOrderRefundApplied(req.dispenseItemId(), delta)) {
                throw new BusinessException(ErrorCode.CONFLICT, "退费金额已变化，请刷新后重试");
            }
            // 该医嘱已全额退回 → 直接作废，避免结算时再按原金额收费
            // 长嘱不走这里：长嘱按日计费（DAILY:），且"日量 vs 总量"口径尚未定稿（B2），
            // 若按整周期金额比较会误作废仍在执行的长嘱。
            BigDecimal appliedTotal = repo.sumAppliedOrderRefund(order.id());
            if (!order.longTerm() && appliedTotal.compareTo(order.amount()) >= 0) {
                cancelled = repo.cancelUnchargedOrder(order.id());
            }
        }

        audit(req.actor(), "ORDER_REFUND", order.visitId().toString(), Map.of(
                "orderId", order.id().toString(), "orderNo", order.orderNo(),
                "dispenseItemId", req.dispenseItemId().toString(),
                "cumulativeRefund", target, "delta", delta, "settled", settled,
                "orderCancelled", cancelled,
                "reason", req.reason() == null ? "" : req.reason()));
        return view(repo.lockOrderRefund(req.dispenseItemId()).orElseThrow(), settled, cancelled);
    }

    /** 财务确认退费（待退费 → 已退费）。 */
    @Transactional
    public OrderRefundView completeRefund(UUID dispenseItemId, BigDecimal amount, String actor) {
        OrderRefund link = repo.lockOrderRefund(dispenseItemId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "未找到医嘱退药冲减记录"));
        BigDecimal value = money(amount);
        if (value.signum() <= 0) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "退费金额必须大于 0");
        }
        if (value.compareTo(link.pendingAmount()) > 0) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "退费金额超过待退费余额（" + link.pendingAmount() + "）");
        }
        if (!repo.completeOrderRefund(dispenseItemId, value)) {
            throw new BusinessException(ErrorCode.CONFLICT, "待退费金额已变化，请刷新后重试");
        }
        audit(actor, "ORDER_REFUND_DONE", link.visitId().toString(), Map.of(
                "orderId", link.orderId().toString(),
                "dispenseItemId", dispenseItemId.toString(), "amount", value));
        return view(repo.lockOrderRefund(dispenseItemId).orElseThrow(), true, false);
    }

    /** 待财务退费的医嘱退药列表（财务工作台用）。 */
    public List<OrderRefund> pendingRefunds(int limit) {
        return repo.listPendingOrderRefunds(limit <= 0 ? 50 : Math.min(limit, 200));
    }

    private OrderRefundView view(OrderRefund link, boolean settled, boolean cancelled) {
        return new OrderRefundView(link.dispenseItemId(), link.orderId(), link.visitId(),
                link.amount(), link.appliedAmount(), link.pendingAmount(), link.doneAmount(),
                settled, cancelled);
    }

    private static BigDecimal money(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value.setScale(2, RoundingMode.HALF_UP);
    }

    private void audit(String actor, String action, String visitId, Map<String, Object> detail) {
        try {
            repo.logChange(actor == null || actor.isBlank() ? "pharmacy" : actor, action,
                    "VISIT", visitId, objectMapper.writeValueAsString(new LinkedHashMap<>(detail)));
        } catch (JacksonException e) {
            repo.logChange(actor == null || actor.isBlank() ? "pharmacy" : actor, action,
                    "VISIT", visitId, "{\"_detail_serialize_failed\":true}");
        }
    }

    /** 定位退药冲减要落到哪张收费单：长嘱按当日 DAILY 明细，临时医嘱按 ORDER: 明细。 */
    private Optional<UUID> locateCharge(VisitOrder order, boolean dailyScoped, LocalDate supplyDate) {
        return dailyScoped
                ? repo.findChargeIdByDailyItem(order.id(), supplyDate)
                : repo.findChargeIdByOrderItem(order.id());
    }

    /** 供药日期解析：空串按 null 处理；格式非法直接 422（不能静默当成"未计费"）。 */
    private static LocalDate parseSupplyDate(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(raw.trim());
        } catch (RuntimeException e) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "供药日期格式不正确，应为 yyyy-MM-dd：" + raw);
        }
    }
}
