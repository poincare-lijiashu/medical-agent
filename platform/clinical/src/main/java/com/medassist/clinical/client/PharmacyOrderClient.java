package com.medassist.clinical.client;

import java.math.BigDecimal;
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

    RxRef createFromOrder(UUID orderId, UUID patientId, UUID visitId, String visitNo, UUID deptId,
                          String prescriber, List<Line> items);

    /**
     * 按医嘱作废处方（医嘱撤销/停嘱联动）。
     *
     * @return {@code cancelled=false} 表示该医嘱从未生成处方（未转送或转送失败），属正常情况
     */
    CancelRef cancelByOrder(UUID orderId, String reason, String actor);

    /** 处方作废结果。 */
    record CancelRef(UUID prescriptionId, String status, boolean cancelled) {
    }
}
