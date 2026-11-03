package com.medassist.material.web;

import com.medassist.common.security.DataScope;
import com.medassist.material.domain.MaterialModel.ConsumableTrace;

/**
 * 耗材追溯的 PHI 掩码：没有 {@code phi:read} 时把患者号改写为掩码。
 *
 * <p>追溯列表天然是"某批次用在了哪些患者身上"，召回时也需要看患者号；
 * 因此这里不隐藏记录，只按权限决定患者号是否明文，既保证召回可执行，也避免越权查看 PHI。
 */
final class PhiMask {

    private PhiMask() {
    }

    static ConsumableTrace trace(ConsumableTrace t, DataScope scope) {
        if (t == null || scope.phiReadable()) {
            return t;
        }
        return new ConsumableTrace(t.id(), t.issueItemId(), t.consumableId(),
                t.consumableCode(), t.consumableName(), t.batchNo(), t.expireDate(),
                t.quantity(), t.patientId(), scope.maskPatientNo(t.patientNo()), t.visitId(),
                t.surgeryId(), t.operator(), t.usedAt(), t.remark(), t.createdAt());
    }
}
