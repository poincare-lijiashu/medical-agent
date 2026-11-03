package com.medassist.clinical.web;

import com.medassist.clinical.domain.ClinicalModel.SurgeryBoardRow;
import com.medassist.clinical.domain.ClinicalModel.Visit;
import com.medassist.clinical.web.ClinicalDtos.AdmissionView;
import com.medassist.clinical.web.ClinicalDtos.InpatientAlert;
import com.medassist.clinical.web.ClinicalDtos.InpatientFinanceView;
import com.medassist.clinical.web.TheatreDtos.SurgeryBoardView;
import com.medassist.common.security.DataScope;

/**
 * 患者标识（PHI）掩码：没有 {@code phi:read} 时，把患者号替换成掩码后再返回。
 *
 * <p>为什么掩码在服务端做：前端拿到全量再遮，数据已经出库并留在浏览器内存与日志里，
 * 脱敏就只剩视觉效果。这里在 Controller 出口统一改写，前端无需任何改动。
 *
 * <p>为什么用拷贝而不是就地修改：领域对象是 {@code record}（不可变），
 * 构造新实例可以保证“能拿到原始数据的地方”和“返回给客户端的地方”是两条明确的边界。
 */
final class PhiMask {

    private PhiMask() {
    }

    static Visit visit(Visit v, DataScope scope) {
        if (v == null || scope.phiReadable()) {
            return v;
        }
        return new Visit(v.id(), v.visitNo(), v.patientId(), scope.maskPatientNo(v.patientNo()),
                v.deptId(), v.doctorId(), v.slotId(), v.visitType(), v.status(),
                v.visitDate(), v.version());
    }

    static AdmissionView admission(AdmissionView a, DataScope scope) {
        if (a == null || scope.phiReadable()) {
            return a;
        }
        return new AdmissionView(a.id(), a.admissionNo(), a.visitId(), a.visitNo(),
                a.patientId(), scope.maskPatientNo(a.patientNo()), a.wardId(), a.wardName(),
                a.bedId(), a.bedNo(), a.attendingDoctor(), a.status(),
                a.admittedAt(), a.dischargedAt());
    }

    static InpatientAlert alert(InpatientAlert a, DataScope scope) {
        if (a == null || scope.phiReadable()) {
            return a;
        }
        return new InpatientAlert(a.admissionId(), a.admissionNo(),
                scope.maskPatientNo(a.patientNo()), a.bedId(), a.bedNo(),
                a.unpaidTotal(), a.pendingAmount(), a.depositBalance(), a.shortfall());
    }

    static InpatientFinanceView finance(InpatientFinanceView f, DataScope scope) {
        if (f == null || scope.phiReadable()) {
            return f;
        }
        return new InpatientFinanceView(f.admissionId(), f.admissionNo(), f.visitId(), f.visitNo(),
                scope.maskPatientNo(f.patientNo()), f.wardId(), f.wardName(), f.bedId(), f.bedNo(),
                f.status(), f.admittedAt(), f.billedTotal(), f.settledTotal(), f.unpaidTotal(),
                f.depositBalance(), f.pendingAmount(), f.pendingOrderCount(), f.dailyLongAmount(),
                f.dailyLongOrderCount(), f.todayBilledAmount(), f.shortfall(), f.warning());
    }

    static SurgeryBoardView board(SurgeryBoardView view, DataScope scope) {
        if (view == null || scope.phiReadable()) {
            return view;
        }
        return new SurgeryBoardView(view.date(), view.roomCount(), view.truncated(),
                view.rows().stream().map(row -> boardRow(row, scope)).toList());
    }

    private static SurgeryBoardRow boardRow(SurgeryBoardRow r, DataScope scope) {
        return new SurgeryBoardRow(r.scheduleId(), r.roomId(), r.roomCode(), r.roomName(),
                r.roomLocation(), r.surgeryCaseId(), r.caseNo(), r.visitId(), r.visitNo(),
                scope.maskPatientNo(r.patientNo()), r.operationCode(), r.operationName(),
                r.surgeon(), r.assistant(), r.anesthesiaType(), r.asaGrade(), r.incisionLevel(),
                r.caseStatus(), r.scheduleStatus(), r.sequence(), r.plannedStart(), r.plannedEnd(),
                r.cancelReason(), r.scheduleVersion(), r.surgeryLevel(), r.levelApprovalStatus());
    }
}
