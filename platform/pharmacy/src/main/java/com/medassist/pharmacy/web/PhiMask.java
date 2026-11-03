package com.medassist.pharmacy.web;

import com.medassist.common.security.DataScope;
import com.medassist.pharmacy.domain.PharmacyModel.NarcoticRegister;
import com.medassist.pharmacy.domain.PharmacyModel.Prescription;
import com.medassist.pharmacy.web.PharmacyDtos.PrescriptionView;

/**
 * 药房侧 PHI 掩码：没有 {@code phi:read} 时把患者号改写为掩码。
 *
 * <p>麻醉药品专册是全系统最敏感的列表之一（患者号 + 药品 + 数量 + 处方医师），
 * 因此即便药房不按科室隔离，也必须按权限决定是否返回明文。
 */
final class PhiMask {

    private PhiMask() {
    }

    static Prescription prescription(Prescription p, DataScope scope) {
        if (p == null || scope.phiReadable()) {
            return p;
        }
        return new Prescription(p.id(), p.prescriptionNo(), p.patientId(),
                scope.maskPatientNo(p.patientNo()), p.visitId(), p.visitNo(), p.deptId(),
                p.prescriber(), p.status(), p.reviewer(), p.reviewedAt(), p.rejectReason(),
                p.totalAmount(), p.version(), p.createdAt(), p.billingMode(),
                p.sourceOrderId(), p.supplyDate());
    }

    static PrescriptionView view(PrescriptionView v, DataScope scope) {
        if (v == null || scope.phiReadable()) {
            return v;
        }
        return new PrescriptionView(prescription(v.prescription(), scope), v.items());
    }

    static NarcoticRegister narcotic(NarcoticRegister r, DataScope scope) {
        if (r == null || scope.phiReadable()) {
            return r;
        }
        return new NarcoticRegister(r.id(), r.dispenseItemId(), r.dispenseId(), r.prescriptionId(),
                r.prescriptionNo(), r.patientId(), scope.maskPatientNo(r.patientNo()),
                r.drugId(), r.drugCode(), r.drugName(), r.spec(), r.quantity(), r.unit(),
                r.batchNo(), r.prescriber(), r.dispenser(), r.checker(), r.dispensedAt(),
                r.ampouleReturned(), r.ampouleReturnedAt(), r.ampouleReceiver(), r.status(),
                r.version(), r.createdAt());
    }
}
