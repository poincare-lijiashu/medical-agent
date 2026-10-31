package com.medassist.clinical.service;

import com.medassist.clinical.client.PatientClient;
import com.medassist.clinical.domain.ClinicalModel.Admission;
import com.medassist.clinical.domain.ClinicalModel.Bed;
import com.medassist.clinical.domain.ClinicalModel.Charge;
import com.medassist.clinical.domain.ClinicalModel.Dept;
import com.medassist.clinical.domain.ClinicalModel.Visit;
import com.medassist.clinical.domain.ClinicalModel.Ward;
import com.medassist.clinical.repo.ClinicalRepository;
import com.medassist.clinical.web.ClinicalDtos.AdmissionView;
import com.medassist.clinical.web.ClinicalDtos.AdmitReq;
import com.medassist.clinical.web.ClinicalDtos.AssignBedReq;
import com.medassist.common.api.ErrorCode;
import com.medassist.common.exception.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 住院 ADT 规则：重复入院、床位占用与释放、出院前结清。
 */
class InpatientServiceTest {

    private ClinicalRepository repo;
    private PatientClient patientClient;
    private InpatientService service;

    private final UUID deptId = UUID.randomUUID();
    private final UUID wardId = UUID.randomUUID();
    private final UUID patientId = UUID.randomUUID();
    private final Dept dept = new Dept(deptId, "DEPT-IM", "内科门诊", "OUTPATIENT", true);
    private final Ward ward = new Ward(wardId, "W-IM-01", "内科一病区", deptId, true);

    @BeforeEach
    void setUp() {
        repo = mock(ClinicalRepository.class);
        patientClient = mock(PatientClient.class);
        service = new InpatientService(repo, patientClient, JsonMapper.builder().build());
        when(repo.findWard(wardId)).thenReturn(Optional.of(ward));
        when(repo.findDept(deptId)).thenReturn(Optional.of(dept));
        when(patientClient.requireActive(patientId))
                .thenReturn(new PatientClient.PatientRef(patientId, "P20261031000001", "张三"));
    }

    private Visit visit(String status) {
        return new Visit(UUID.randomUUID(), "V1", patientId, "P1", deptId, "doctor01", null,
                "INPATIENT", status, LocalDate.now(), 1L);
    }

    @Test
    @DisplayName("入院（未分床）：建住院就诊 + 0 元收费单 + ADMITTED 登记")
    void admitWithoutBed() {
        when(repo.findActiveAdmissionByPatient(patientId)).thenReturn(Optional.empty());
        when(repo.nextVisitNo()).thenReturn("V20261031000010");
        when(repo.nextChargeNo()).thenReturn("C20261031000010");
        when(repo.nextAdmissionNo()).thenReturn("A20261031000001");
        when(repo.findAdmission(any())).thenAnswer(inv -> {
            UUID id = inv.getArgument(0);
            return Optional.of(new Admission(id, "A20261031000001", UUID.randomUUID(), patientId, "P1",
                    wardId, null, "doctor01", Admission.ADMITTED, Instant.now(), null, 0L));
        });
        when(repo.findVisit(any())).thenReturn(Optional.of(visit(Visit.IN_TREATMENT)));

        AdmissionView v = service.admit(new AdmitReq(patientId, wardId, null, "doctor01"), "clerk01");

        assertThat(v.status()).isEqualTo(Admission.ADMITTED);
        verify(repo).insertVisit(any(Visit.class));
        verify(repo).insertCharge(any(Charge.class));
        verify(repo).insertAdmission(any(Admission.class));
        verify(repo, never()).occupyBed(any(), anyLong());
    }

