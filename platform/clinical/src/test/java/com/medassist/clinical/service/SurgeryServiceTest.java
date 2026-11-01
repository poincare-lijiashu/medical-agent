package com.medassist.clinical.service;

import com.medassist.clinical.domain.ClinicalModel.Admission;
import com.medassist.clinical.domain.ClinicalModel.OperationCatalog;
import com.medassist.clinical.domain.ClinicalModel.SurgeryCase;
import com.medassist.clinical.domain.ClinicalModel.Visit;
import com.medassist.clinical.repo.ClinicalRepository;
import com.medassist.clinical.web.SurgeryDtos.CancelSurgeryReq;
import com.medassist.clinical.web.SurgeryDtos.PlanSurgeryReq;
import com.medassist.common.api.ErrorCode;
import com.medassist.common.exception.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 手术麻醉域规则：只住院可排手术、字典必须启用、同一手术不重复、状态机 CAS、取消必须写原因。
 */
class SurgeryServiceTest {

    private ClinicalRepository repo;
    private SurgeryLevelService levelService;
    private SurgeryService service;

    private final UUID visitId = UUID.randomUUID();
    private final UUID caseId = UUID.randomUUID();
    private final UUID patientId = UUID.randomUUID();
    private final UUID admissionId = UUID.randomUUID();
    private final String code = "47.0901";

    private final OperationCatalog operation = new OperationCatalog(code, "阑尾切除术", "普外科",
            true, 0L);

    @BeforeEach
    void setUp() {
        repo = mock(ClinicalRepository.class);
        levelService = mock(SurgeryLevelService.class);
        service = new SurgeryService(repo, levelService);
    }

    private Visit visit(String visitType) {
        return new Visit(visitId, "V1", patientId, "P1", UUID.randomUUID(), "doctor01",
                null, visitType, Visit.IN_TREATMENT, LocalDate.now(), 0L);
    }

    private Admission admission() {
        return new Admission(admissionId, "A001", visitId, patientId, "P1",
                UUID.randomUUID(), UUID.randomUUID(), "doctor01", Admission.IN_HOSPITAL,
                Instant.now(), null, 0L);
    }

    private SurgeryCase surgeryCase(String status) {
        return new SurgeryCase(caseId, "OP20261101000001", visitId, admissionId, patientId,
                code, "阑尾切除术", Instant.now(), null, null, "doctor01", null,
                "GENERAL", "II", "II", status, null, "doctor01", 0L, Instant.now());
    }

    private PlanSurgeryReq req() {
        return new PlanSurgeryReq(code, Instant.now(), "doctor01", null, "general", "ii", "ii");
    }

    // ---------------------------------------------------------------- 计划

    @Test
    @DisplayName("计划手术：快照字典名称、填充住院号、状态 PLANNED 并留审计")
    void planSnapshotsCatalogName() {
        when(repo.findVisit(visitId)).thenReturn(Optional.of(visit("INPATIENT")));
        when(repo.findOperation(code)).thenReturn(Optional.of(operation));
        when(repo.findLatestAdmissionByVisit(visitId)).thenReturn(Optional.of(admission()));
        when(repo.nextSurgeryCaseNo()).thenReturn("OP20261101000001");

        SurgeryCase created = service.plan(visitId, req(), "doctor01");

        ArgumentCaptor<SurgeryCase> captor = ArgumentCaptor.forClass(SurgeryCase.class);
        verify(repo).insertSurgeryCase(captor.capture());
        SurgeryCase saved = captor.getValue();
        assertThat(saved.operationName()).isEqualTo("阑尾切除术");
        assertThat(saved.admissionId()).isEqualTo(admissionId);
        assertThat(saved.status()).isEqualTo(SurgeryCase.PLANNED);
        assertThat(saved.anesthesiaType()).isEqualTo("GENERAL");
        assertThat(saved.incisionLevel()).isEqualTo("II");
        assertThat(created.caseNo()).isEqualTo("OP20261101000001");
        verify(repo).logChange(eq("doctor01"), eq("SURGERY_PLAN"), eq("SURGERY_CASE"),
                eq(created.id().toString()), anyString());
    }

    @Test
    @DisplayName("计划手术：门诊就诊不能排手术 → 422")
    void planRejectsOutpatient() {
        when(repo.findVisit(visitId)).thenReturn(Optional.of(visit("OUTPATIENT")));

        assertThatThrownBy(() -> service.plan(visitId, req(), "doctor01"))
                .isInstanceOfSatisfying(BusinessException.class, e ->
                        assertThat(e.errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED));
        verify(repo, never()).insertSurgeryCase(any());
    }

