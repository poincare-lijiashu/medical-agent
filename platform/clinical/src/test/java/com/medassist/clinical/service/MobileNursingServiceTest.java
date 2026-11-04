package com.medassist.clinical.service;

import com.medassist.clinical.client.PatientClient;
import com.medassist.clinical.domain.ClinicalModel.Admission;
import com.medassist.clinical.domain.ClinicalModel.Bed;
import com.medassist.clinical.domain.ClinicalModel.MobileNursingTask;
import com.medassist.clinical.domain.ClinicalModel.VisitOrder;
import com.medassist.clinical.domain.ClinicalModel.Ward;
import com.medassist.clinical.repo.ClinicalRepository;
import com.medassist.clinical.web.MobileNursingDtos.GenerateFromOrdersReq;
import com.medassist.clinical.web.MobileNursingDtos.ManualTaskReq;
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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 移动护理：医嘱生成任务、扫码匹配/不匹配、异常关闭。
 */
class MobileNursingServiceTest {

    private ClinicalRepository repo;
    private PatientClient patientClient;
    private MobileNursingService service;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final UUID admissionId = UUID.randomUUID();
    private final UUID visitId = UUID.randomUUID();
    private final UUID patientId = UUID.randomUUID();
    private final UUID wardId = UUID.randomUUID();
    private final UUID bedId = UUID.randomUUID();
    private final UUID taskId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        repo = mock(ClinicalRepository.class);
        patientClient = mock(PatientClient.class);
        service = new MobileNursingService(repo, patientClient, objectMapper);
    }

    private Admission admission(String status) {
        return new Admission(admissionId, "A20261104000001", visitId, patientId, "P001",
                wardId, bedId, "doctor01", status, Instant.now(), null, 0);
    }

    private VisitOrder order(String mode, String type, String status, LocalDate start,
                             LocalDate stop) {
        return new VisitOrder(UUID.randomUUID(), "O" + System.nanoTime(), visitId, type,
                "DRUG-001", "氯化钠注射液", "100ml", "QD", 1, 1, "瓶",
                new BigDecimal("3.50"), new BigDecimal("3.50"), status, "doctor01", 0,
                mode, start, stop, VisitOrder.TRANSFER_NONE, null, null, null, null);
    }

    private MobileNursingTask task(String status, long version) {
        return new MobileNursingTask(taskId, "MN20261104000001", BusinessTime.today(),
                MobileNursingTask.MEDICATION, admissionId, visitId, patientId, "P001", "张三",
                wardId, "内科病区", "01", "ORDER", "O001", UUID.randomUUID(), "DRUG-001",
                "氯化钠注射液", "P001", "DRUG-001", status, 0, null, null, null, null,
                null, null, null, "doctor01", version, Instant.now());
    }

    private void stubAdmissionContext() {
        when(repo.findAdmissionByNo("A20261104000001")).thenReturn(Optional.of(
                admission(Admission.IN_HOSPITAL)));
        when(patientClient.requireProfile(patientId)).thenReturn(
                new PatientClient.PatientProfile(patientId, "P001", "张三", "MALE",
                        LocalDate.of(1960, 1, 1)));
        when(repo.findWard(wardId)).thenReturn(Optional.of(
                new Ward(wardId, "W01", "内科病区", UUID.randomUUID(), true)));
        when(repo.findBed(bedId)).thenReturn(Optional.of(
                new Bed(bedId, wardId, "01", "OCCUPIED", 0)));
    }

    @Test
    @DisplayName("从医嘱生成：仅长期药品医嘱，已存在任务跳过，未来日期拒绝")
    void generatesFromLongDrugOrders() {
        stubAdmissionContext();
        VisitOrder longDrug = order("LONG", "DRUG", VisitOrder.PLACED,
                BusinessTime.today().minusDays(1), null);
        VisitOrder tempDrug = order("TEMP", "DRUG", VisitOrder.PLACED, null, null);
        VisitOrder longExam = order("LONG", "EXAM", VisitOrder.PLACED, null, null);
        VisitOrder cancelled = order("LONG", "DRUG", VisitOrder.CANCELLED, null, null);
        VisitOrder future = order("LONG", "DRUG", VisitOrder.PLACED,
                BusinessTime.today().plusDays(1), null);
        VisitOrder existing = order("LONG", "DRUG", VisitOrder.PLACED, null, null);
        when(repo.listOrders(visitId, null)).thenReturn(List.of(longDrug, tempDrug, longExam,
                cancelled, future, existing));
        when(repo.findMobileTaskByOrder(existing.id(), BusinessTime.today())).thenReturn(
                Optional.of(task(MobileNursingTask.PENDING, 0)));
        when(repo.nextMobileTaskNo()).thenReturn("MN20261104000001");

        var outcome = service.generateFromOrders(new GenerateFromOrdersReq(null,
                "A20261104000001", BusinessTime.today()), "nurse01");

        assertThat(outcome.created()).isEqualTo(1);
        assertThat(outcome.skipped()).isEqualTo(1);
        verify(repo).insertMobileTask(any());

        assertThatThrownBy(() -> service.generateFromOrders(new GenerateFromOrdersReq(null,
                "A20261104000001", BusinessTime.today().plusDays(1)), "nurse01"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
    }

    @Test
    @DisplayName("手工建单：类型/项目/条码缺失拒绝")
    void manualTaskValidation() {
        ManualTaskReq badType = new ManualTaskReq(null, "A20261104000001", "UNKNOWN",
                "DRUG-001", "氯化钠", "B001", null, null);
        assertThatThrownBy(() -> service.createManualTask(badType, "nurse01"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.MOBILE_TASK_INVALID);

        stubAdmissionContext();
        when(repo.nextMobileTaskNo()).thenReturn("MN20261104000002");
        ManualTaskReq ok = new ManualTaskReq(null, "A20261104000001", "SPECIMEN",
                "LAB-K", "血清钾", "SPEC-001", "采集单", null);
        var created = service.createManualTask(ok, "nurse01");
        assertThat(created.taskType()).isEqualTo(MobileNursingTask.SPECIMEN);
        assertThat(created.status()).isEqualTo(MobileNursingTask.PENDING);
    }

    @Test
    @DisplayName("扫码核对：双码匹配置 VERIFIED；不匹配保留 PENDING 并写两条事件")
    void verifiesBarcodes() {
        MobileNursingTask pending = task(MobileNursingTask.PENDING, 0);
        when(repo.lockMobileTask(taskId)).thenReturn(Optional.of(pending));
        when(repo.updateMobileTaskScan(eq(taskId), eq(true), eq("nurse01"), eq(0L)))
                .thenReturn(true);
        MobileNursingTask verified = task(MobileNursingTask.VERIFIED, 1);
        when(repo.findMobileTask(taskId)).thenReturn(Optional.of(verified));

        var outcome = service.verify(taskId, "P001", "DRUG-001", "nurse01");
        assertThat(outcome.matched()).isTrue();
        assertThat(outcome.task().status()).isEqualTo(MobileNursingTask.VERIFIED);
        verify(repo, org.mockito.Mockito.times(2)).insertMobileScanEvent(any());

        MobileNursingTask pending2 = task(MobileNursingTask.PENDING, 2);
        when(repo.lockMobileTask(taskId)).thenReturn(Optional.of(pending2));
        when(repo.updateMobileTaskScan(eq(taskId), eq(false), eq("nurse01"), eq(2L)))
                .thenReturn(true);
        when(repo.findMobileTask(taskId)).thenReturn(Optional.of(pending2));
        var mismatch = service.verify(taskId, "P999", "DRUG-001", "nurse01");
        assertThat(mismatch.matched()).isFalse();
        assertThat(mismatch.patientMatched()).isFalse();
        assertThat(mismatch.itemMatched()).isTrue();

        assertThatThrownBy(() -> service.verify(taskId, " ", "DRUG-001", "nurse01"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.MOBILE_SCAN_INVALID);
    }

    @Test
    @DisplayName("扫码核对：非 PENDING 拒绝")
    void verifyRejectsNonPending() {
        when(repo.lockMobileTask(taskId)).thenReturn(Optional.of(
                task(MobileNursingTask.VERIFIED, 1)));
        assertThatThrownBy(() -> service.verify(taskId, "P001", "DRUG-001", "nurse01"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.MOBILE_TASK_STATUS_INVALID);
        verify(repo, never()).insertMobileScanEvent(any());
    }

    @Test
    @DisplayName("异常关闭：原因必填；已异常幂等")
    void exceptionRules() {
        MobileNursingTask pending = task(MobileNursingTask.PENDING, 0);
        when(repo.lockMobileTask(taskId)).thenReturn(Optional.of(pending));
        assertThatThrownBy(() -> service.markException(taskId, " ", "nurse01"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.MOBILE_EXCEPTION_REASON_REQUIRED);

        when(repo.markMobileTaskException(taskId, "患者拒绝服药", "nurse01", 0)).thenReturn(true);
        when(repo.findMobileTask(taskId)).thenReturn(Optional.of(
                task(MobileNursingTask.EXCEPTION, 1)));
        assertThat(service.markException(taskId, "患者拒绝服药", "nurse01").status())
                .isEqualTo(MobileNursingTask.EXCEPTION);

        when(repo.lockMobileTask(taskId)).thenReturn(Optional.of(
                task(MobileNursingTask.EXCEPTION, 1)));
        assertThat(service.markException(taskId, null, "nurse01").status())
                .isEqualTo(MobileNursingTask.EXCEPTION);
    }

    @Test
    @DisplayName("列表：非法状态/类型 422")
    void listValidation() {
        assertThatThrownBy(() -> service.listTasks(BusinessTime.today(), "UNKNOWN", null,
                null, 10))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
        assertThatThrownBy(() -> service.listTasks(BusinessTime.today(), null, "UNKNOWN",
                null, 10))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
        verify(repo, never()).listMobileTasks(any(), any(), any(), any(), anyInt());
    }
}