    @Test
    @DisplayName("重复入院：同一患者已有在院登记时拒绝")
    void admitRejectsDuplicate() {
        when(repo.findActiveAdmissionByPatient(patientId)).thenReturn(Optional.of(
                new Admission(UUID.randomUUID(), "A1", UUID.randomUUID(), patientId, "P1", wardId,
                        null, "doctor01", Admission.IN_HOSPITAL, Instant.now(), null, 0L)));

        assertThatThrownBy(() -> service.admit(new AdmitReq(patientId, wardId, null, "doctor01"), "clerk01"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("A1");
        verify(repo, never()).insertAdmission(any());
    }

    @Test
    @DisplayName("转床：占新床、释放旧床、留转床轨迹")
    void transferOccupiesTargetAndFreesSource() {
        UUID admissionId = UUID.randomUUID();
        UUID oldBed = UUID.randomUUID();
        UUID newBed = UUID.randomUUID();
        Admission a = new Admission(admissionId, "A1", UUID.randomUUID(), patientId, "P1", wardId,
                oldBed, "doctor01", Admission.IN_HOSPITAL, Instant.now(), null, 2L);
        when(repo.lockAdmission(admissionId)).thenReturn(Optional.of(a));
        when(repo.lockBed(newBed)).thenReturn(Optional.of(new Bed(newBed, wardId, "02", Bed.FREE, 0L)));
        when(repo.lockBed(oldBed)).thenReturn(Optional.of(new Bed(oldBed, wardId, "01", Bed.OCCUPIED, 3L)));
        when(repo.occupyBed(newBed, 0L)).thenReturn(true);
        when(repo.updateAdmissionBed(admissionId, newBed, 2L)).thenReturn(true);
        when(repo.freeBed(oldBed, 3L)).thenReturn(true);
        when(repo.findAdmission(admissionId)).thenReturn(Optional.of(new Admission(
                admissionId, "A1", a.visitId(), patientId, "P1", wardId, newBed, "doctor01",
                Admission.IN_HOSPITAL, Instant.now(), null, 3L)));
        when(repo.findBed(newBed)).thenReturn(Optional.of(new Bed(newBed, wardId, "02", Bed.OCCUPIED, 1L)));
        when(repo.findVisit(any())).thenReturn(Optional.of(visit(Visit.IN_TREATMENT)));

        AdmissionView v = service.assignBed(admissionId, new AssignBedReq(newBed, "病情需要"), "nurse01");

        assertThat(v.bedNo()).isEqualTo("02");
        verify(repo).occupyBed(newBed, 0L);
        verify(repo).freeBed(oldBed, 3L);
        verify(repo).logBedTransfer(admissionId, oldBed, newBed, "病情需要", "nurse01");
    }

    @Test
    @DisplayName("未结清不得出院")
    void dischargeRejectsUnpaid() {
        UUID admissionId = UUID.randomUUID();
        Admission a = new Admission(admissionId, "A1", UUID.randomUUID(), patientId, "P1", wardId,
                null, "doctor01", Admission.IN_HOSPITAL, Instant.now(), null, 1L);
        when(repo.lockAdmission(admissionId)).thenReturn(Optional.of(a));
        when(repo.findChargeByVisit(a.visitId())).thenReturn(Optional.of(
                new Charge(UUID.randomUUID(), "C1", a.visitId(), new BigDecimal("88.00"),
                        Charge.UNPAID, null, null, 0L)));

        assertThatThrownBy(() -> service.discharge(admissionId, "doctor01"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.CHARGE_STATUS_INVALID);
        verify(repo, never()).updateAdmissionStatus(any(), any(), any(), anyLong());
    }

    @Test
    @DisplayName("出院：置 DISCHARGED、腾床、结束就诊")
    void dischargeFreesBedAndFinishesVisit() {
        UUID admissionId = UUID.randomUUID();
        UUID bedId = UUID.randomUUID();
        UUID visitId = UUID.randomUUID();
        Admission a = new Admission(admissionId, "A1", visitId, patientId, "P1", wardId,
                bedId, "doctor01", Admission.IN_HOSPITAL, Instant.now(), null, 4L);
        when(repo.lockAdmission(admissionId)).thenReturn(Optional.of(a));
        when(repo.findChargeByVisit(visitId)).thenReturn(Optional.of(
                new Charge(UUID.randomUUID(), "C1", visitId, new BigDecimal("88.00"),
                        Charge.PAID, "CASH", Instant.now(), 2L)));
        when(repo.updateAdmissionStatus(admissionId, Admission.IN_HOSPITAL, Admission.DISCHARGED, 4L))
                .thenReturn(true);
        when(repo.lockBed(bedId)).thenReturn(Optional.of(new Bed(bedId, wardId, "01", Bed.OCCUPIED, 7L)));
        when(repo.freeBed(bedId, 7L)).thenReturn(true);
        when(repo.findVisit(visitId)).thenReturn(Optional.of(
                new Visit(visitId, "V1", patientId, "P1", deptId, "doctor01", null,
                        "INPATIENT", Visit.IN_TREATMENT, LocalDate.now(), 1L)));
        when(repo.updateVisitStatus(visitId, Visit.IN_TREATMENT, Visit.FINISHED, 1L)).thenReturn(true);
        when(repo.findAdmission(admissionId)).thenReturn(Optional.of(new Admission(
                admissionId, "A1", visitId, patientId, "P1", wardId, bedId, "doctor01",
                Admission.DISCHARGED, Instant.now(), Instant.now(), 5L)));
        when(repo.findBed(bedId)).thenReturn(Optional.of(new Bed(bedId, wardId, "01", Bed.FREE, 8L)));

        AdmissionView v = service.discharge(admissionId, "doctor01");

        assertThat(v.status()).isEqualTo(Admission.DISCHARGED);
        verify(repo).freeBed(bedId, 7L);
        verify(repo).updateVisitStatus(visitId, Visit.IN_TREATMENT, Visit.FINISHED, 1L);
    }
}
