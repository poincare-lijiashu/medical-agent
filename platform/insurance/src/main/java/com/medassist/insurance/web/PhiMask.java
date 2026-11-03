package com.medassist.insurance.web;

import com.medassist.common.security.DataScope;
import com.medassist.insurance.domain.InsuranceModel.Member;
import com.medassist.insurance.domain.InsuranceModel.MemberHistory;
import com.medassist.insurance.domain.InsuranceModel.SettlementBill;

import java.util.List;

/**
 * 医保侧 PHI 掩码（P0-S2）：没有 {@code phi:read} 时把患者号替换为掩码后再返回。
 *
 * <p>此前 insurance 直接返回患者号明文，QC / REGISTRAR 等低权账号可取明文，
 * {@code phi:read} 体系在本域失效。掩码在 Controller 出口统一改写，前端无需改动。
 */
final class PhiMask {

    private PhiMask() {
    }

    static SettlementBill bill(SettlementBill b, DataScope scope) {
        if (b == null || scope.phiReadable()) {
            return b;
        }
        return new SettlementBill(b.id(), b.billNo(), b.visitId(), b.patientId(),
                scope.maskPatientNo(b.patientNo()), b.serviceDate(), b.memberId(), b.totalAmount(),
                b.eligibleAmount(), b.deductible(), b.insuranceAmount(), b.selfPayAmount(),
                b.status(), b.detail(), b.createdBy(), b.confirmedBy(), b.confirmedAt(),
                b.voidReason(), b.voidedBy(), b.voidedAt(), b.snapshotRatio(), b.snapshotCap(),
                b.version(), b.createdAt(), b.primaryDiagnosisCode(), b.primaryDiagnosisName(),
                b.diagnosisCount(), b.diagnoses());
    }

    static List<SettlementBill> bills(List<SettlementBill> list, DataScope scope) {
        if (scope.phiReadable()) {
            return list;
        }
        return list.stream().map(b -> bill(b, scope)).toList();
    }

    static Member member(Member m, DataScope scope) {
        if (m == null || scope.phiReadable()) {
            return m;
        }
        return new Member(m.id(), m.patientId(), scope.maskPatientNo(m.patientNo()),
                m.insuranceType(), m.region(), m.personCategory(), m.deductible(),
                m.reimbursementRatio(), m.cap(), m.effectiveFrom(), m.effectiveTo(),
                m.status(), m.version());
    }

    static List<MemberHistory> memberHistory(List<MemberHistory> list, DataScope scope) {
        if (scope.phiReadable()) {
            return list;
        }
        return list.stream()
                .map(h -> new MemberHistory(h.id(), h.memberId(), h.patientId(),
                        scope.maskPatientNo(h.patientNo()), h.insuranceType(), h.region(),
                        h.personCategory(), h.deductible(), h.reimbursementRatio(), h.cap(),
                        h.effectiveFrom(), h.effectiveTo(), h.status(), h.version(),
                        h.changeType(), h.changedBy(), h.changedAt(), h.note()))
                .toList();
    }
}
