package com.medassist.clinical.web;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/** 术中收费打包（手术费 / 麻醉费 / 耗材）的响应体。 */
public final class SurgeryChargeDtos {

    private SurgeryChargeDtos() {
    }

    /**
     * 一条打包费用行。
     *
     * <p>{@code unitPrice} 来自物价字典（{@code md_charge_item}），
     * {@code amount = unitPrice × quantity}；{@code charged} 表示该手术此项是否已计费。
     */
    public record ChargeLineView(String itemCode, String itemName, String category,
                                 BigDecimal quantity, BigDecimal unitPrice, BigDecimal amount,
                                 boolean charged) {
    }

    /**
     * 打包预览：规则展开后的全部行与金额。
     *
     * @param pendingAmount 尚未计费的金额（已计费行为 0）
     * @param fullyCharged  是否已全部计费（重复计费调用会返回 200 且不加钱）
     */
    public record SurgeryChargePreview(UUID caseId, String caseStatus,
                                       List<ChargeLineView> lines, BigDecimal totalAmount,
                                       BigDecimal pendingAmount, boolean fullyCharged) {
    }

    /** 打包计费结果；{@code addedAmount=0} 表示本次没有新增费用（幂等重试）。 */
    public record SurgeryChargeResult(UUID caseId, UUID chargeId, String chargeNo,
                                      BigDecimal addedAmount, BigDecimal chargeTotal,
                                      List<ChargeLineView> lines) {
    }
}
