package com.medassist.clinical.client;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * 药房域内网客户端：把药品医嘱转成待审处方。
 *
 * <p>幂等契约：同一 {@code orderId} 重复调用只会有一张处方；返回 {@code duplicate=true}
 * 表示此前已生成（临床侧重试、补偿重放都靠这条保证不重复开方）。
 */
public interface PharmacyOrderClient {

    /** 处方行（数量与用法来自医嘱，价格与规格由药房药品字典决定）。 */
    record Line(String drugCode, int quantity, String dosage, String frequency, Integer days,
                BigDecimal singleDose, String doseUnit, Integer timesPerDay) {
    }

    /** 药房处方引用。 */
    record RxRef(UUID prescriptionId, String prescriptionNo, boolean duplicate,
                 BigDecimal totalAmount) {
    }

    /**
     * 医嘱转处方。
     *
     * @param supplyDate 供药日期，与 orderId 组成幂等键；长期医嘱按日请领时每天一张
     */
    RxRef createFromOrder(UUID orderId, UUID patientId, UUID visitId, String visitNo, UUID deptId,
                          String prescriber, LocalDate supplyDate, List<Line> items);

    /**
     * 按医嘱作废处方（医嘱撤销/停嘱联动）。
     *
     * @return {@code cancelled=false} 表示该医嘱从未生成处方（未转送或转送失败），属正常情况
     */
    CancelRef cancelByOrder(UUID orderId, String reason, String actor);

    /** 处方作废结果。 */
    record CancelRef(UUID prescriptionId, String status, boolean cancelled) {
    }

    /**
     * 病区退药 → 药房批次库存退回（内网）。
     *
     * <p>为什么由药房分摊：一次发药可能跨多个批次，只有药房知道每个批次还能退多少。
     * 幂等键 {@code returnId} 与病区退药单号一致，重试不会重复回补库存。
     */
    WardReturnRef returnWardDrug(UUID orderId, LocalDate supplyDate, String drugCode, int quantity,
                                 String reason, UUID returnId, String actor);

    /** 药房退药回执。 */
    record WardReturnRef(UUID dispenseId, int returnedQuantity, int remainingReturnable) {
    }
}
