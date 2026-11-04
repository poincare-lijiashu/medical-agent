package com.medassist.clinical.web;

import com.medassist.clinical.service.ChronicService;
import com.medassist.clinical.web.ChronicDtos.CloseRecordReq;
import com.medassist.clinical.web.ChronicDtos.CompleteTaskReq;
import com.medassist.clinical.web.ChronicDtos.CreatePlanReq;
import com.medassist.clinical.web.ChronicDtos.CreateRecordReq;
import com.medassist.clinical.web.ChronicDtos.RecordSummaryView;
import com.medassist.clinical.web.ChronicDtos.StopPlanReq;
import com.medassist.clinical.web.ChronicDtos.UpdateRiskReq;
import com.medassist.common.api.ErrorCode;
import com.medassist.common.exception.BusinessException;
import com.medassist.common.security.DataScope;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 慢病接口权限矩阵：档案 / 计划 / 随访查看 / 随访执行四权分立。
 */
class ChronicControllerPermissionTest {

    private ChronicService service;
    private ChronicController controller;

    private final UUID recordId = UUID.randomUUID();
    private final UUID planId = UUID.randomUUID();
    private final UUID taskId = UUID.randomUUID();
    private final DataScope fullScope = DataScope.all("admin01", true);

    @BeforeEach
    void setUp() {
        service = mock(ChronicService.class);
        controller = new ChronicController(service);
    }

    private void assertForbidden(Runnable call) {
        assertThatThrownBy(call::run)
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.FORBIDDEN);
    }

    @Test
    @DisplayName("档案查看/维护分权")
    void recordPermissions() {
        when(service.listDiseases()).thenReturn(List.of());
        assertForbidden(() -> controller.diseases("chronic:record:manage"));
        controller.diseases("chronic:record:read");
        verify(service).listDiseases();

        when(service.listRecords(null, null, null, 100)).thenReturn(List.of());
        assertForbidden(() -> controller.records(null, null, null, 100,
                "chronic:followup:read", fullScope));
        controller.records(null, null, null, 100, "chronic:record:read", fullScope);
        verify(service).listRecords(null, null, null, 100);

        CreateRecordReq create = new CreateRecordReq(UUID.randomUUID(), UUID.randomUUID(),
                LocalDate.now(), "HIGH", null, null, null);
        assertForbidden(() -> controller.createRecord(create, "chronic:record:read",
                "doctor01", fullScope));
        verify(service, never()).createRecord(any(), any());
        controller.createRecord(create, "chronic:record:manage", "doctor01", fullScope);
        verify(service).createRecord(create, "doctor01");

        assertForbidden(() -> controller.updateRisk(recordId, new UpdateRiskReq("LOW"),
                "chronic:followup:execute", "doctor01", fullScope));
        controller.updateRisk(recordId, new UpdateRiskReq("LOW"), "chronic:record:manage",
                "doctor01", fullScope);
        verify(service).updateRisk(recordId, new UpdateRiskReq("LOW"), "doctor01");
    }

    @Test
    @DisplayName("结案/计划：需要 record manage 或 plan manage")
    void closeAndPlanPermissions() {
        CloseRecordReq close = new CloseRecordReq("转上级医院");
        assertForbidden(() -> controller.closeRecord(recordId, close, "chronic:plan:manage",
                "doctor01", fullScope));
        controller.closeRecord(recordId, close, "chronic:record:manage", "doctor01", fullScope);
        verify(service).closeRecord(recordId, "转上级医院", "doctor01");

        CreatePlanReq plan = new CreatePlanReq(30, null);
        assertForbidden(() -> controller.createPlan(recordId, plan, "chronic:record:manage",
                "doctor01", fullScope));
        controller.createPlan(recordId, plan, "chronic:plan:manage", "doctor01", fullScope);
        verify(service).createPlan(recordId, plan, "doctor01");

        StopPlanReq stop = new StopPlanReq("患者失访");
        assertForbidden(() -> controller.stopPlan(planId, stop, "chronic:followup:execute",
                "doctor01", fullScope));
        controller.stopPlan(planId, stop, "chronic:plan:manage", "doctor01", fullScope);
        verify(service).stopPlan(planId, "患者失访", "doctor01");
    }

    @Test
    @DisplayName("随访任务：查看用 read，完成用 execute")
    void taskPermissions() {
        when(service.listTasks(null, null, 100)).thenReturn(List.of());
        assertForbidden(() -> controller.tasks(null, null, 100, "chronic:record:read", fullScope));
        controller.tasks(null, null, 100, "chronic:followup:read", fullScope);
        verify(service).listTasks(null, null, 100);

        CompleteTaskReq req = new CompleteTaskReq(LocalDate.now(), "PHONE", 130, 80, null,
                null, "头晕", "GOOD", "继续服药");
        assertForbidden(() -> controller.completeTask(taskId, req, "chronic:followup:read",
                "nurse01", fullScope));
        verify(service, never()).completeTask(any(), any(), any());
        controller.completeTask(taskId, req, "chronic:followup:execute", "nurse01", fullScope);
        verify(service).completeTask(taskId, req, "nurse01");

        when(service.listFollowups(recordId, null, null)).thenReturn(List.of());
        assertForbidden(() -> controller.followups(recordId, null, null,
                "chronic:record:read", fullScope));
        controller.followups(recordId, null, null, "chronic:followup:read", fullScope);
        verify(service).listFollowups(recordId, null, null);
    }

    @Test
    @DisplayName("PHI：无 phi:read 时档案列表患者号与姓名掩码")
    void masksRecordPhi() {
        RecordSummaryView summary = new RecordSummaryView(recordId, "CR20261104000001",
                UUID.randomUUID(), "P001", "张三", "MALE", LocalDate.of(1960, 1, 1), "CD-HYP",
                "高血压", "HIGH", "ACTIVE", "doctor01", Instant.now(), null, 0, null);
        when(service.listRecords(null, null, null, 100)).thenReturn(List.of(summary));

        var masked = controller.records(null, null, null, 100, "chronic:record:read",
                DataScope.all("nurse01", false)).data().get(0);

        assertThat(masked.patientNo()).isNotEqualTo("P001");
        assertThat(masked.patientName()).isNotEqualTo("张三");
    }
}