    @Test
    @DisplayName("计划手术：编码不存在 → 404")
    void planRejectsUnknownCode() {
        when(repo.findVisit(visitId)).thenReturn(Optional.of(visit("INPATIENT")));
        when(repo.findOperation(code)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.plan(visitId, req(), "doctor01"))
                .isInstanceOfSatisfying(BusinessException.class, e ->
                        assertThat(e.errorCode()).isEqualTo(ErrorCode.NOT_FOUND));
    }

    @Test
    @DisplayName("计划手术：字典已停用 → 422（历史记录仍保留名称快照）")
    void planRejectsDisabledCode() {
        when(repo.findVisit(visitId)).thenReturn(Optional.of(visit("INPATIENT")));
        when(repo.findOperation(code)).thenReturn(Optional.of(new OperationCatalog(code,
                "阑尾切除术", "普外科", false, 1L)));

        assertThatThrownBy(() -> service.plan(visitId, req(), "doctor01"))
                .isInstanceOfSatisfying(BusinessException.class, e ->
                        assertThat(e.errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED));
    }

    @Test
    @DisplayName("计划手术：同一就诊同一手术未取消重复录入 → 409（唯一索引兜底）")
    void planDuplicateActiveConflicts() {
        when(repo.findVisit(visitId)).thenReturn(Optional.of(visit("INPATIENT")));
        when(repo.findOperation(code)).thenReturn(Optional.of(operation));
        when(repo.nextSurgeryCaseNo()).thenReturn("OP20261101000002");
        org.mockito.Mockito.doThrow(new DataIntegrityViolationException("ux_surgery_case_active"))
                .when(repo).insertSurgeryCase(any());

        assertThatThrownBy(() -> service.plan(visitId, req(), "doctor01"))
                .isInstanceOfSatisfying(BusinessException.class, e ->
                        assertThat(e.errorCode()).isEqualTo(ErrorCode.CONFLICT));
        verify(repo, never()).logChange(anyString(), anyString(), anyString(), anyString(),
                anyString());
    }

    // ---------------------------------------------------------------- 状态机

    @Test
    @DisplayName("开始手术：CAS 成功并留审计")
    void startOk() {
        when(repo.lockSurgeryCase(caseId))
                .thenReturn(Optional.of(surgeryCase(SurgeryCase.PLANNED)))
                .thenReturn(Optional.of(surgeryCase(SurgeryCase.IN_PROGRESS)));
        when(repo.listActiveConsentTypes(caseId)).thenReturn(List.of("SURGERY", "ANESTHESIA"));
        when(repo.startSurgery(eq(caseId), any(Instant.class), eq(0L))).thenReturn(true);

        SurgeryCase result = service.start(visitId, caseId, 0L, "doctor01");

        assertThat(result.status()).isEqualTo(SurgeryCase.IN_PROGRESS);
        verify(levelService).requireStartApproved(caseId);
        verify(repo).logChange(eq("doctor01"), eq("SURGERY_START"), eq("SURGERY_CASE"),
                eq(caseId.toString()), anyString());
    }

    @Test
    @DisplayName("开始手术：版本已变化 → 409，不写审计")
    void startStaleVersion() {
        when(repo.lockSurgeryCase(caseId))
                .thenReturn(Optional.of(surgeryCase(SurgeryCase.PLANNED)));
        when(repo.listActiveConsentTypes(caseId)).thenReturn(List.of("SURGERY", "ANESTHESIA"));
        when(repo.startSurgery(eq(caseId), any(Instant.class), eq(0L))).thenReturn(false);

        assertThatThrownBy(() -> service.start(visitId, caseId, 0L, "doctor01"))
                .isInstanceOfSatisfying(BusinessException.class, e ->
                        assertThat(e.errorCode()).isEqualTo(ErrorCode.CONFLICT));
        verify(repo, never()).logChange(anyString(), anyString(), anyString(), anyString(),
                anyString());
    }

    @Test
    @DisplayName("开始手术：缺手术/麻醉知情同意 → 422（开台硬门槛）")
    void startRejectsMissingConsents() {
        when(repo.lockSurgeryCase(caseId))
                .thenReturn(Optional.of(surgeryCase(SurgeryCase.PLANNED)));
        when(repo.listActiveConsentTypes(caseId)).thenReturn(List.of("SURGERY"));

        assertThatThrownBy(() -> service.start(visitId, caseId, 0L, "doctor01"))
                .isInstanceOfSatisfying(BusinessException.class, e -> {
                    assertThat(e.errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED);
                    assertThat(e.getMessage()).contains("麻醉知情同意");
                });
        verify(repo, never()).startSurgery(any(), any(), anyLong());
    }

