package com.medassist.clinical.client;

import java.util.UUID;

/**
 * 医技域内网客户端：把检查/检验医嘱转成医技申请。
 *
 * <p>幂等契约：同一 {@code orderId} 重复调用只会有一张申请；返回 {@code duplicate=true}
 * 表示此前已生成。
 */
public interface TechOrderClient {

    /** 医技申请引用。 */
    record AppRef(UUID applicationId, String applicationNo, boolean duplicate,
                  String category, String itemName) {
    }

    /** 申请作废结果（{@code cancelled=false} 表示该医嘱从未生成申请）。 */
    record CancelRef(UUID applicationId, String status, boolean cancelled) {
    }

    AppRef createFromOrder(UUID orderId, UUID patientId, UUID visitId, String visitNo, UUID deptId,
                           String appliedBy, String itemCode, String bodyPart, String priority,
                           String clinicalDiag);

    CancelRef cancelByOrder(UUID orderId, String reason, String actor);
}
