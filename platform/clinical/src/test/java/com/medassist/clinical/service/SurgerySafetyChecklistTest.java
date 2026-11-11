package com.medassist.clinical.service;

import com.medassist.clinical.config.SurgeryProperties;
import com.medassist.clinical.domain.ClinicalModel.Admission;
import com.medassist.clinical.domain.ClinicalModel.SurgeryCase;
import com.medassist.clinical.domain.ClinicalModel.SurgerySafetyCheck;
import com.medassist.clinical.domain.ClinicalModel.Visit;
import com.medassist.clinical.repo.ClinicalRepository;
import com.medassist.clinical.web.SurgerySafetyDtos.SafetyCheckReq;
import com.medassist.common.api.ErrorCode;
import com.medassist.common.exception.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

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
 * 手术安全核查（§265）：模板校验、时点顺序、开台/离室门禁。
 */
class SurgerySafetyChecklistTest {

    private ClinicalRepository repo;
    private SurgeryService service;

    private final UUID visitId = UUID.randomUUID();
    private final UUID caseId = UUID.randomUUID();
    private final UUID patientId = UUID.randomUUID();

    private static final List<String> PRE_ITEMS = List.of("PATIENT_ID", "SITE_MARK",
            "PROCEDURE_CONFIRM", "ANESTHESIA_CONSENT", "ALLERGY", "AIRWAY",
            "BLOOD_LOSS_RISK", "IMAGING", "EQUIPMENT");
    private static final List<String> TIMEOUT_ITEMS = List.of("TEAM_INTRO",
            "PATIENT_SITE_PROCEDURE", "ANTIBIOTIC_PROPHYLAXIS", "IMAGING_DISPLAYED",
            "STERILITY");
    private static final List<String> SIGNOUT_ITEMS = List.of("PROCEDURE_RECORDED",
            "SPECIMEN_LABELED", "INSTRUMENT_COUNT", "RECOVERY_PLAN", "BLOOD_LOSS_RECORDED");

    @BeforeEach
    void setUp() {
        repo = mock(ClinicalRepository.class);
        service = new SurgeryService(repo, mock(SurgeryLevelService.class),
                mock(PreopVisitService.class), new SurgeryProperties(true));
    }

    private SurgeryCase surgeryCase(String status) {
        return new SurgeryCase(caseId, "OP20261101000001", visitId, UUID.randomUUID(), patientId,
                "47.0901", "阑尾切除术", Instant.now(), null, null, "doctor01", null,
                "GENERAL", "II", "II", status, null, "doctor01", 0L, Instant.now());
    }

    private SurgerySafetyCheck row(String phase, boolean complete) {
        String items = "[{\"code\":\"PATIENT_ID\",\"label\":\"身份\",\"required\":true,"
                + "\"checked\":" + complete + "}]";
        return new SurgerySafetyCheck(UUID.randomUUID(), caseId, phase, "doctor01",
                Instant.now(), items, complete ? "[]" : "[\"PATIENT_ID\"]", null, complete,
                Instant.now());
    }

    @Test
    @DisplayName("提交麻醉前核查：必需项齐全 → complete=true 并留审计")
    void submitPreAnesthesiaComplete() {
        when(repo.lockSurgeryCase(caseId)).thenReturn(Optional.of(
                surgeryCase(SurgeryCase.PLANNED)));
        when(repo.listSurgerySafetyChecks(caseId)).thenReturn(List.of());

        service.submitSafetyCheck(visitId, caseId,
                new SafetyCheckReq("PRE_ANESTHESIA", PRE_ITEMS, null), "doctor01");

        ArgumentCaptor<SurgerySafetyCheck> captor =
                ArgumentCaptor.forClass(SurgerySafetyCheck.class);
        verify(repo).insertSurgerySafetyCheck(captor.capture());
        assertThat(captor.getValue().phase()).isEqualTo(SurgerySafetyCheck.PRE_ANESTHESIA);
        assertThat(captor.getValue().complete()).isTrue();
        assertThat(captor.getValue().missingItems()).isEqualTo("[]");
        verify(repo).logChange(eq("doctor01"), eq("SURGERY_SAFETY_CHECK"),
                eq("SURGERY_CASE"), eq(caseId.toString()), anyString());
    }

    @Test
    @DisplayName("提交核查：缺必需项 → complete=false（仍留证，不阻断提交）")
    void missingRequiredItemsMarksIncomplete() {
        when(repo.lockSurgeryCase(caseId)).thenReturn(Optional.of(
                surgeryCase(SurgeryCase.PLANNED)));
        when(repo.listSurgerySafetyChecks(caseId)).thenReturn(List.of());

        service.submitSafetyCheck(visitId, caseId,
                new SafetyCheckReq("PRE_ANESTHESIA", List.of("PATIENT_ID"), null), "doctor01");

        ArgumentCaptor<SurgerySafetyCheck> captor =
                ArgumentCaptor.forClass(SurgerySafetyCheck.class);
        verify(repo).insertSurgerySafetyCheck(captor.capture());
        assertThat(captor.getValue().complete()).isFalse();
        assertThat(captor.getValue().missingItems()).contains("SITE_MARK");
    }

