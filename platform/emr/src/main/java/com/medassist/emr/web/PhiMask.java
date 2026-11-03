package com.medassist.emr.web;

import com.medassist.common.security.DataScope;
import com.medassist.emr.domain.EmrModel.Homepage;
import com.medassist.emr.domain.EmrModel.HomepageSummary;
import com.medassist.emr.domain.EmrModel.MedicalRecord;
import com.medassist.emr.domain.EmrModel.RecordHit;

import java.util.List;

/**
 * 病历侧 PHI 掩码（P0-S2）：没有 {@code phi:read} 时把患者号/姓名替换为掩码后再返回。
 *
 * <p>此前 emr 直接返回患者号明文，QC / REGISTRAR 等低权账号可取明文，phi:read 体系在本域失效。
 * 掩码在 Controller 出口统一改写；病历内容（content）不在此列——那是临床数据，由病历权限管。
 */
final class PhiMask {

    private PhiMask() {
    }

    static MedicalRecord record(MedicalRecord r, DataScope scope) {
        if (r == null || scope.phiReadable()) {
            return r;
        }
        return new MedicalRecord(r.id(), r.recordNo(), r.patientId(),
                scope.maskPatientNo(r.patientNo()), r.visitId(), r.visitNo(), r.deptId(),
                r.docType(), r.templateCode(), r.title(), r.content(), r.contentText(),
                r.author(), r.status(), r.revision(), r.signedBy(), r.signedAt(),
                r.contentHash(), r.archivedAt(), r.version(), r.createdAt());
    }

    static List<MedicalRecord> records(List<MedicalRecord> list, DataScope scope) {
        return scope.phiReadable() ? list : list.stream().map(r -> record(r, scope)).toList();
    }

    static RecordHit hit(RecordHit h, DataScope scope) {
        if (h == null || scope.phiReadable()) {
            return h;
        }
        return new RecordHit(h.id(), h.recordNo(), h.patientId(),
                scope.maskPatientNo(h.patientNo()), h.deptId(), h.docType(), h.title(),
                h.author(), h.status(), h.revision(), h.createdAt());
    }

    static List<RecordHit> hits(List<RecordHit> list, DataScope scope) {
        return scope.phiReadable() ? list : list.stream().map(h -> hit(h, scope)).toList();
    }

    static HomepageSummary summary(HomepageSummary s, DataScope scope) {
        if (s == null || scope.phiReadable()) {
            return s;
        }
        return new HomepageSummary(s.id(), s.homepageNo(), s.visitNo(),
                scope.maskPatientNo(s.patientNo()), s.patientNameMasked(), s.deptId(),
                s.deptName(), s.wardName(), s.dischargedAt(), s.stayDays(), s.totalAmount(),
                s.status(), s.version());
    }

    static List<HomepageSummary> summaries(List<HomepageSummary> list, DataScope scope) {
        return scope.phiReadable() ? list : list.stream().map(s -> summary(s, scope)).toList();
    }

    static Homepage homepage(Homepage h, DataScope scope) {
        if (h == null || scope.phiReadable()) {
            return h;
        }
        return new Homepage(h.id(), h.homepageNo(), h.visitId(), h.admissionId(), h.patientId(),
                scope.maskPatientNo(h.patientNo()), scope.maskPatientName(h.patientName()),
                h.patientSex(), h.birthDate(), h.admitAge(), h.visitNo(), h.deptId(), h.deptName(),
                h.wardId(), h.wardName(), h.bedNo(), h.admittedAt(), h.dischargedAt(), h.stayDays(),
                h.attendingDoctor(), h.admissionWay(), h.dischargeMode(), h.deathTime(),
                h.deathCause(), h.readmitPlan(), h.maritalStatus(), h.occupation(), h.nation(),
                h.bloodType(), h.rhFactor(), h.rescueCount(), h.rescueSuccessCount(),
                h.criticalCase(), h.hospitalInfection(), h.chiefPhysician(), h.residentPhysician(),
                h.coder(), h.diagnoses(), h.procedures(), h.feeSummary(), h.totalAmount(),
                h.status(), h.submittedBy(), h.submittedById(), h.submittedAt(), h.archivedBy(),
                h.archivedAt(), h.version(), h.createdAt());
    }
}
