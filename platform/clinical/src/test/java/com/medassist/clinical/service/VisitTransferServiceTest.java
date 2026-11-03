package com.medassist.clinical.service;

import com.medassist.clinical.domain.ClinicalModel.Admission;
import com.medassist.clinical.domain.ClinicalModel.Bed;
import com.medassist.clinical.domain.ClinicalModel.Dept;
import com.medassist.clinical.domain.ClinicalModel.Visit;
import com.medassist.clinical.domain.ClinicalModel.VisitTransfer;
import com.medassist.clinical.domain.ClinicalModel.Ward;
import com.medassist.clinical.repo.ClinicalRepository;
import com.medassist.clinical.web.ClinicalDtos.TransferVisitReq;
import com.medassist.common.api.ErrorCode;
import com.medassist.common.exception.BusinessException;
import com.medassist.common.security.DataScope;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 转科的业务边界：只有当前主诊科室能发、必须在院、必须落到目标科室的病区床位。
 *
 * <p>重点不是"能不能改 dept_id"，而是改完之后旧科室是否立即失去访问、原床是否释放、
 * 进行中的会诊是否跟着撤回——这三件事任一做漏，转科就会制造越权或死锁的床位。
 */
class VisitTransferServiceTest {

    private ClinicalRepository repo;
    private VisitTransferService service;

    private final UUID visitId = UUID.randomUUID();
    private final UUID admissionId = UUID.randomUUID();
    private final UUID deptA = UUID.randomUUID();
    private final UUID deptB = UUID.randomUUID();
    private final UUID wardA = UUID.randomUUID();
    private final UUID wardB = UUID.randomUUID();
    private final UUID bedA = UUID.randomUUID();
    private final UUID bedB = UUID.randomUUID();

    private final DataScope deptAScope = DataScope.ofDepartment("doctor01", deptA, true);
    private final DataScope deptBScope = DataScope.ofDepartment("doctor02", deptB, true);

    @BeforeEach
    void setUp() {
        repo = mock(ClinicalRepository.class);
        service = new VisitTransferService(repo, new VisitAccessGuard(repo));
        when(repo.findVisit(visitId)).thenReturn(Optional.of(visit(deptA)));
        when(repo.findDept(deptB)).thenReturn(Optional.of(
                new Dept(deptB, "D02", "心内科", "INPATIENT", true)));
    }

    private Visit visit(UUID deptId) {
        return new Visit(visitId, "V001", UUID.randomUUID(), "P20260101000001", deptId,
                "doctor01", null, "INPATIENT", Visit.IN_TREATMENT, LocalDate.now(), 0L);
    }

    private Admission admission(UUID bedId) {
        return new Admission(admissionId, "A20260101000001", visitId, UUID.randomUUID(),
                "P20260101000001", wardA, bedId, "doctor01", Admission.IN_HOSPITAL,
                Instant.now(), null, 0L);
    }

    @Test
    @DisplayName("转科：非主诊科室 404，且不改动任何数据")
    void transferRejectsForeignDepartment() {
        assertThatThrownBy(() -> service.transfer(deptBScope, visitId,
                new TransferVisitReq(deptB, null, "需要心内科治疗"), "doctor02"))
                .isInstanceOfSatisfying(BusinessException.class, e ->
                        assertThat(e.errorCode()).isEqualTo(ErrorCode.VISIT_NOT_FOUND));

        verify(repo, never()).lockActiveAdmissionByVisit(any());
        verify(repo, never()).updateVisitDepartment(any(), any(), anyLong());
        verify(repo, never()).insertVisitTransfer(any());
    }

    @Test
    @DisplayName("转科：目标科室与当前科室相同 → 422")
    void transferRejectsSameDepartment() {
        when(repo.lockActiveAdmissionByVisit(visitId)).thenReturn(Optional.of(admission(bedA)));
        when(repo.lockVisit(visitId)).thenReturn(Optional.of(visit(deptA)));

        assertThatThrownBy(() -> service.transfer(deptAScope, visitId,
                new TransferVisitReq(deptA, null, "同科室"), "doctor01"))
                .isInstanceOfSatisfying(BusinessException.class, e ->
                        assertThat(e.errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED));
    }