    @Test
    @DisplayName("开始手术：分级门槛未通过 → 422，且不再校验同意书/不改状态")
    void startRejectsUnapprovedLevel() {
        when(repo.lockSurgeryCase(caseId))
                .thenReturn(Optional.of(surgeryCase(SurgeryCase.PLANNED)));
        org.mockito.Mockito.doThrow(new BusinessException(ErrorCode.VALIDATION_FAILED,
                        "开台前必须完成手术分级（当前未分级）"))
                .when(levelService).requireStartApproved(caseId);

        assertThatThrownBy(() -> service.start(visitId, caseId, 0L, "doctor01"))
                .isInstanceOfSatisfying(BusinessException.class, e -> {
                    assertThat(e.errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED);
                    assertThat(e.getMessage()).contains("手术分级");
                });
        verify(repo, never()).listActiveConsentTypes(any());
        verify(repo, never()).startSurgery(any(), any(), anyLong());
    }

    @Test
    @DisplayName("完成手术：只有进行中可以完成")
    void completeOk() {
        when(repo.lockSurgeryCase(caseId))
                .thenReturn(Optional.of(surgeryCase(SurgeryCase.IN_PROGRESS)))
                .thenReturn(Optional.of(surgeryCase(SurgeryCase.COMPLETED)));
        when(repo.completeSurgery(eq(caseId), any(Instant.class), eq(1L))).thenReturn(true);

        SurgeryCase result = service.complete(visitId, caseId, 1L, "doctor01");

        assertThat(result.status()).isEqualTo(SurgeryCase.COMPLETED);
        verify(repo).logChange(eq("doctor01"), eq("SURGERY_COMPLETE"), eq("SURGERY_CASE"),
                eq(caseId.toString()), anyString());
    }

    @Test
    @DisplayName("完成手术：计划态不能直接完成 → 409")
    void completeRejectsPlanned() {
        when(repo.lockSurgeryCase(caseId))
                .thenReturn(Optional.of(surgeryCase(SurgeryCase.PLANNED)));
        when(repo.completeSurgery(eq(caseId), any(Instant.class), eq(0L))).thenReturn(false);

        assertThatThrownBy(() -> service.complete(visitId, caseId, 0L, "doctor01"))
                .isInstanceOfSatisfying(BusinessException.class, e ->
                        assertThat(e.errorCode()).isEqualTo(ErrorCode.CONFLICT));
    }

    @Test
    @DisplayName("取消手术：必须填写原因 → 422")
    void cancelRequiresReason() {
        when(repo.lockSurgeryCase(caseId))
                .thenReturn(Optional.of(surgeryCase(SurgeryCase.PLANNED)));

        assertThatThrownBy(() -> service.cancel(visitId, caseId,
                new CancelSurgeryReq(0L, "  "), "doctor01"))
                .isInstanceOfSatisfying(BusinessException.class, e ->
                        assertThat(e.errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED));
        verify(repo, never()).cancelSurgery(any(), anyString(), anyLong());
    }

    @Test
    @DisplayName("取消手术：CAS 成功，审计带原因")
    void cancelOk() {
        when(repo.lockSurgeryCase(caseId))
                .thenReturn(Optional.of(surgeryCase(SurgeryCase.PLANNED)))
                .thenReturn(Optional.of(surgeryCase(SurgeryCase.CANCELLED)));
        when(repo.cancelSurgery(caseId, "患者拒绝手术", 0L)).thenReturn(true);

        SurgeryCase result = service.cancel(visitId, caseId,
                new CancelSurgeryReq(0L, "患者拒绝手术"), "doctor01");

        assertThat(result.status()).isEqualTo(SurgeryCase.CANCELLED);
        verify(repo).logChange(eq("doctor01"), eq("SURGERY_CANCEL"), eq("SURGERY_CASE"),
                eq(caseId.toString()), anyString());
    }

    @Test
    @DisplayName("跨就诊访问手术记录 → 404（防越权）")
    void rejectCaseOfAnotherVisit() {
        when(repo.lockSurgeryCase(caseId))
                .thenReturn(Optional.of(surgeryCase(SurgeryCase.PLANNED)));

        assertThatThrownBy(() -> service.start(UUID.randomUUID(), caseId, 0L, "doctor01"))
                .isInstanceOfSatisfying(BusinessException.class, e ->
                        assertThat(e.errorCode()).isEqualTo(ErrorCode.NOT_FOUND));
    }

    @Test
    @DisplayName("手术编码搜索：limit 上限 100，默认 20")
    void catalogLimits() {
        when(repo.searchOperationCatalog("阑尾", 20)).thenReturn(List.of(operation));
        when(repo.searchOperationCatalog(null, 100)).thenReturn(List.of(operation));

        assertThat(service.catalog("阑尾", null)).hasSize(1);
        assertThat(service.catalog(null, 500)).hasSize(1);
        verify(repo).searchOperationCatalog("阑尾", 20);
        verify(repo).searchOperationCatalog(null, 100);
    }
}
