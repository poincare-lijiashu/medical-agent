package com.medassist.clinical.web;

import com.medassist.clinical.domain.ClinicalModel.MobileBoardRow;
import com.medassist.clinical.domain.ClinicalModel.MobileNursingTask;
import com.medassist.clinical.domain.ClinicalModel.MobileScanEvent;
import com.medassist.clinical.service.MobileNursingService;
import com.medassist.clinical.service.MobileNursingService.GenerateOutcome;
import com.medassist.clinical.service.MobileNursingService.VerifyOutcome;
import com.medassist.clinical.web.MobileNursingDtos.ExceptionReq;
import com.medassist.clinical.web.MobileNursingDtos.GenerateFromOrdersReq;
import com.medassist.clinical.web.MobileNursingDtos.ManualTaskReq;
import com.medassist.clinical.web.MobileNursingDtos.VerifyReq;
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
 * 移动护理权限矩阵：查看 / 扫码执行 / 任务管理三权分立。
 */
class MobileNursingControllerPermissionTest {

    private MobileNursingService service;
    private MobileNursingController controller;

    private final UUID taskId = UUID.randomUUID();
    private final UUID patientId = UUID.randomUUID();
    private final DataScope fullScope = DataScope.all("admin01", true);

    @BeforeEach
    void setUp() {
        service = mock(MobileNursingService.class);
        controller = new MobileNursingController(service);
    }

    private void assertForbidden(Runnable call) {
        assertThatThrownBy(call::run)
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.FORBIDDEN);
    }

    private MobileNursingTask task(String status) {
        return new MobileNursingTask(taskId, "MN20261104000001", LocalDate.now(),
                MobileNursingTask.MEDICATION, UUID.randomUUID(), UUID.randomUUID(),
                patientId, "P001", "张三", UUID.randomUUID(), "内科病区", "01", "ORDER",
                "O001", UUID.randomUUID(), "DRUG-001", "氯化钠注射液", "P001", "DRUG-001",
                status, 0, null, null, null, null, null, null, null, "nurse01", 0L,
                Instant.now());
    }

    @Test
    @DisplayName("任务查看：必须 mobile:nursing:read")
    void tasksRequireRead() {
        when(service.listTasks(null, null, null, null, 100, fullScope)).thenReturn(List.of());
        assertForbidden(() -> controller.tasks(null, null, null, null, 100,
                "mobile:nursing:execute", fullScope));
        controller.tasks(null, null, null, null, 100, "mobile:nursing:read", fullScope);
        verify(service).listTasks(null, null, null, null, 100, fullScope);
    }

    @Test
    @DisplayName("生成任务/手工建单：必须 mobile:nursing:manage")
    void managePermissions() {
        GenerateFromOrdersReq req = new GenerateFromOrdersReq(null, "A001", LocalDate.now());
        when(service.generateFromOrders(req, "nurse01", fullScope)).thenReturn(
                new GenerateOutcome(0, 0, List.of()));
        assertForbidden(() -> controller.generate(req, "mobile:nursing:execute", "nurse01",
                fullScope));
        verify(service, never()).generateFromOrders(any(), any(), any());
        controller.generate(req, "mobile:nursing:manage", "nurse01", fullScope);
        verify(service).generateFromOrders(req, "nurse01", fullScope);

        ManualTaskReq manual = new ManualTaskReq(null, "A001", "SPECIMEN", "LAB-K", "血清钾",
                "SPEC-001", null, null);
        when(service.createManualTask(manual, "nurse01", fullScope)).thenReturn(task("PENDING"));
        assertForbidden(() -> controller.createManual(manual, "mobile:nursing:read",
                "nurse01", fullScope));
        controller.createManual(manual, "mobile:nursing:manage", "nurse01", fullScope);
        verify(service).createManualTask(manual, "nurse01", fullScope);
    }

    @Test
    @DisplayName("扫码核对/异常：必须 mobile:nursing:execute")
    void executePermissions() {
        VerifyReq req = new VerifyReq("P001", "DRUG-001");
        when(service.verify(taskId, "P001", "DRUG-001", "nurse01", fullScope)).thenReturn(
                new VerifyOutcome(true, true, true, "核对通过", task("VERIFIED")));
        assertForbidden(() -> controller.verify(taskId, req, "mobile:nursing:read", "nurse01",
                fullScope));
        verify(service, never()).verify(any(), any(), any(), any(), any());
        controller.verify(taskId, req, "mobile:nursing:execute", "nurse01", fullScope);
        verify(service).verify(taskId, "P001", "DRUG-001", "nurse01", fullScope);

        ExceptionReq ex = new ExceptionReq("患者拒绝服药");
        when(service.markException(taskId, "患者拒绝服药", "nurse01", fullScope)).thenReturn(
                task("EXCEPTION"));
        assertForbidden(() -> controller.exception(taskId, ex, "mobile:nursing:read",
                "nurse01", fullScope));
        controller.exception(taskId, ex, "mobile:nursing:execute", "nurse01", fullScope);
        verify(service).markException(taskId, "患者拒绝服药", "nurse01", fullScope);
    }

    @Test
    @DisplayName("扫码记录查看与 PHI 掩码")
    void scansAndMasking() {
        DataScope masked = DataScope.all("nurse01", false);
        when(service.listScans(taskId, masked)).thenReturn(List.of(
                new MobileScanEvent(UUID.randomUUID(), taskId, "PATIENT", "P001", true,
                        "nurse01", Instant.now(), null)));
        assertForbidden(() -> controller.scans(taskId, "mobile:nursing:execute", fullScope));
        var scans = controller.scans(taskId, "mobile:nursing:read", masked).data();
        assertThat(scans).hasSize(1);
        assertThat(scans.get(0).scannedValue()).isNotEqualTo("P001");

        when(service.board(fullScope)).thenReturn(new MobileBoardRow(3, 2, 1, 6));
        assertForbidden(() -> controller.board("mobile:nursing:manage", fullScope));
        assertThat(controller.board("mobile:nursing:read", fullScope).data().pending())
                .isEqualTo(3);
    }

    @Test
    @DisplayName("任务视图不返回期望条码，且无 phi:read 时患者号/姓名掩码")
    void taskViewHidesExpectedBarcodes() {
        DataScope masked = DataScope.all("nurse01", false);
        when(service.listTasks(null, null, null, null, 100, masked)).thenReturn(List.of(
                task("PENDING")));
        var view = controller.tasks(null, null, null, null, 100, "mobile:nursing:read",
                masked).data().get(0);
        assertThat(view.patientNo()).isNotEqualTo("P001");
        assertThat(view.patientName()).isNotEqualTo("张三");
        // TaskView 本身没有 expectedPatientBarcode / expectedItemBarcode 字段
        assertThat(view.toString()).doesNotContain("expected");
    }
}
