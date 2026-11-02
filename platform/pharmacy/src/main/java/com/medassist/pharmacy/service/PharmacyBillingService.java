package com.medassist.pharmacy.service;

import com.medassist.common.api.ErrorCode;
import com.medassist.common.exception.BusinessException;
import com.medassist.pharmacy.client.ClinicalChargeClient;
import com.medassist.pharmacy.domain.PharmacyModel.Dispense;
import com.medassist.pharmacy.domain.PharmacyModel.DispenseItem;
import com.medassist.pharmacy.domain.PharmacyModel.Prescription;
import com.medassist.pharmacy.repo.PharmacyRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 发药计费编排（N2 收尾）：本地事务提交后再调用收费域。
 *
 * <p>为什么用 afterCommit：
 * <ul>
 *   <li>收费域调用失败不能回滚"药已经发给患者"的事实（库存与发药单都已落库）；</li>
 *   <li>因此把计费做成可补偿动作：失败置 {@code CHARGE_FAILED}，由
 *       {@code POST /pharmacy/dispenses/{id}/billing-retry} 重试（临床侧按发药明细 id 幂等）。</li>
 * </ul>
 *
 * <p>退药同理：按"累计已退金额"幂等冲减，重试一定收敛。
 */
@Service
public class PharmacyBillingService {

    private static final Logger log = LoggerFactory.getLogger(PharmacyBillingService.class);

    private final PharmacyRepository repo;
    private final ClinicalChargeClient client;
    private final ObjectMapper objectMapper;

    public PharmacyBillingService(PharmacyRepository repo, ClinicalChargeClient client,
                                  ObjectMapper objectMapper) {
        this.repo = repo;
        this.client = client;
        this.objectMapper = objectMapper;
    }

    // ---------------------------------------------------------------- 发药计费