    @Test
    @DisplayName("模板外核查项 → 42412（防止客户端自定义标签）")
    void unknownItemRejected() {
        when(repo.lockSurgeryCase(caseId)).thenReturn(Optional.of(
                surgeryCase(SurgeryCase.PLANNED)));

        assertThatThrownBy(() -> service.submitSafetyCheck(visitId, caseId,
                new SafetyCheckReq("PRE_ANESTHESIA", List.of("PATIENT_ID", "SELF_DEFINED"),
                        null), "doctor01"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.SURGERY_SAFETY_CHECKLIST_INVALID);
        verify(repo, never()).insertSurgerySafetyCheck(any());
    }

    @Test
    @DisplayName("时序：麻醉前未完成不能提交手术开始前核查 → 42411")
    void timeOutRequiresPreAnesthesia() {
        when(repo.lockSurgeryCase(caseId)).thenReturn(Optional.of(
                surgeryCase(SurgeryCase.PLANNED)));
        when(repo.listSurgerySafetyChecks(caseId)).thenReturn(List.of(
                row(SurgerySafetyCheck.PRE_ANESTHESIA, false)));

        assertThatThrownBy(() -> service.submitSafetyCheck(visitId, caseId,
                new SafetyCheckReq("TIME_OUT", TIMEOUT_ITEMS, null), "doctor01"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.SURGERY_SAFETY_CHECKLIST_REQUIRED);
    }

    @Test
    @DisplayName("离室前核查只能在手术进行中提交 → 42412")
    void signOutRequiresInProgress() {
        when(repo.lockSurgeryCase(caseId)).thenReturn(Optional.of(
                surgeryCase(SurgeryCase.PLANNED)));

        assertThatThrownBy(() -> service.submitSafetyCheck(visitId, caseId,
                new SafetyCheckReq("SIGN_OUT", SIGNOUT_ITEMS, null), "doctor01"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.SURGERY_SAFETY_CHECKLIST_INVALID);
    }

    @Test
    @DisplayName("开台门禁：麻醉前或手术开始前未完成 → 42411，不改状态")
    void startBlockedWithoutChecklist() {
        // 先通过分级/访视/同意书门槛
        when(repo.lockSurgeryCase(caseId)).thenReturn(Optional.of(
                surgeryCase(SurgeryCase.PLANNED)));
        when(repo.listActiveConsentTypes(caseId)).thenReturn(List.of("SURGERY", "ANESTHESIA"));
        when(repo.listSurgerySafetyChecks(caseId)).thenReturn(List.of(
                row(SurgerySafetyCheck.PRE_ANESTHESIA, true)));

        assertThatThrownBy(() -> service.start(visitId, caseId, 0L, "doctor01"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.SURGERY_SAFETY_CHECKLIST_REQUIRED);
        verify(repo, never()).startSurgery(any(), any(), anyLong());
    }

    @Test
    @DisplayName("离室门禁：未完成离室核查 → 42411")
    void completeBlockedWithoutSignOut() {
        when(repo.lockSurgeryCase(caseId)).thenReturn(Optional.of(
                surgeryCase(SurgeryCase.IN_PROGRESS)));
        when(repo.listSurgerySafetyChecks(caseId)).thenReturn(List.of());

        assertThatThrownBy(() -> service.complete(visitId, caseId, 1L, "doctor01"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.SURGERY_SAFETY_CHECKLIST_REQUIRED);
        verify(repo, never()).completeSurgery(any(), any(), anyLong());
    }

    @Test
    @DisplayName("汇总视图：从未提交时三个时点都为未完成，并给出模板")
    void emptyChecklistView() {
        when(repo.findSurgeryCase(caseId)).thenReturn(Optional.of(
                surgeryCase(SurgeryCase.PLANNED)));
        when(repo.listSurgerySafetyChecks(caseId)).thenReturn(List.of());

        var view = service.checklist(visitId, caseId);

        assertThat(view.phases()).hasSize(3);
        assertThat(view.readyToStart()).isFalse();
        assertThat(view.readyToComplete()).isFalse();
        assertThat(view.phases().get(0).items()).hasSize(PRE_ITEMS.size());
        assertThat(view.phases().get(0).missingItems()).containsExactlyElementsOf(PRE_ITEMS);
    }

    @Test
    @DisplayName("汇总视图：三时点均完成 → readyToStart/readyToComplete 均为 true")
    void readyFlags() {
        when(repo.findSurgeryCase(caseId)).thenReturn(Optional.of(
                surgeryCase(SurgeryCase.IN_PROGRESS)));
        when(repo.listSurgerySafetyChecks(caseId)).thenReturn(List.of(
                row(SurgerySafetyCheck.PRE_ANESTHESIA, true),
                row(SurgerySafetyCheck.TIME_OUT, true),
                row(SurgerySafetyCheck.SIGN_OUT, true)));

        var view = service.checklist(visitId, caseId);

        assertThat(view.readyToStart()).isTrue();
        assertThat(view.readyToComplete()).isTrue();
        assertThat(view.caseId()).isEqualTo(caseId);
    }

    @Test
    @DisplayName("跨就诊访问核查 → 404")
    void crossVisitRejected() {
        when(repo.findSurgeryCase(caseId)).thenReturn(Optional.of(
                surgeryCase(SurgeryCase.PLANNED)));

        assertThatThrownBy(() -> service.checklist(UUID.randomUUID(), caseId))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.SURGERY_CASE_NOT_FOUND);
    }
}
