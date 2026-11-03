package com.medassist.clinical.service;

import com.medassist.clinical.domain.ClinicalModel.Consultation;
import com.medassist.clinical.domain.ClinicalModel.Dept;
import com.medassist.clinical.domain.ClinicalModel.Visit;
import com.medassist.clinical.repo.ClinicalRepository;
import com.medassist.clinical.web.ConsultationDtos.CancelConsultationReq;
import com.medassist.clinical.web.ConsultationDtos.CompleteConsultationReq;
import com.medassist.clinical.web.ConsultationDtos.CreateConsultationReq;
import com.medassist.clinical.web.ConsultationDtos.RejectConsultationReq;
import com.medassist.common.api.ErrorCode;
import com.medassist.common.exception.BusinessException;
import com.medassist.common.security.DataScope;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 会诊状态机与授权边界：主诊科室发起/撤回，受邀科室受理/拒绝/完成，越权一律 404。
 */
class ConsultationServiceTest {

    private ClinicalRepository repo;
    private ConsultationService service;

    private final UUID visitId = UUID.randomUUID();
    private final UUID deptA = UUID.randomUUID();
    private final UUID deptB = UUID.randomUUID();
    private final UUID consultationId = UUID.randomUUID();

    private final DataScope deptAScope = DataScope.ofDepartment("doctor01", deptA, true);
    private final DataScope deptBScope = DataScope.ofDepartment("doctor02", deptB, true);

    @BeforeEach
    void setUp() {
        repo = mock(ClinicalRepository.class);
        service = new ConsultationService(repo, new VisitAccessGuard(repo));
        when(repo.findDept(deptB)).thenReturn(Optional.of(
                new Dept(deptB, "D02", "心内科", "INPATIENT", true)));
        when(repo.nextConsultationNo()).thenReturn("CS20261103000001");
    }

    private Visit visit() {
        return new Visit(visitId, "V001", UUID.randomUUID(), "P20260101000001", deptA,
                "doctor01", null, "INPATIENT", Visit.IN_TREATMENT, LocalDate.now(), 0L);
    }

    private Consultation consultation(String status, long version) {
        return new Consultation(consultationId, "CS20261103000001", visitId, UUID.randomUUID(),
                "P20260101000001", deptA, deptB, "术后发热待查", status, "doctor01",
                Instant.now(), null, null, null, null, null, null, null, null, null,
                version, Instant.now());
    }

    @Test
    @DisplayName("发起：不能邀请主诊科室自己（422）")
    void requestRejectsSameDepartment() {
        when(repo.findVisit(visitId)).thenReturn(Optional.of(visit()));

        assertThatThrownBy(() -> service.request(deptAScope, visitId,
                new CreateConsultationReq(deptA, "术后发热"), "doctor01"))
                .isInstanceOfSatisfying(BusinessException.class, e ->
                        assertThat(e.errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED));
        verify(repo, never()).insertConsultation(any());
    }

    @Test
    @DisplayName("发起：跨科室正常写入，状态为 REQUESTED")
    void requestCreatesRequested() {
        when(repo.findVisit(visitId)).thenReturn(Optional.of(visit()));

        Consultation created = service.request(deptAScope, visitId,
                new CreateConsultationReq(deptB, " 术后发热 "), "doctor01");

        assertThat(created.status()).isEqualTo(Consultation.REQUESTED);
        assertThat(created.reason()).isEqualTo("术后发热");
        assertThat(created.toDeptId()).isEqualTo(deptB);
        verify(repo).insertConsultation(any());
        verify(repo).logChange(eq("doctor01"), eq("CONSULTATION_REQUEST"), eq("CONSULTATION"),
                anyString(), anyString());
    }

    @Test
    @DisplayName("发起：同一科室重复申请 → 409（唯一索引兜底）")
    void requestRejectsDuplicate() {
        when(repo.findVisit(visitId)).thenReturn(Optional.of(visit()));
        doThrow(new DataIntegrityViolationException("ux_consultation_active"))
                .when(repo).insertConsultation(any());

        assertThatThrownBy(() -> service.request(deptAScope, visitId,
                new CreateConsultationReq(deptB, "术后发热"), "doctor01"))
                .isInstanceOfSatisfying(BusinessException.class, e ->
                        assertThat(e.errorCode()).isEqualTo(ErrorCode.CONFLICT));
    }

