package com.medassist.clinical.web;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * 药房发药费用联动契约（内网接口，N2 收尾）。
 *
 * <p>药房是"费用发生地"（发药），临床是"费用记账地"；跨服务只传业务事实
 * （发药明细 id、药品编码、金额），金额仍由药房按药品字典快照算出。
 */
public final class PharmacyChargeDtos {

    private PharmacyChargeDtos() {
    }

    /** 发药明细一行（dispenseItemId 是幂等键）。 */
    public record ChargeLine(UUID dispenseItemId, String drugCode, String drugName, BigDecimal amount) {
    }

    /** 发药计费请求：一次发药单的全部明细。 */
    public record ChargeReq(UUID visitId, UUID dispenseId, String actor, List<ChargeLine> items) {
    }

    /** 发药计费结果（createdItems=0 表示全部已计费，幂等重放）。 */
    public record ChargeResult(UUID chargeId, String chargeNo, int createdItems,
                               BigDecimal addedAmount, BigDecimal chargeTotal) {
    }

    /** 退药冲减请求：传"累计已退金额"（幂等，重复提交不会重复冲账）。 */
    public record RefundReq(UUID dispenseItemId, BigDecimal cumulativeRefundAmount,
                            String reason, String actor) {
    }

    /** 财务确认退费。 */
    public record CompleteRefundReq(BigDecimal amount, String actor) {
    }

    /** 退费视图。 */
    public record RefundView(UUID dispenseItemId, UUID visitId, String drugCode, String drugName,
                             BigDecimal amount, BigDecimal refundedAmount, BigDecimal pendingAmount,
                             BigDecimal doneAmount, boolean billSettled) {
    }

    /**
     * 医嘱计费模式的退药冲减请求。
     *
     * @param itemAmount             该发药明细金额（可退上限，由药房按发药明细给出）
     * @param cumulativeRefundAmount 该明细累计已退金额（幂等：重复提交同一值不会重复冲账）
     */
    public record OrderRefundReq(UUID orderId, UUID dispenseItemId, BigDecimal itemAmount,
                                 BigDecimal cumulativeRefundAmount, String reason, String actor) {
    }

    /** 医嘱退药冲减视图：applied=未结算直接冲减，pending=已结算待财务退费。 */
    public record OrderRefundView(UUID dispenseItemId, UUID orderId, UUID visitId,
                                  BigDecimal amount, BigDecimal appliedAmount,
                                  BigDecimal pendingAmount, BigDecimal doneAmount,
                                  boolean billSettled, boolean orderCancelled) {
    }
}
