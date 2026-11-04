package com.medassist.clinical.service;

import com.medassist.clinical.client.PharmacyOrderClient;
import com.medassist.clinical.client.PharmacyOrderClient.Line;
import com.medassist.clinical.client.PharmacyOrderClient.PrescriptionStatus;
import com.medassist.clinical.client.PharmacyOrderClient.RxRef;
import com.medassist.clinical.domain.ClinicalModel.Admission;
import com.medassist.clinical.domain.ClinicalModel.DischargeMedication;
import com.medassist.clinical.domain.ClinicalModel.DischargeMedicationItem;
import com.medassist.clinical.domain.ClinicalModel.Visit;
import com.medassist.clinical.repo.ClinicalRepository;
import com.medassist.clinical.web.DischargeMedicationDtos.CancelDischargeMedicationReq;
import com.medassist.clinical.web.DischargeMedicationDtos.CreateDischargeMedicationReq;
import com.medassist.clinical.web.DischargeMedicationDtos.DischargeMedicationView;
import com.medassist.clinical.web.DischargeMedicationDtos.MedicationLine;
import com.medassist.common.api.ErrorCode;
import com.medassist.common.exception.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
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
 * 出院带药：在院校验、唯一进行中单据、提交计费模式、发药同步与取消。
 */
class DischargeMedicationServiceTest {

    private ClinicalRepository repo;
    private PharmacyOrderClient client;
    private DischargeMedicationService service;

