package com.medassist.clinical.web;

import com.medassist.clinical.domain.ClinicalModel.ChronicFollowup;
import com.medassist.clinical.domain.ClinicalModel.ChronicFollowupTask;
import com.medassist.clinical.domain.ClinicalModel.ChronicRecord;
import com.medassist.clinical.domain.ClinicalModel.Consultation;
import com.medassist.clinical.domain.ClinicalModel.HealthExamRegistration;
import com.medassist.clinical.domain.ClinicalModel.SurgeryBoardRow;
import com.medassist.clinical.domain.ClinicalModel.TransfusionRequest;
import com.medassist.clinical.domain.ClinicalModel.Visit;
import com.medassist.clinical.web.ChronicDtos.FollowupView;
import com.medassist.clinical.web.ChronicDtos.RecordSummaryView;
import com.medassist.clinical.web.ChronicDtos.RecordView;
import com.medassist.clinical.web.ChronicDtos.TaskView;
import com.medassist.clinical.web.ClinicalDtos.AdmissionView;
import com.medassist.clinical.web.ClinicalDtos.InpatientAlert;
import com.medassist.clinical.web.ClinicalDtos.InpatientFinanceView;
import com.medassist.clinical.web.EmergencyDtos.ObservationView;
import com.medassist.clinical.web.EmergencyDtos.TriageView;
import com.medassist.clinical.web.HealthExamDtos.RegistrationSummaryView;
import com.medassist.clinical.web.HealthExamDtos.RegistrationView;
import com.medassist.clinical.web.TheatreDtos.SurgeryBoardView;
import com.medassist.clinical.web.TransfusionDtos.RequestSummaryView;
import com.medassist.clinical.web.TransfusionDtos.RequestView;
import com.medassist.common.security.DataScope;