    @Test
    @DisplayName("转科：患者不在院（无在院登记）→ 409")
    void transferRequiresInHospitalAdmission() {
        when(repo.lockVisit(visitId)).thenReturn(Optional.of(visit(deptA)));
        when(repo.lockActiveAdmissionByVisit(visitId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.transfer(deptAScope, visitId,
                new TransferVisitReq(deptB, bedB, "需要心内科治疗"), "doctor01"))
                .isInstanceOfSatisfying(BusinessException.class, e ->
                        assertThat(e.errorCode()).isEqualTo(ErrorCode.CONFLICT));

        verify(repo, never()).updateVisitDepartment(any(), any(), anyLong());
    }

    @Test
    @DisplayName("转科：目标科室不是住院科室 → 422")
    void transferRejectsOutpatientDepartment() {
        when(repo.lockActiveAdmissionByVisit(visitId)).thenReturn(Optional.of(admission(bedA)));
        when(repo.lockVisit(visitId)).thenReturn(Optional.of(visit(deptA)));
        when(repo.findDept(deptB)).thenReturn(Optional.of(
                new Dept(deptB, "OPD", "门诊", "OUTPATIENT", true)));

        assertThatThrownBy(() -> service.transfer(deptAScope, visitId,
                new TransferVisitReq(deptB, null, "需要心内科治疗"), "doctor01"))
                .isInstanceOfSatisfying(BusinessException.class, e ->
                        assertThat(e.errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED));
    }

    @Test
    @DisplayName("转科：已分床却不给目标床位 → 422（不允许挂着原病区再去别的科室）")
    void transferRequiresTargetBedWhenOccupied() {
        when(repo.lockVisit(visitId)).thenReturn(Optional.of(visit(deptA)));
        when(repo.lockActiveAdmissionByVisit(visitId)).thenReturn(Optional.of(admission(bedA)));

        assertThatThrownBy(() -> service.transfer(deptAScope, visitId,
                new TransferVisitReq(deptB, null, "需要心内科治疗"), "doctor01"))
                .isInstanceOfSatisfying(BusinessException.class, e ->
                        assertThat(e.errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED));

        verify(repo, never()).occupyBed(any(), anyLong());
    }

    @Test
    @DisplayName("转科：目标床位不属于目标科室的病区 → 422")
    void transferRejectsBedInOtherDepartmentWard() {
        when(repo.lockVisit(visitId)).thenReturn(Optional.of(visit(deptA)));
        when(repo.lockActiveAdmissionByVisit(visitId)).thenReturn(Optional.of(admission(bedA)));
        when(repo.lockBed(bedB)).thenReturn(Optional.of(
                new Bed(bedB, wardB, "B-01", Bed.FREE, 0L)));
        when(repo.findWard(wardB)).thenReturn(Optional.of(
                new Ward(wardB, "W2", "心内科病区", deptA, true)));

        assertThatThrownBy(() -> service.transfer(deptAScope, visitId,
                new TransferVisitReq(deptB, bedB, "需要心内科治疗"), "doctor01"))
                .isInstanceOfSatisfying(BusinessException.class, e ->
                        assertThat(e.errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED));

        verify(repo, never()).occupyBed(any(), anyLong());
    }

    @Test
    @DisplayName("转科成功：换床 + 改主诊科室 + 撤回进行中会诊 + 写转科记录")
    void transferMovesBedDepartmentAndCancelsConsultations() {
        when(repo.lockVisit(visitId)).thenReturn(Optional.of(visit(deptA)));
        when(repo.lockActiveAdmissionByVisit(visitId)).thenReturn(Optional.of(admission(bedA)));
        when(repo.lockBed(bedB)).thenReturn(Optional.of(
                new Bed(bedB, wardB, "B-01", Bed.FREE, 0L)));
        when(repo.findWard(wardB)).thenReturn(Optional.of(
                new Ward(wardB, "W2", "心内科病区", deptB, true)));
        when(repo.occupyBed(bedB, 0L)).thenReturn(true);
        when(repo.updateAdmissionWardAndBed(admissionId, wardB, bedB, 0L)).thenReturn(true);
        when(repo.lockBed(bedA)).thenReturn(Optional.of(
                new Bed(bedA, wardA, "A-01", Bed.OCCUPIED, 0L)));
        when(repo.freeBed(bedA, 0L)).thenReturn(true);
        when(repo.updateVisitDepartment(visitId, deptB, 0L)).thenReturn(true);
        when(repo.cancelActiveConsultationsForVisit(eq(visitId), eq("doctor01"), anyString()))
                .thenReturn(2);

        VisitTransfer transfer = service.transfer(deptAScope, visitId,
                new TransferVisitReq(deptB, bedB, " 需要心内科治疗 "), "doctor01");

        assertThat(transfer.fromDeptId()).isEqualTo(deptA);
        assertThat(transfer.toDeptId()).isEqualTo(deptB);
        assertThat(transfer.reason()).isEqualTo("需要心内科治疗");
        verify(repo).occupyBed(bedB, 0L);
        verify(repo).updateAdmissionWardAndBed(admissionId, wardB, bedB, 0L);
        verify(repo).freeBed(bedA, 0L);
        verify(repo).updateVisitDepartment(visitId, deptB, 0L);
        verify(repo).cancelActiveConsultationsForVisit(visitId, "doctor01",
                "转科至心内科：需要心内科治疗");
        verify(repo).insertVisitTransfer(any());
        verify(repo).logBedTransfer(admissionId, bedA, bedB, "转科至心内科", "doctor01");
        verify(repo).logChange(eq("doctor01"), eq("VISIT_TRANSFER"), eq("VISIT"),
                eq(visitId.toString()), contains("\"cancelledConsultations\":2"));
    }

    @Test
    @DisplayName("转科后：原科室查转科记录 404，新科室可读")
    void listIsScopedToCurrentDepartment() {
        when(repo.findVisit(visitId)).thenReturn(Optional.of(visit(deptB)));
        when(repo.listTransfersByVisit(visitId, 50)).thenReturn(List.of());

        assertThatThrownBy(() -> service.list(deptAScope, visitId))
                .isInstanceOfSatisfying(BusinessException.class, e ->
                        assertThat(e.errorCode()).isEqualTo(ErrorCode.VISIT_NOT_FOUND));
        verify(repo, never()).listTransfersByVisit(any(), any(Integer.class));

        assertThat(service.list(deptBScope, visitId)).isEmpty();
    }
}