    private final UUID medicationId = UUID.randomUUID();
    private final UUID visitId = UUID.randomUUID();
    private final UUID admissionId = UUID.randomUUID();
    private final UUID patientId = UUID.randomUUID();
    private final UUID deptId = UUID.randomUUID();
    private final UUID prescriptionId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        repo = mock(ClinicalRepository.class);
        client = mock(PharmacyOrderClient.class);
        service = new DischargeMedicationService(repo, client, JsonMapper.builder().build());
    }

    private void assertCode(Runnable call, ErrorCode code) {
        assertThatThrownBy(call::run)
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(code);
    }

    private Visit visit(String type) {
        return new Visit(visitId, "V20261104000001", patientId, "P0001", deptId, "doctor01",
                null, type, "IN_TREATMENT", LocalDate.now(), 0L);
    }

    private Admission admission(String status) {
        return new Admission(admissionId, "A20261104000001", visitId, patientId, "P0001",
                deptId, null, "doctor01", status, Instant.now(), null, 0L);
    }

    private DischargeMedication medication(String status, long version) {
        return new DischargeMedication(medicationId, "DM20261104000001", admissionId, visitId,
                "V20261104000001", patientId, "P0001", deptId, "内科", status, "出院带药",
                prescriptionId, "RX20261104000001", new BigDecimal("24.00"),
                Instant.now(), null, null, null, null, "doctor01", version, Instant.now());
    }

    private DischargeMedicationItem item(int seq, String code, int quantity) {
        return new DischargeMedicationItem(UUID.randomUUID(), medicationId, seq, code, "阿莫西林胶囊",
                "0.25g*24粒", "盒", quantity, "0.5g", "TID", 7);
    }

    private CreateDischargeMedicationReq createReq(String visitNo, List<MedicationLine> lines) {
        return new CreateDischargeMedicationReq(null, visitNo, "出院带药", lines);
    }

    private MedicationLine line(String code, String name, int quantity) {
        return new MedicationLine(code, name, "0.25g*24粒", "盒", quantity, "0.5g", "TID", 7);
    }

    // ---------------------------------------------------------------- 开立

    @Test
    @DisplayName("开立：非住院就诊 → 409")
    void createRejectsOutpatient() {
        when(repo.findVisitByNo("V20261104000001")).thenReturn(Optional.of(visit("OUTPATIENT")));

        assertCode(() -> service.create(createReq("V20261104000001",
                List.of(line("DRUG-AMOX-025", "阿莫西林胶囊", 2))), "doctor01"),
                ErrorCode.DISCHARGE_MED_VISIT_INVALID);
        verify(repo, never()).insertDischargeMedication(any());
    }

    @Test
    @DisplayName("开立：患者已出院 → 409")
    void createRejectsDischarged() {
        when(repo.findVisitByNo(anyString())).thenReturn(Optional.of(visit("INPATIENT")));
        when(repo.findLatestAdmissionByVisit(visitId))
                .thenReturn(Optional.of(admission("DISCHARGED")));

        assertCode(() -> service.create(createReq("V20261104000001",
                List.of(line("DRUG-AMOX-025", "阿莫西林胶囊", 2))), "doctor01"),
                ErrorCode.DISCHARGE_MED_VISIT_INVALID);
        verify(repo, never()).insertDischargeMedication(any());
    }

    @Test
    @DisplayName("开立：已有进行中的单据 → 409")
    void createRejectsActiveMedication() {
        when(repo.findVisitByNo(anyString())).thenReturn(Optional.of(visit("INPATIENT")));
        when(repo.findLatestAdmissionByVisit(visitId))
                .thenReturn(Optional.of(admission("IN_HOSPITAL")));
        when(repo.findActiveDischargeMedication(admissionId))
                .thenReturn(Optional.of(medication(DischargeMedication.DRAFT, 0L)));

        assertCode(() -> service.create(createReq("V20261104000001",
                List.of(line("DRUG-AMOX-025", "阿莫西林胶囊", 2))), "doctor01"),
                ErrorCode.DISCHARGE_MED_EXISTS);
    }

    @Test
    @DisplayName("开立：药品编码重复 → 422，不落库")
    void createRejectsDuplicateDrug() {
        when(repo.findVisitByNo(anyString())).thenReturn(Optional.of(visit("INPATIENT")));
        when(repo.findLatestAdmissionByVisit(visitId))
                .thenReturn(Optional.of(admission("IN_HOSPITAL")));
        when(repo.findActiveDischargeMedication(admissionId)).thenReturn(Optional.empty());

        assertCode(() -> service.create(createReq("V20261104000001", List.of(
                line("DRUG-AMOX-025", "阿莫西林胶囊", 2),
                line("drug-amox-025", "阿莫西林胶囊", 1))), "doctor01"),
                ErrorCode.DISCHARGE_MED_INVALID);
        verify(repo, never()).insertDischargeMedication(any());
    }

    @Test
    @DisplayName("开立：成功生成草稿与明细快照，并写审计")
    void createSucceeds() {
        when(repo.findVisitByNo(anyString())).thenReturn(Optional.of(visit("INPATIENT")));
        when(repo.findLatestAdmissionByVisit(visitId))
                .thenReturn(Optional.of(admission("IN_HOSPITAL")));
        when(repo.findActiveDischargeMedication(admissionId)).thenReturn(Optional.empty());
        when(repo.nextDischargeMedicationNo()).thenReturn("DM20261104000001");
        when(repo.findDischargeMedication(any()))
                .thenReturn(Optional.of(medication(DischargeMedication.DRAFT, 0L)));
        when(repo.listDischargeMedicationItems(any())).thenReturn(List.of(
                item(1, "DRUG-AMOX-025", 2), item(2, "DRUG-IBU-200", 1)));

        DischargeMedicationView view = service.create(createReq("V20261104000001", List.of(
                line("DRUG-AMOX-025", "阿莫西林胶囊", 2),
                line("DRUG-IBU-200", "布洛芬缓释胶囊", 1))), "doctor01");

        assertThat(view.medication().status()).isEqualTo(DischargeMedication.DRAFT);
        assertThat(view.items()).hasSize(2);
        verify(repo).insertDischargeMedication(any());
        verify(repo, org.mockito.Mockito.times(2)).insertDischargeMedicationItem(any());
        verify(repo).logChange(eq("doctor01"), eq("DISCHARGE_MED_CREATE"),
                eq("DISCHARGE_MEDICATION"), anyString(), anyString());
    }

    // ---------------------------------------------------------------- 提交

    @Test
    @DisplayName("提交：非草稿 → 409")
    void submitRejectsNonDraft() {
        when(repo.lockDischargeMedication(medicationId))
                .thenReturn(Optional.of(medication(DischargeMedication.SUBMITTED, 1L)));

        assertCode(() -> service.submit(medicationId, "doctor01"),
                ErrorCode.DISCHARGE_MED_STATUS_INVALID);
        verify(client, never()).createFromOrderWithBilling(any(), any(), any(), any(), any(),
                any(), any(), anyString(), any());
    }

    @Test
    @DisplayName("提交：患者已离院 → 409，不调用药房")
    void submitRejectsDischargedPatient() {
        when(repo.lockDischargeMedication(medicationId))
                .thenReturn(Optional.of(medication(DischargeMedication.DRAFT, 0L)));
        when(repo.findAdmission(admissionId)).thenReturn(Optional.of(admission("DISCHARGED")));

        assertCode(() -> service.submit(medicationId, "doctor01"),
                ErrorCode.DISCHARGE_MED_VISIT_INVALID);
        verify(client, never()).createFromOrderWithBilling(any(), any(), any(), any(), any(),
                any(), any(), anyString(), any());
    }

    @Test
    @DisplayName("提交：调用药房以 DISPENSE 计费，成功后落处方号与金额")
    void submitCreatesPrescription() {
        when(repo.lockDischargeMedication(medicationId))
                .thenReturn(Optional.of(medication(DischargeMedication.DRAFT, 0L)));
        when(repo.findAdmission(admissionId)).thenReturn(Optional.of(admission("IN_HOSPITAL")));
        when(repo.listDischargeMedicationItems(medicationId))
                .thenReturn(List.of(item(1, "DRUG-AMOX-025", 2)));
        when(client.createFromOrderWithBilling(any(), any(), any(), any(), any(), any(), any(),
                anyString(), any())).thenReturn(new RxRef(prescriptionId, "RX20261104000001",
                false, new BigDecimal("24.00")));
        when(repo.markDischargeMedicationSubmitted(eq(medicationId), eq(prescriptionId),
                eq("RX20261104000001"), any(), eq(0L))).thenReturn(true);
        when(repo.findDischargeMedication(medicationId))
                .thenReturn(Optional.of(medication(DischargeMedication.SUBMITTED, 1L)));
        when(repo.listDischargeMedicationItems(medicationId))
                .thenReturn(List.of(item(1, "DRUG-AMOX-025", 2)));

        service.submit(medicationId, "doctor01");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Line>> lines = ArgumentCaptor.forClass(List.class);
        verify(client).createFromOrderWithBilling(eq(medicationId), eq(patientId), eq(visitId),
                eq("V20261104000001"), eq(deptId), eq("doctor01"), any(LocalDate.class),
                eq("DISPENSE"), lines.capture());
        assertThat(lines.getValue()).hasSize(1);
        assertThat(lines.getValue().get(0).drugCode()).isEqualTo("DRUG-AMOX-025");
        verify(repo).logChange(eq("doctor01"), eq("DISCHARGE_MED_SUBMIT"),
                eq("DISCHARGE_MEDICATION"), eq(medicationId.toString()), anyString());
    }

    @Test
    @DisplayName("提交：药房成功但本地 CAS 失败 → 409（下次提交命中药房幂等）")
    void submitCasConflict() {
        when(repo.lockDischargeMedication(medicationId))
                .thenReturn(Optional.of(medication(DischargeMedication.DRAFT, 0L)));
        when(repo.findAdmission(admissionId)).thenReturn(Optional.of(admission("IN_HOSPITAL")));
        when(repo.listDischargeMedicationItems(medicationId))
                .thenReturn(List.of(item(1, "DRUG-AMOX-025", 2)));
        when(client.createFromOrderWithBilling(any(), any(), any(), any(), any(), any(), any(),
                anyString(), any())).thenReturn(new RxRef(prescriptionId, "RX1", true,
                BigDecimal.TEN));
        when(repo.markDischargeMedicationSubmitted(any(), any(), any(), any(), anyLong()))
                .thenReturn(false);

        assertCode(() -> service.submit(medicationId, "doctor01"), ErrorCode.CONFLICT);
    }

    // ---------------------------------------------------------------- 同步

    @Test
    @DisplayName("同步：药房已发药 → 本地 DISPENSED 并写审计")
    void syncDispensed() {
        when(repo.findDischargeMedication(medicationId))
                .thenReturn(Optional.of(medication(DischargeMedication.SUBMITTED, 1L)));
        when(client.prescriptionStatus(prescriptionId))
                .thenReturn(new PrescriptionStatus(prescriptionId, "RX20261104000001",
                        "DISPENSED", "DISPENSE", new BigDecimal("24.00")));

        service.syncStatus(medicationId, "doctor01");

        verify(repo).markDischargeMedicationDispensed(medicationId);
        verify(repo).logChange(eq("doctor01"), eq("DISCHARGE_MED_DISPENSED"),
                eq("DISCHARGE_MEDICATION"), eq(medicationId.toString()), anyString());
    }

    @Test
    @DisplayName("同步：药房处方被作废 → 本地取消并留原因")
    void syncCancelled() {
        when(repo.findDischargeMedication(medicationId))
                .thenReturn(Optional.of(medication(DischargeMedication.SUBMITTED, 1L)));
        when(client.prescriptionStatus(prescriptionId))
                .thenReturn(new PrescriptionStatus(prescriptionId, "RX20261104000001",
                        "CANCELLED", "DISPENSE", BigDecimal.ZERO));

        service.syncStatus(medicationId, "doctor01");

        verify(repo).cancelDischargeMedication(medicationId, "pharmacy", "药房处方已作废", 1L);
        verify(repo).logChange(eq("doctor01"), eq("DISCHARGE_MED_CANCELLED"),
                eq("DISCHARGE_MEDICATION"), eq(medicationId.toString()), anyString());
    }

    @Test
    @DisplayName("同步：非 SUBMITTED 幂等返回，不查询药房")
    void syncSkipsNonSubmitted() {
        when(repo.findDischargeMedication(medicationId))
                .thenReturn(Optional.of(medication(DischargeMedication.DRAFT, 0L)));
        when(repo.listDischargeMedicationItems(medicationId)).thenReturn(List.of());

        service.syncStatus(medicationId, "scheduler");

        verify(client, never()).prescriptionStatus(any());
        verify(repo, never()).markDischargeMedicationDispensed(any());
    }

    @Test
    @DisplayName("同步：已提交但无处方号 → 409")
    void syncRejectsMissingPrescription() {
        DischargeMedication noRx = new DischargeMedication(medicationId, "DM1", admissionId,
                visitId, "V1", patientId, "P0001", deptId, "内科", DischargeMedication.SUBMITTED,
                null, null, null, BigDecimal.ZERO, Instant.now(), null, null, null, null,
                "doctor01", 1L, Instant.now());
        when(repo.findDischargeMedication(medicationId)).thenReturn(Optional.of(noRx));

        assertCode(() -> service.syncStatus(medicationId, "scheduler"),
                ErrorCode.DISCHARGE_MED_PRESCRIPTION_MISSING);
    }

    // ---------------------------------------------------------------- 取消

    @Test
    @DisplayName("取消：必须写原因")
    void cancelRequiresReason() {
        assertCode(() -> service.cancel(medicationId,
                new CancelDischargeMedicationReq(" "), "doctor01"),
                ErrorCode.DISCHARGE_MED_CANCEL_REASON_REQUIRED);
        verify(repo, never()).cancelDischargeMedication(any(), anyString(), anyString(), anyLong());
    }

    @Test
    @DisplayName("取消草稿：不调用药房，直接落 CANCELLED")
    void cancelDraft() {
        when(repo.lockDischargeMedication(medicationId))
                .thenReturn(Optional.of(medication(DischargeMedication.DRAFT, 0L)));
        when(repo.cancelDischargeMedication(medicationId, "doctor01", "患者放弃带药", 0L))
                .thenReturn(true);
        when(repo.findDischargeMedication(medicationId))
                .thenReturn(Optional.of(medication(DischargeMedication.CANCELLED, 1L)));
        when(repo.listDischargeMedicationItems(medicationId)).thenReturn(List.of());

        service.cancel(medicationId, new CancelDischargeMedicationReq("患者放弃带药"),
                "doctor01");

        verify(client, never()).cancelByOrder(any(), any(), any());
        verify(repo).logChange(eq("doctor01"), eq("DISCHARGE_MED_CANCEL"),
                eq("DISCHARGE_MEDICATION"), eq(medicationId.toString()), anyString());
    }

    @Test
    @DisplayName("取消已提交：先作废药房处方，再落 CANCELLED")
    void cancelSubmittedCancelsPrescription() {
        when(repo.lockDischargeMedication(medicationId))
                .thenReturn(Optional.of(medication(DischargeMedication.SUBMITTED, 1L)));
        when(client.cancelByOrder(medicationId, "重复开立", "doctor01"))
                .thenReturn(new PharmacyOrderClient.CancelRef(prescriptionId, "CANCELLED", true));
        when(repo.cancelDischargeMedication(medicationId, "doctor01", "重复开立", 1L))
                .thenReturn(true);
        when(repo.findDischargeMedication(medicationId))
                .thenReturn(Optional.of(medication(DischargeMedication.CANCELLED, 2L)));
        when(repo.listDischargeMedicationItems(medicationId)).thenReturn(List.of());

        service.cancel(medicationId, new CancelDischargeMedicationReq("重复开立"), "doctor01");

        verify(client).cancelByOrder(medicationId, "重复开立", "doctor01");
        verify(repo).cancelDischargeMedication(medicationId, "doctor01", "重复开立", 1L);
    }

    @Test
    @DisplayName("列表：非法状态 422，limit 上限 200")
    void listNormalizes() {
        assertCode(() -> service.list("BAD", null, 10), ErrorCode.VALIDATION_FAILED);
        service.list("submitted", deptId, 9999);
        verify(repo).listDischargeMedications("SUBMITTED", deptId, 200);
    }
}