import java.util.List;

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
                a.admittedAt(), a.dischargedAt(), a.plannedDischargeDate());
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

    static Consultation consultation(Consultation c, DataScope scope) {
        if (c == null || scope.phiReadable()) {
            return c;
        }
        return new Consultation(c.id(), c.consultationNo(), c.visitId(), c.patientId(),
                scope.maskPatientNo(c.patientNo()), c.fromDeptId(), c.toDeptId(), c.reason(),
                c.status(), c.requestedBy(), c.requestedAt(), c.respondedBy(), c.respondedAt(),
                c.rejectedReason(), c.completedBy(), c.completedAt(), c.conclusion(),
                c.cancelledBy(), c.cancelledAt(), c.cancelledReason(), c.version(), c.createdAt());
    }

    static TriageView triage(TriageView t, DataScope scope) {
        if (t == null || scope.phiReadable()) {
            return t;
        }
        return new TriageView(t.id(), t.triageNo(), t.patientId(),
                scope.maskPatientNo(t.patientNo()), scope.maskPatientName(t.patientName()),
                t.deptId(), t.deptName(), t.triageLevel(), t.chiefComplaint(), t.temperature(),
                t.pulse(), t.respiration(), t.systolic(), t.diastolic(), t.spo2(),
                t.greenChannel(), t.status(), t.visitId(), t.visitNo(), t.triagedBy(),
                t.arrivalAt(), t.triagedAt(), t.leaveReason(), t.version(), t.createdAt());
    }

    static ObservationView observation(ObservationView o, DataScope scope) {
        if (o == null || scope.phiReadable()) {
            return o;
        }
        return new ObservationView(o.id(), o.observationNo(), o.triageId(), o.triageNo(),
                o.visitId(), o.visitNo(), o.patientId(), scope.maskPatientNo(o.patientNo()),
                scope.maskPatientName(o.patientName()), o.deptId(), o.deptName(), o.bedId(),
                o.bedNo(), o.status(), o.startAt(), o.endAt(), o.outcome(), o.outcomeNote(),
                o.createdBy(), o.closedBy(), o.version(), o.createdAt());
    }

    static RegistrationSummaryView healthSummary(RegistrationSummaryView s, DataScope scope) {
        if (s == null || scope.phiReadable()) {
            return s;
        }
        return new RegistrationSummaryView(s.id(), s.examNo(), s.packageName(), s.patientId(),
                scope.maskPatientNo(s.patientNo()), scope.maskPatientName(s.patientName()),
                s.gender(), s.birthDate(), s.status(), s.itemTotal(), s.itemCompleted(),
                s.abnormalCount(), s.reportStatus(), s.createdAt());
    }

    static RegistrationView healthRegistration(RegistrationView view, DataScope scope) {
        if (view == null || scope.phiReadable()) {
            return view;
        }
        HealthExamRegistration r = view.registration();
        HealthExamRegistration masked = new HealthExamRegistration(r.id(), r.examNo(),
                r.packageId(), r.packageName(), r.packagePrice(), r.patientId(),
                scope.maskPatientNo(r.patientNo()), scope.maskPatientName(r.patientName()),
                r.gender(), r.birthDate(), r.status(), r.note(), r.cancelReason(),
                r.cancelledBy(), r.cancelledAt(), r.createdBy(), r.version(), r.createdAt(),
                r.updatedAt());
        return new RegistrationView(masked, view.items(), view.report());
    }

    static RecordSummaryView chronicSummary(RecordSummaryView s, DataScope scope) {
        if (s == null || scope.phiReadable()) {
            return s;
        }
        return new RecordSummaryView(s.id(), s.recordNo(), s.patientId(),
                scope.maskPatientNo(s.patientNo()), scope.maskPatientName(s.patientName()),
                s.gender(), s.birthDate(), s.diseaseCode(), s.diseaseName(), s.riskLevel(),
                s.status(), s.manager(), s.createdAt(), s.lastFollowupDate(), s.followupCount(),
                s.nextDueDate());
    }

    static RecordView chronicRecord(RecordView view, DataScope scope) {
        if (view == null || scope.phiReadable()) {
            return view;
        }
        ChronicRecord r = view.record();
        ChronicRecord maskedRecord = new ChronicRecord(r.id(), r.recordNo(), r.patientId(),
                scope.maskPatientNo(r.patientNo()), scope.maskPatientName(r.patientName()),
                r.gender(), r.birthDate(), r.diseaseId(), r.diseaseCode(), r.diseaseName(),
                r.diagnosisDate(), r.riskLevel(), r.status(), r.managingDeptId(),
                r.managingDeptName(), r.manager(), r.note(), r.closeReason(), r.closedBy(),
                r.closedAt(), r.createdBy(), r.version(), r.createdAt(), r.updatedAt());
        ChronicFollowupTask maskedTask = view.pendingTask() == null ? null
                : new ChronicFollowupTask(view.pendingTask().id(), view.pendingTask().taskNo(),
                        view.pendingTask().planId(), view.pendingTask().chronicId(),
                        view.pendingTask().patientId(),
                        scope.maskPatientNo(view.pendingTask().patientNo()),
                        scope.maskPatientName(view.pendingTask().patientName()),
                        view.pendingTask().diseaseName(), view.pendingTask().dueDate(),
                        view.pendingTask().status(), view.pendingTask().completedAt(),
                        view.pendingTask().followupId(), view.pendingTask().cancelReason(),
                        view.pendingTask().cancelledBy(), view.pendingTask().cancelledAt(),
                        view.pendingTask().createdAt(), view.pendingTask().version());
        List<ChronicFollowup> maskedFollowups = view.followups() == null ? List.of()
                : view.followups().stream().map(f -> new ChronicFollowup(f.id(),
                        f.followupNo(), f.taskId(), f.chronicId(), f.patientId(),
                        scope.maskPatientNo(f.patientNo()),
                        scope.maskPatientName(f.patientName()), f.followupDate(), f.method(),
                        f.systolic(), f.diastolic(), f.fastingGlucose(), f.weight(), f.symptoms(),
                        f.medicationAdherence(), f.advice(), f.createdBy(), f.createdAt())).toList();
        return new RecordView(maskedRecord, view.plan(), maskedTask, maskedFollowups);
    }

    static TaskView chronicTask(TaskView t, DataScope scope) {
        if (t == null || scope.phiReadable()) {
            return t;
        }
        return new TaskView(t.id(), t.taskNo(), t.chronicId(), t.patientId(),
                scope.maskPatientNo(t.patientNo()), scope.maskPatientName(t.patientName()),
                t.diseaseName(), t.dueDate(), t.status(), t.overdue(), t.completedAt(),
                t.createdAt(), t.version());
    }

    static FollowupView chronicFollowup(FollowupView f, DataScope scope) {
        if (f == null || scope.phiReadable()) {
            return f;
        }
        return new FollowupView(f.id(), f.followupNo(), f.taskId(), f.chronicId(), f.patientId(),
                scope.maskPatientNo(f.patientNo()), scope.maskPatientName(f.patientName()),
                f.followupDate(), f.method(), f.systolic(), f.diastolic(), f.fastingGlucose(),
                f.weight(), f.symptoms(), f.medicationAdherence(), f.advice(), f.createdBy(),
                f.createdAt());
    }

    static RequestSummaryView transfusionSummary(RequestSummaryView s, DataScope scope) {
        if (s == null || scope.phiReadable()) {
            return s;
        }
        return new RequestSummaryView(s.id(), s.requestNo(), s.patientId(),
                scope.maskPatientNo(s.patientNo()), scope.maskPatientName(s.patientName()),
                s.wardName(), s.bedNo(), s.componentName(), s.quantity(), s.unit(),
                s.bloodType(), s.rh(), s.urgency(), s.status(), s.requestedBy(), s.requestedAt(),
                s.completedBy(), s.completedAt());
    }

    static RequestView transfusionRequest(RequestView view, DataScope scope) {
        if (view == null || scope.phiReadable()) {
            return view;
        }
        TransfusionRequest r = view.request();
        TransfusionRequest masked = new TransfusionRequest(r.id(), r.requestNo(),
                r.admissionId(), r.visitId(), r.patientId(),
                scope.maskPatientNo(r.patientNo()), scope.maskPatientName(r.patientName()),
                r.gender(), r.birthDate(), r.wardId(), r.wardName(), r.bedNo(), r.componentId(),
                r.componentCode(), r.componentName(), r.quantity(), r.unit(), r.bloodType(),
                r.rh(), r.hemoglobin(), r.platelet(), r.urgency(), r.indication(), r.status(),
                r.crossmatchId(), r.bagNo(), r.bagExpireAt(), r.requestedBy(), r.requestedAt(),
                r.issuedBy(), r.issuedAt(), r.startedBy(), r.startedAt(), r.completedBy(),
                r.completedAt(), r.cancelReason(), r.cancelledBy(), r.cancelledAt(), r.createdBy(),
                r.version(), r.createdAt(), r.updatedAt());
        return new RequestView(masked, view.crossmatches(), view.record(), view.reaction(),
                view.precheck(), view.issue(), view.observations());
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
