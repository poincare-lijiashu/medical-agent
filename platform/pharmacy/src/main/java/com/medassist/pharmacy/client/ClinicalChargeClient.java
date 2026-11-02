package com.medassist.pharmacy.client;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * 临床收费域内网接口（发药计费 / 退药冲减）。
 *
 * <p>只有 {@code billing_mode=DISPENSE} 的处方需要它；费用由临床域记账，
 * 药房不直接写收费表（跨域只走 HTTP）。
 */
public interface ClinicalChargeClient {

    /** 发药明细一行（dispenseItemId 是临床侧的幂等键）。 */
    record Line(UUID dispenseItemId, String drugCode, String drugName, BigDecimal amount) {
    }

    /** 计费结果。 */
    record Charged(String chargeNo, BigDecimal chargeTotal) {
    }

    /** 退药冲减结果：pendingAmount>0 表示原收费单已结算，需财务退费。 */
    record Refunded(BigDecimal pendingAmount) {
    }

    /**
     * 医嘱计费模式的退药冲减结果。
     *
     * @param orderCancelled 该医嘱已全额退回且尚未计费，临床已直接作废医嘱
     */
    record OrderRefunded(BigDecimal appliedAmount, BigDecimal pendingAmount,
                         BigDecimal doneAmount, boolean orderCancelled) {
    }

    Charged charge(UUID visitId, UUID dispenseId, List<Line> lines, String actor);

    Refunded refund(UUID dispenseItemId, BigDecimal cumulativeRefundAmount, String reason, String actor);

    /** 医嘱计费模式：按"累计已退金额"幂等冲减医嘱（对应 clinical /internal/pharmacy-order-refunds）。 */
    OrderRefunded refundOrder(UUID orderId, UUID dispenseItemId, BigDecimal itemAmount,
                              BigDecimal cumulativeRefundAmount, String reason, String actor);
}