    @Test
    @DisplayName("受理：非受邀科室 404；受邀科室受理成功")
    void acceptOnlyByInvitedDepartment() {
        when(repo.lockConsultation(consultationId))
                .thenReturn(Optional.of(consultation(Consultation.REQUESTED, 0L)));
        when(repo.acceptConsultation(consultationId, "doctor02", 0L)).thenReturn(true);
        when(repo.findConsultation(consultationId))
                .thenReturn(Optional.of(consultation(Consultation.ACCEPTED, 1L)));

        assertThatThrownBy(() -> service.accept(deptAScope, consultationId, "doctor01"))
                .isInstanceOfSatisfying(BusinessException.class, e ->
                        assertThat(e.errorCode()).isEqualTo(ErrorCode.NOT_FOUND));

        Consultation accepted = service.accept(deptBScope, consultationId, "doctor02");
        assertThat(accepted.status()).isEqualTo(Consultation.ACCEPTED);
    }

    @Test
    @DisplayName("完成：未受理不能直接完成（409）")
    void completeRequiresAccepted() {
        when(repo.lockConsultation(consultationId))
                .thenReturn(Optional.of(consultation(Consultation.REQUESTED, 0L)));

        assertThatThrownBy(() -> service.complete(deptBScope, consultationId,
                new CompleteConsultationReq("建议查血培养", 0L), "doctor02"))
                .isInstanceOfSatisfying(BusinessException.class, e ->
                        assertThat(e.errorCode()).isEqualTo(ErrorCode.CONFLICT));
        verify(repo, never()).completeConsultation(any(), anyString(), anyString(), anyLong());
    }

    @Test
    @DisplayName("拒绝：理由必填（@NotBlank 在 Controller，服务层再兜一次非空）")
    void rejectRequiresReason() {
        when(repo.lockConsultation(consultationId))
                .thenReturn(Optional.of(consultation(Consultation.REQUESTED, 0L)));

        assertThatThrownBy(() -> service.reject(deptBScope, consultationId,
                new RejectConsultationReq("  "), "doctor02"))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("撤回：只有主诊科室可撤回，且只能撤回进行中的会诊")
    void cancelOnlyByFromDepartment() {
        when(repo.lockConsultation(consultationId))
                .thenReturn(Optional.of(consultation(Consultation.ACCEPTED, 1L)));
        when(repo.cancelConsultation(consultationId, "doctor01", "患者转院", 1L)).thenReturn(true);
        when(repo.findConsultation(consultationId))
                .thenReturn(Optional.of(consultation(Consultation.CANCELLED, 2L)));

        assertThatThrownBy(() -> service.cancel(deptBScope, consultationId,
                new CancelConsultationReq("重复申请", 1L), "doctor02"))
                .isInstanceOfSatisfying(BusinessException.class, e ->
                        assertThat(e.errorCode()).isEqualTo(ErrorCode.NOT_FOUND));

        Consultation cancelled = service.cancel(deptAScope, consultationId,
                new CancelConsultationReq("患者转院", 1L), "doctor01");
        assertThat(cancelled.status()).isEqualTo(Consultation.CANCELLED);
    }

    @Test
    @DisplayName("按就诊查会诊：主诊科室可见，无关科室 404")
    void listByVisitScopesByDepartment() {
        when(repo.findVisit(visitId)).thenReturn(Optional.of(visit()));
        when(repo.listConsultationsByVisit(eq(visitId), any(Integer.class)))
                .thenReturn(List.of(consultation(Consultation.REQUESTED, 0L)));
        when(repo.existsActiveConsultation(eq(visitId), any()))
                .thenReturn(false);

        assertThat(service.listByVisit(deptAScope, visitId)).hasSize(1);
        assertThatThrownBy(() -> service.listByVisit(deptBScope, visitId))
                .isInstanceOfSatisfying(BusinessException.class, e ->
                        assertThat(e.errorCode()).isEqualTo(ErrorCode.VISIT_NOT_FOUND));
    }
}