    /** 在发药事务内登记：提交成功后计费。 */
    public void scheduleCharge(Dispense dispense, UUID visitId, List<DispenseItem> items, String actor) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            chargeNow(dispense, visitId, items, actor);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                chargeNow(dispense, visitId, items, actor);
            }
        });
    }

    private void chargeNow(Dispense dispense, UUID visitId, List<DispenseItem> items, String actor) {
        try {
            ClinicalChargeClient.Charged charged = client.charge(visitId, dispense.id(),
                    items.stream()
                            .map(i -> new ClinicalChargeClient.Line(i.id(), drugCodeOf(i), drugNameOf(i), i.amount()))
                            .toList(),
                    actor);
            repo.updateDispenseBillingStatus(dispense.id(), Dispense.BILLING_CHARGED);
            audit(actor, "DISPENSE_CHARGED", dispense.id().toString(), Map.of(
                    "dispenseNo", dispense.dispenseNo(), "chargeNo", charged.chargeNo(),
                    "chargeTotal", charged.chargeTotal()));
        } catch (RuntimeException e) {
            log.warn("pharmacy.dispense_charge_failed dispenseId={} err={}", dispense.id(),
                    e.getClass().getSimpleName());
            repo.updateDispenseBillingStatus(dispense.id(), Dispense.BILLING_CHARGE_FAILED);
            audit(actor, "DISPENSE_CHARGE_FAILED", dispense.id().toString(), Map.of(
                    "dispenseNo", dispense.dispenseNo(), "reason", safeMessage(e)));
        }
    }

    // ---------------------------------------------------------------- 退药冲减

    /** 在退药事务内登记：提交成功后按累计已退金额冲减。 */
    public void scheduleRefund(UUID dispenseId, List<DispenseItem> items, String actor) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            refundNow(dispenseId, items, actor);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                refundNow(dispenseId, items, actor);
            }
        });
    }

    private void refundNow(UUID dispenseId, List<DispenseItem> items, String actor) {
        boolean pending = false;
        try {
            for (DispenseItem item : items) {
                if (item.returnedQuantity() <= 0) {
                    continue;
                }
                BigDecimal cumulative = item.unitPrice()
                        .multiply(BigDecimal.valueOf(item.returnedQuantity()))
                        .setScale(2, RoundingMode.HALF_UP);
                ClinicalChargeClient.Refunded refunded = client.refund(item.id(), cumulative,
                        "退药 " + item.batchNo(), actor);
                pending = pending || refunded.pendingAmount().signum() > 0;
            }
            repo.updateDispenseBillingStatus(dispenseId,
                    pending ? Dispense.BILLING_REFUND_PENDING : Dispense.BILLING_CHARGED);
            audit(actor, "DISPENSE_REFUND_SYNCED", dispenseId.toString(), Map.of(
                    "refundPending", pending));
        } catch (RuntimeException e) {
            log.warn("pharmacy.dispense_refund_failed dispenseId={} err={}", dispenseId,
                    e.getClass().getSimpleName());
            repo.updateDispenseBillingStatus(dispenseId, Dispense.BILLING_REFUND_FAILED);
            audit(actor, "DISPENSE_REFUND_FAILED", dispenseId.toString(), Map.of(
                    "reason", safeMessage(e)));
        }
    }

    // ---------------------------------------------------------------- 医嘱计费模式退药冲减（B3）

    /**
     * 医嘱计费模式：退药后冲减临床医嘱金额（同样在本地事务提交后执行）。
     *
     * <p>临床侧按 {@code dispenseItemId} + "累计已退金额"幂等：未计费记 applied（结算时扣减）、
     * 已计费未结算写负向明细、已结算登记待财务退费。
     */
    public void scheduleOrderRefund(UUID dispenseId, UUID orderId, LocalDate supplyDate,
                                    List<DispenseItem> items, String actor) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            orderRefundNow(dispenseId, orderId, supplyDate, items, actor);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                orderRefundNow(dispenseId, orderId, supplyDate, items, actor);
            }
        });
    }

    private void orderRefundNow(UUID dispenseId, UUID orderId, LocalDate supplyDate,
                                List<DispenseItem> items, String actor) {
        boolean pending = false;
        int cancelled = 0;
        try {
            for (DispenseItem item : items) {
                if (item.returnedQuantity() <= 0) {
                    continue;
                }
                BigDecimal cumulative = item.unitPrice()
                        .multiply(BigDecimal.valueOf(item.returnedQuantity()))
                        .setScale(2, RoundingMode.HALF_UP);
                ClinicalChargeClient.OrderRefunded refunded = client.refundOrder(orderId, item.id(),
                        item.amount().setScale(2, RoundingMode.HALF_UP), cumulative,
                        "退药 " + item.batchNo(), actor,
                        supplyDate == null ? null : supplyDate.toString());
                pending = pending || refunded.pendingAmount().signum() > 0;
                if (refunded.orderCancelled()) {
                    cancelled++;
                }
            }
            // 医嘱计费模式下药房不持账：冲减同步成功后回到 NONE（待退费保留 PENDING 供财务查询）
            repo.updateDispenseBillingStatus(dispenseId,
                    pending ? Dispense.BILLING_REFUND_PENDING : Dispense.BILLING_NONE);
            audit(actor, "ORDER_REFUND_SYNCED", dispenseId.toString(), Map.of(
                    "orderId", orderId.toString(), "refundPending", pending,
                    "orderCancelled", cancelled));
        } catch (RuntimeException e) {
            log.warn("pharmacy.order_refund_failed dispenseId={} orderId={} err={}", dispenseId,
                    orderId, e.getClass().getSimpleName());
            repo.updateDispenseBillingStatus(dispenseId, Dispense.BILLING_REFUND_FAILED);
            audit(actor, "ORDER_REFUND_FAILED", dispenseId.toString(), Map.of(
                    "orderId", orderId.toString(), "reason", safeMessage(e)));
        }
    }

    // ---------------------------------------------------------------- 补偿重试

    /**
     * 重试计费/退药同步（运维与药房窗口都可用）。
     *
     * <p>已退药的发药单不允许重试计费——那会把患者已经退回的药再收一次钱。
     */
    public Dispense retry(UUID dispenseId, String actor) {
        Dispense dispense = repo.findDispense(dispenseId)
                .orElseThrow(() -> new BusinessException(ErrorCode.DISPENSE_NOT_FOUND));
        Prescription rx = repo.findPrescription(dispense.prescriptionId())
                .orElseThrow(() -> new BusinessException(ErrorCode.PRESCRIPTION_NOT_FOUND));
        if (!rx.chargeOnDispense()) {
            // 医嘱计费模式：只有"退药冲减同步失败/待退费"需要重试，药房本身不记账
            UUID orderId = rx.sourceOrderId();
            if (orderId == null) {
                throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                        "该处方是医嘱计费模式且未关联临床医嘱，无需重试计费");
            }
            String billingStatus = dispense.billingStatus();
            if (Dispense.BILLING_REFUND_FAILED.equals(billingStatus)
                    || Dispense.BILLING_REFUND_PENDING.equals(billingStatus)) {
                orderRefundNow(dispenseId, orderId, rx.supplyDate(),
                        repo.listDispenseItems(dispenseId), actor);
                return repo.findDispense(dispenseId).orElseThrow();
            }
            return dispense; // NONE：没有待同步的冲减，幂等返回
        }
        List<DispenseItem> items = repo.listDispenseItems(dispenseId);
        String status = dispense.billingStatus();
        if (Dispense.BILLING_PENDING.equals(status) || Dispense.BILLING_CHARGE_FAILED.equals(status)) {
            boolean returned = items.stream().anyMatch(i -> i.returnedQuantity() > 0);
            if (returned) {
                throw new BusinessException(ErrorCode.CONFLICT,
                        "该发药单已发生退药，不能整单重试计费，请由收费处人工处理");
            }
            chargeNow(dispense, rx.visitId(), items, actor);
        } else if (Dispense.BILLING_REFUND_FAILED.equals(status)
                || Dispense.BILLING_REFUND_PENDING.equals(status)) {
            refundNow(dispenseId, items, actor);
        } else if (Dispense.BILLING_CHARGED.equals(status)) {
            return dispense; // 幂等：已计费无需重试
        } else {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "当前计费状态不支持重试：" + status);
        }
        return repo.findDispense(dispenseId).orElseThrow();
    }

    // ---------------------------------------------------------------- 内部

    /** 发药明细只带 drugId；药品编码/名称从处方明细或药品字典取（此处用字典，保持唯一真源）。 */
    private String drugCodeOf(DispenseItem item) {
        return repo.findDrug(item.drugId()).map(d -> d.drugCode()).orElse("UNKNOWN");
    }

    private String drugNameOf(DispenseItem item) {
        return repo.findDrug(item.drugId()).map(d -> d.name()).orElse("未知药品");
    }

    private static String safeMessage(RuntimeException e) {
        return e.getMessage() == null ? e.getClass().getSimpleName()
                : e.getMessage().substring(0, Math.min(200, e.getMessage().length()));
    }

    private void audit(String actor, String action, String targetId, Map<String, Object> detail) {
        try {
            repo.logChange(actor == null || actor.isBlank() ? "pharmacy" : actor, action,
                    "DISPENSE", targetId, objectMapper.writeValueAsString(new LinkedHashMap<>(detail)));
        } catch (JacksonException e) {
            repo.logChange(actor == null || actor.isBlank() ? "pharmacy" : actor, action,
                    "DISPENSE", targetId, "{\"_detail_serialize_failed\":true}");
        }
    }
}
