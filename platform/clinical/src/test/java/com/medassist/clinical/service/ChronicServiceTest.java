package com.medassist.clinical.service;

import com.medassist.clinical.client.PatientClient;
import com.medassist.clinical.domain.ClinicalModel.ChronicDisease;
import com.medassist.clinical.domain.ClinicalModel.ChronicFollowupPlan;
import com.medassist.clinical.domain.ClinicalModel.ChronicFollowupTask;
import com.medassist.clinical.domain.ClinicalModel.ChronicRecord;
import com.medassist.clinical.repo.ClinicalRepository;
import com.medassist.clinical.web.ChronicDtos.CompleteTaskReq;
import com.medassist.clinical.web.ChronicDtos.CreatePlanReq;
import com.medassist.clinical.web.ChronicDtos.CreateRecordReq;
import com.medassist.clinical.web.ChronicDtos.UpdateRiskReq;
import com.medassist.common.api.ErrorCode;
import com.medassist.common.exception.BusinessException;
import com.medassist.common.time.BusinessTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 随访与慢病：建档、计划、任务完成与结案状态机。
 */
class ChronicServiceTest {

    private ClinicalRepository repo;
    private PatientClient patientClient;
    private ChronicService service;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final UUID patientId = UUID.randomUUID();
    private final UUID diseaseId = UUID.randomUUID();
    private final UUID recordId = UUID.randomUUID();
    private final UUID planId = UUID.randomUUID();
    private final UUID taskId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        repo = mock(ClinicalRepository.class);
        patientClient = mock(PatientClient.class);
        service = new ChronicService(repo, patientClient, objectMapper);
    }

    private ChronicDisease disease(boolean enabled) {
        return new ChronicDisease(diseaseId, "CD-HYP", "高血压", enabled);
    }

    private ChronicRecord record(String status, long version) {
        return new ChronicRecord(recordId, "CR20261104000001", patientId, "P001", "张三", "MALE",
                LocalDate.of(1960, 1, 1), diseaseId, "CD-HYP", "高血压",
                LocalDate.of(2020, 5, 1), "HIGH", status, null, null, "doctor01", null,
                null, null, null, "doctor01", version, Instant.now(), Instant.now());
    }

    private ChronicFollowupPlan plan(String status, long version) {
        return new ChronicFollowupPlan(planId, "CFP20261104000001", recordId, 30,
                BusinessTime.today().plusDays(30), status, null, null, null, "doctor01",
                version, Instant.now());
    }

    private ChronicFollowupTask task(UUID plan, String status, LocalDate dueDate, long version) {
        return new ChronicFollowupTask(taskId, "CFT20261104000001", plan, recordId, patientId,
                "P001", "张三", "高血压", dueDate, status, null, null, null, null, null,
                Instant.now(), version);
    }

    private void stubDetail() {
        when(repo.findChronicRecord(any())).thenReturn(Optional.of(
                record(ChronicRecord.ACTIVE, 0)));
        when(repo.findActivePlanByChronic(any())).thenReturn(Optional.of(
                plan(ChronicFollowupPlan.ACTIVE, 0)));
        when(repo.findPendingChronicTask(any())).thenReturn(Optional.of(
                task(planId, ChronicFollowupTask.PENDING, BusinessTime.today(), 0)));
        when(repo.listChronicFollowups(any(), eq(50))).thenReturn(List.of());
    }

    @Test
    @DisplayName("建档：诊断日期/风险等级校验、病种停用、重复在管档案")
    void createRecordRules() {
        CreateRecordReq future = new CreateRecordReq(patientId, diseaseId,
                BusinessTime.today().plusDays(1), "HIGH", null, null, null);
        assertThatThrownBy(() -> service.createRecord(future, "doctor01"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.VALIDATION_FAILED);

        CreateRecordReq badRisk = new CreateRecordReq(patientId, diseaseId,
                BusinessTime.today(), "UNKNOWN", null, null, null);
        assertThatThrownBy(() -> service.createRecord(badRisk, "doctor01"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.VALIDATION_FAILED);

        when(repo.findChronicDisease(diseaseId)).thenReturn(Optional.of(disease(false)));
        CreateRecordReq ok = new CreateRecordReq(patientId, diseaseId, BusinessTime.today(),
                "HIGH", null, null, null);
        assertThatThrownBy(() -> service.createRecord(ok, "doctor01"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.CHRONIC_DISEASE_NOT_FOUND);

        when(repo.findChronicDisease(diseaseId)).thenReturn(Optional.of(disease(true)));
        when(repo.findActiveChronicRecord(patientId, diseaseId)).thenReturn(Optional.of(
                record(ChronicRecord.ACTIVE, 0)));
        assertThatThrownBy(() -> service.createRecord(ok, "doctor01"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.CHRONIC_RECORD_DUPLICATE);
    }

    @Test
    @DisplayName("建档成功后写入患者人口学快照")
    void createRecordSnapshotsPatient() {
        when(repo.findChronicDisease(diseaseId)).thenReturn(Optional.of(disease(true)));
        when(repo.findActiveChronicRecord(patientId, diseaseId)).thenReturn(Optional.empty());
        when(patientClient.requireProfile(patientId)).thenReturn(
                new PatientClient.PatientProfile(patientId, "P001", "张三", "MALE",
                        LocalDate.of(1960, 1, 1)));
        when(repo.nextChronicRecordNo()).thenReturn("CR20261104000001");
        stubDetail();

        var view = service.createRecord(new CreateRecordReq(patientId, diseaseId,
                BusinessTime.today(), "HIGH", null, "doctor01", "门诊随访"), "doctor01");

        assertThat(view.record().status()).isEqualTo(ChronicRecord.ACTIVE);
        verify(repo).insertChronicRecord(any());
    }

    @Test
    @DisplayName("计划：非在管/已有计划/间隔越界/日期早于今天全部拒绝")
    void createPlanRules() {
        when(repo.lockChronicRecord(recordId)).thenReturn(Optional.of(
                record(ChronicRecord.CLOSED, 1)));
        assertThatThrownBy(() -> service.createPlan(recordId, new CreatePlanReq(30, null),
                "doctor01"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.CHRONIC_RECORD_STATUS_INVALID);

        when(repo.lockChronicRecord(recordId)).thenReturn(Optional.of(
                record(ChronicRecord.ACTIVE, 0)));
        when(repo.findActivePlanByChronic(recordId)).thenReturn(Optional.of(
                plan(ChronicFollowupPlan.ACTIVE, 0)));
        assertThatThrownBy(() -> service.createPlan(recordId, new CreatePlanReq(30, null),
                "doctor01"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.CHRONIC_PLAN_EXISTS);

        when(repo.findActivePlanByChronic(recordId)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.createPlan(recordId, new CreatePlanReq(6, null),
                "doctor01"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.CHRONIC_PLAN_INVALID);
        assertThatThrownBy(() -> service.createPlan(recordId,
                new CreatePlanReq(30, BusinessTime.today().minusDays(1)), "doctor01"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.CHRONIC_PLAN_INVALID);
    }

    @Test
    @DisplayName("计划创建成功：写入计划与首个 PENDING 任务")
    void createPlanInsertsFirstTask() {
        when(repo.lockChronicRecord(recordId)).thenReturn(Optional.of(
                record(ChronicRecord.ACTIVE, 0)));
        when(repo.findActivePlanByChronic(any())).thenReturn(Optional.empty());
        when(repo.nextChronicPlanNo()).thenReturn("CFP20261104000001");
        when(repo.nextChronicTaskNo()).thenReturn("CFT20261104000001");
        when(repo.findChronicRecord(any())).thenReturn(Optional.of(
                record(ChronicRecord.ACTIVE, 0)));
        when(repo.findPendingChronicTask(any())).thenReturn(Optional.empty());
        when(repo.listChronicFollowups(any(), eq(50))).thenReturn(List.of());

        service.createPlan(recordId, new CreatePlanReq(30, null), "doctor01");

        verify(repo).insertChronicPlan(any());
        verify(repo).insertChronicTask(any());
    }

    @Test
    @DisplayName("完成随访：血压必须成对且至少一项体征/症状/建议")
    void completeTaskValidation() {
        when(repo.findChronicTask(taskId)).thenReturn(Optional.of(
                task(planId, ChronicFollowupTask.PENDING, BusinessTime.today(), 0)));
        when(repo.lockChronicPlan(planId)).thenReturn(Optional.of(
                plan(ChronicFollowupPlan.ACTIVE, 0)));
        when(repo.lockChronicTask(taskId)).thenReturn(Optional.of(
                task(planId, ChronicFollowupTask.PENDING, BusinessTime.today(), 0)));

        assertThatThrownBy(() -> service.completeTask(taskId,
                new CompleteTaskReq(BusinessTime.today(), "PHONE", 130, null, null, null,
                        null, null, null), "nurse01"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.CHRONIC_FOLLOWUP_INVALID);

        assertThatThrownBy(() -> service.completeTask(taskId,
                new CompleteTaskReq(BusinessTime.today(), "PHONE", null, null, null, null,
                        null, null, null), "nurse01"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.CHRONIC_FOLLOWUP_INVALID);

        assertThatThrownBy(() -> service.completeTask(taskId,
                new CompleteTaskReq(BusinessTime.today(), "PHONE", 120, 80, null, null,
                        null, "UNKNOWN", null), "nurse01"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.CHRONIC_FOLLOWUP_INVALID);
    }

    @Test
    @DisplayName("完成随访成功：写记录、任务 DONE、计划前移并生成下一任务")
    void completeTaskAdvancesPlan() {
        LocalDate today = BusinessTime.today();
        when(repo.findChronicTask(taskId)).thenReturn(Optional.of(
                task(planId, ChronicFollowupTask.PENDING, today, 0)));
        when(repo.lockChronicPlan(planId)).thenReturn(Optional.of(
                plan(ChronicFollowupPlan.ACTIVE, 0)));
        when(repo.lockChronicTask(taskId)).thenReturn(Optional.of(
                task(planId, ChronicFollowupTask.PENDING, today, 0)));
        when(repo.findChronicRecord(recordId)).thenReturn(Optional.of(
                record(ChronicRecord.ACTIVE, 0)));
        when(repo.nextChronicFollowupNo()).thenReturn("CFF20261104000001");
        when(repo.nextChronicTaskNo()).thenReturn("CFT20261104000002");
        when(repo.completeChronicTask(eq(taskId), any(UUID.class), eq(0L))).thenReturn(true);
        when(repo.updateChronicPlanNextDate(eq(planId), eq(today.plusDays(30)), eq(0L)))
                .thenReturn(true);
        stubDetail();

        service.completeTask(taskId, new CompleteTaskReq(today, "OUTPATIENT", 128, 78,
                new BigDecimal("6.1"), new BigDecimal("68.5"), "无明显不适", "GOOD",
                "继续低盐饮食"), "doctor01");

        verify(repo).insertChronicFollowup(any());
        verify(repo).completeChronicTask(eq(taskId), any(UUID.class), eq(0L));
        verify(repo).updateChronicPlanNextDate(planId, today.plusDays(30), 0);
        verify(repo).insertChronicTask(any());
    }

    @Test
    @DisplayName("停止计划：原因必填，成功后取消待随访任务")
    void stopPlanRules() {
        when(repo.lockChronicPlan(planId)).thenReturn(Optional.of(
                plan(ChronicFollowupPlan.ACTIVE, 0)));
        assertThatThrownBy(() -> service.stopPlan(planId, " ", "doctor01"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.CHRONIC_STOP_REASON_REQUIRED);

        when(repo.stopChronicPlan(planId, "患者失访", "doctor01", 0)).thenReturn(true);
        when(repo.cancelPendingChronicTasks(planId, "患者失访", "doctor01")).thenReturn(1);
        stubDetail();

        service.stopPlan(planId, "患者失访", "doctor01");
        verify(repo).stopChronicPlan(planId, "患者失访", "doctor01", 0);
        verify(repo).cancelPendingChronicTasks(planId, "患者失访", "doctor01");
    }

    @Test
    @DisplayName("结案：原因必填，成功后停计划并关闭档案")
    void closeRecordRules() {
        when(repo.lockChronicRecord(recordId)).thenReturn(Optional.of(
                record(ChronicRecord.ACTIVE, 0)));
        assertThatThrownBy(() -> service.closeRecord(recordId, null, "doctor01"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.CHRONIC_CLOSE_REASON_REQUIRED);

        when(repo.findActivePlanByChronic(recordId)).thenReturn(Optional.of(
                plan(ChronicFollowupPlan.ACTIVE, 0)));
        when(repo.stopChronicPlan(planId, "转上级医院", "doctor01", 0)).thenReturn(true);
        when(repo.cancelPendingChronicTasks(planId, "转上级医院", "doctor01")).thenReturn(1);
        when(repo.closeChronicRecord(recordId, "转上级医院", "doctor01", 0)).thenReturn(true);
        stubDetail();

        service.closeRecord(recordId, "转上级医院", "doctor01");
        verify(repo).closeChronicRecord(recordId, "转上级医院", "doctor01", 0);
    }

    @Test
    @DisplayName("风险等级更新：非法值 422，非在管 409")
    void updateRiskRules() {
        assertThatThrownBy(() -> service.updateRisk(recordId, new UpdateRiskReq("UNKNOWN"),
                "doctor01"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.VALIDATION_FAILED);

        when(repo.lockChronicRecord(recordId)).thenReturn(Optional.of(
                record(ChronicRecord.CLOSED, 1)));
        assertThatThrownBy(() -> service.updateRisk(recordId, new UpdateRiskReq("LOW"),
                "doctor01"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.CHRONIC_RECORD_STATUS_INVALID);
        verify(repo, never()).updateChronicRisk(any(), any(), anyLong());
    }

    @Test
    @DisplayName("列表：状态非法 422")
    void listValidatesStatus() {
        assertThatThrownBy(() -> service.listRecords("UNKNOWN", null, null, 10))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
        assertThatThrownBy(() -> service.listTasks("UNKNOWN", null, 10))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
        verify(repo, never()).listChronicRecords(any(), any(), any(), anyInt());
        verify(repo, never()).listChronicTasks(any(), any(), anyInt());
    }
}
