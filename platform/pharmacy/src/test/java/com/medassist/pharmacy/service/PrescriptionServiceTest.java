package com.medassist.pharmacy.service;

import com.medassist.common.api.ErrorCode;
import com.medassist.common.exception.BusinessException;
import com.medassist.pharmacy.client.ClinicalChargeClient;
import com.medassist.pharmacy.client.PatientClient;
import com.medassist.pharmacy.domain.PharmacyModel.Dispense;
import com.medassist.pharmacy.domain.PharmacyModel.DispenseItem;
import com.medassist.pharmacy.domain.PharmacyModel.Drug;
import com.medassist.pharmacy.domain.PharmacyModel.DrugBatch;
import com.medassist.pharmacy.domain.PharmacyModel.Prescription;
import com.medassist.pharmacy.domain.PharmacyModel.PrescriptionItem;
import com.medassist.pharmacy.repo.PharmacyRepository;
import com.medassist.pharmacy.web.PharmacyDtos.CancelFromOrderReq;
import com.medassist.pharmacy.web.PharmacyDtos.CreateFromOrderReq;
import com.medassist.pharmacy.web.PharmacyDtos.CreatePrescriptionReq;
import com.medassist.pharmacy.web.PharmacyDtos.DispenseView;
import com.medassist.pharmacy.web.PharmacyDtos.OrderPrescriptionView;
import com.medassist.pharmacy.web.PharmacyDtos.PrescriptionCancelView;
import com.medassist.pharmacy.web.PharmacyDtos.PrescriptionLine;
import com.medassist.pharmacy.web.PharmacyDtos.PrescriptionView;
import com.medassist.pharmacy.web.PharmacyDtos.ReturnReq;
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
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 处方域规则：金额快照、审核状态机、FEFO 发药、双人核对、退药上限。
 */
class PrescriptionServiceTest {

    private PharmacyRepository repo;
    private PatientClient patientClient;
    private ClinicalChargeClient chargeClient;
    private PrescriptionService service;

    private final UUID patientId = UUID.randomUUID();
    private final UUID drugId = UUID.randomUUID();
    private final UUID rxId = UUID.randomUUID();

    private final Drug amoxicillin = new Drug(drugId, "DRUG-AMOX-025", "阿莫西林胶囊", "阿莫仙",
            "0.25g*24粒", "CAPSULE", "盒", "粒", 24, new BigDecimal("12.50"),
            "XA01AA0401010101010", "A", 1, false, true, 0L, null, false, false);

    private final Drug morphine = new Drug(UUID.randomUUID(), "DRUG-MOR-10", "盐酸吗啡注射液", null,
            "10mg", "INJECTION", "支", "支", 1, new BigDecimal("5.60"),
            null, "B", 0, true, true, 0L, null, false, false);

    @BeforeEach
    void setUp() {
        repo = mock(PharmacyRepository.class);
        patientClient = mock(PatientClient.class);
        chargeClient = mock(ClinicalChargeClient.class);
        PharmacyBillingService billing = new PharmacyBillingService(repo, chargeClient,
                JsonMapper.builder().build());
        service = new PrescriptionService(repo, patientClient, billing,
                new PrescriptionAuditService(repo),
                new NarcoticService(repo, JsonMapper.builder().build()),
                JsonMapper.builder().build());

        when(patientClient.requireActive(patientId))
                .thenReturn(new PatientClient.PatientRef(patientId, "P20261031000001", "张三", java.time.LocalDate.of(1990, 1, 1)));
        when(repo.nextPrescriptionNo()).thenReturn("RX20261031000001");
        when(repo.nextDispenseNo()).thenReturn("DP20261031000001");
        when(repo.listPrescriptionItems(rxId)).thenReturn(List.of());
        when(repo.findPrescription(any())).thenReturn(Optional.of(rx(
                Prescription.PENDING_REVIEW, new BigDecimal("25.00"), 0L)));
        when(repo.findDispense(any())).thenReturn(Optional.of(dispense(Dispense.DISPENSED)));
        when(repo.listDispenseItems(any())).thenReturn(List.of());
        when(repo.listReturns(any())).thenReturn(List.of());
    }

    private Prescription rx(String status, BigDecimal total, long version) {
        return rx(status, total, version, Prescription.BILLING_ORDER);
    }

    private Prescription rx(String status, BigDecimal total, long version, String billingMode) {
        return new Prescription(rxId, "RX20261031000001", patientId, "P20261031000001",
                null, null, null, "doctor01", status, null, null, null, total, version, Instant.now(),
                billingMode, null, null);
    }

    private Dispense dispense(String status) {
        return dispense(status, Dispense.BILLING_NONE);
    }

    private Dispense dispense(String status, String billingStatus) {
        return new Dispense(UUID.randomUUID(), "DP20261031000001", rxId, "pharm01", null,
                status, new BigDecimal("25.00"), Instant.now(), 0L, billingStatus);
    }

    private DrugBatch batch(String batchNo, int quantity, int daysToExpire) {
        return new DrugBatch(UUID.randomUUID(), drugId, amoxicillin.drugCode(), amoxicillin.name(),
                batchNo, LocalDate.now().plusDays(daysToExpire), quantity, "国药", 0L);
    }

    // ---------------------------------------------------------------- 开方

    @Test
    @DisplayName("开方：金额按字典价格快照计算，状态待审核")
    void createComputesAmountFromCatalog() {
        when(repo.findDrugByCode("DRUG-AMOX-025")).thenReturn(Optional.of(amoxicillin));

        PrescriptionView view = service.create(new CreatePrescriptionReq(patientId, null, null, null, null,
                List.of(new PrescriptionLine("DRUG-AMOX-025", 2, "0.5g", "tid", 3, null, null, null))), "doctor01");

        assertThat(view.prescription().status()).isEqualTo(Prescription.PENDING_REVIEW);
        assertThat(view.prescription().totalAmount()).isEqualByComparingTo("25.00");
        assertThat(view.items()).hasSize(1);
        assertThat(view.items().get(0).unitPrice()).isEqualByComparingTo("12.50");
        assertThat(view.items().get(0).amount()).isEqualByComparingTo("25.00");
        assertThat(view.items().get(0).drugName()).isEqualTo("阿莫西林胶囊");
        verify(repo).insertPrescription(any(Prescription.class));
        // 处方主键在服务内生成，审计目标是该主键
        verify(repo).logChange(eq("doctor01"), eq("RX_CREATE"), eq("PRESCRIPTION"),
                anyString(), anyString());
    }

    @Test
    @DisplayName("开方：同一药品重复出现直接拒绝（不静默合并数量）")
    void createRejectsDuplicateDrug() {
        when(repo.findDrugByCode("DRUG-AMOX-025")).thenReturn(Optional.of(amoxicillin));

        assertThatThrownBy(() -> service.create(new CreatePrescriptionReq(patientId, null, null, null, null,
                List.of(new PrescriptionLine("DRUG-AMOX-025", 1, null, null, null, null, null, null),
                        new PrescriptionLine("DRUG-AMOX-025", 2, null, null, null, null, null, null))), "doctor01"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("重复开立");
        verify(repo, never()).insertPrescription(any());
    }

    @Test
    @DisplayName("开方：药品不存在或已停用 → 404")
    void createRejectsUnknownDrug() {
        when(repo.findDrugByCode("DRUG-X")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.create(new CreatePrescriptionReq(patientId, null, null, null, null,
                List.of(new PrescriptionLine("DRUG-X", 1, null, null, null, null, null, null))), "doctor01"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.DRUG_NOT_FOUND);
    }

    // ------------------------------------------------ 医嘱转处方（HIS 主链路）

    @Test
    @DisplayName("医嘱转处方：同一医嘱重复转送返回已有处方（幂等，不新建）")
    void createFromOrderIsIdempotent() {
        UUID orderId = UUID.randomUUID();
        when(repo.findPrescriptionBySourceOrder(orderId, LocalDate.now()))
                .thenReturn(Optional.of(rx(Prescription.PENDING_REVIEW, new BigDecimal("25.00"), 0L)));

        OrderPrescriptionView view = service.createFromOrder(new CreateFromOrderReq(orderId, patientId,
                UUID.randomUUID(), "V1", null, "doctor01", LocalDate.now(),
                List.of(new PrescriptionLine("DRUG-AMOX-025", 2, null, null, null, null, null, null))),
                "clinical");

        assertThat(view.duplicate()).isTrue();
        assertThat(view.prescriptionId()).isEqualTo(rxId);
        assertThat(view.totalAmount()).isEqualByComparingTo("25.00");
        verify(repo, never()).insertPrescription(any());
        verify(repo, never()).insertPrescriptionItem(any());
    }

    @Test
    @DisplayName("医嘱转处方：固定计费模式 ORDER 并记录来源医嘱（药房不再重复计费）")
    void createFromOrderUsesOrderBillingMode() {
        UUID orderId = UUID.randomUUID();
        when(repo.findPrescriptionBySourceOrder(orderId, LocalDate.now())).thenReturn(Optional.empty());
        when(repo.findDrugByCode("DRUG-AMOX-025")).thenReturn(Optional.of(amoxicillin));

        OrderPrescriptionView view = service.createFromOrder(new CreateFromOrderReq(orderId, patientId,
                UUID.randomUUID(), "V1", null, "doctor01", LocalDate.now(),
                List.of(new PrescriptionLine("DRUG-AMOX-025", 2, "0.5g", "bid", 3, null, null, null))),
                "clinical");

        assertThat(view.duplicate()).isFalse();
        ArgumentCaptor<Prescription> captor = ArgumentCaptor.forClass(Prescription.class);
        verify(repo).insertPrescription(captor.capture());
        assertThat(captor.getValue().billingMode()).isEqualTo(Prescription.BILLING_ORDER);
        assertThat(captor.getValue().sourceOrderId()).isEqualTo(orderId);
        assertThat(captor.getValue().prescriber()).isEqualTo("doctor01");
        verify(repo).logChange(eq("doctor01"), eq("RX_CREATE_FROM_ORDER"), eq("PRESCRIPTION"),
                anyString(), anyString());
    }

    @Test
    @DisplayName("医嘱转处方：审方 BLOCK（儿童禁用）直接拒绝，不生成处方")
    void createFromOrderRejectsAuditBlock() {
        UUID orderId = UUID.randomUUID();
        when(repo.findPrescriptionBySourceOrder(orderId, LocalDate.now())).thenReturn(Optional.empty());
        when(patientClient.requireActive(patientId)).thenReturn(new PatientClient.PatientRef(
                patientId, "P20261031000001", "小明", LocalDate.now().minusYears(5)));
        when(repo.findDrugByCode("DRUG-AMOX-025")).thenReturn(Optional.of(new Drug(drugId,
                "DRUG-AMOX-025", "阿莫西林胶囊", null, "0.25g*24粒", "CAPSULE", "盒", "粒", 24,
                new BigDecimal("12.50"), null, "A", 0, false, true, 0L, null, true, false)));

        assertThatThrownBy(() -> service.createFromOrder(new CreateFromOrderReq(orderId, patientId,
                UUID.randomUUID(), "V1", null, "doctor01", LocalDate.now(),
                List.of(new PrescriptionLine("DRUG-AMOX-025", 1, null, null, null, null, null, null))),
                "clinical"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("儿童");
        verify(repo, never()).insertPrescription(any());
    }

    @Test
    @DisplayName("长嘱按日请领：同一医嘱不同供药日期各生成一张处方（幂等键为 orderId+date）")
    void createFromOrderIsIdempotentPerSupplyDate() {
        UUID orderId = UUID.randomUUID();
        LocalDate today = LocalDate.now();
        when(repo.findPrescriptionBySourceOrder(orderId, today)).thenReturn(Optional.empty());
        when(repo.findPrescriptionBySourceOrder(orderId, today.plusDays(1)))
                .thenReturn(Optional.of(rx(Prescription.PENDING_REVIEW, new BigDecimal("25.00"), 0L)));
        when(repo.findDrugByCode("DRUG-AMOX-025")).thenReturn(Optional.of(amoxicillin));

        OrderPrescriptionView first = service.createFromOrder(new CreateFromOrderReq(orderId, patientId,
                UUID.randomUUID(), "V1", null, "doctor01", today,
                List.of(new PrescriptionLine("DRUG-AMOX-025", 2, null, null, null, null, null, null))),
                "clinical");
        OrderPrescriptionView nextDay = service.createFromOrder(new CreateFromOrderReq(orderId, patientId,
                UUID.randomUUID(), "V1", null, "doctor01", today.plusDays(1),
                List.of(new PrescriptionLine("DRUG-AMOX-025", 2, null, null, null, null, null, null))),
                "clinical");

        assertThat(first.duplicate()).isFalse();
        assertThat(nextDay.duplicate()).isTrue();
        ArgumentCaptor<Prescription> captor = ArgumentCaptor.forClass(Prescription.class);
        verify(repo).insertPrescription(captor.capture());
        assertThat(captor.getValue().supplyDate()).isEqualTo(today);
    }

    @Test
    @DisplayName("停长嘱：作废所有未发药处方，已发药的留给退药流程")
    void cancelFromOrderCancelsAllPendingPrescriptions() {
        UUID orderId = UUID.randomUUID();
        UUID pendingId = UUID.randomUUID();
        UUID dispensedId = UUID.randomUUID();
        UUID approvedId = UUID.randomUUID();
        Prescription pending = new Prescription(pendingId, "RX-P1", patientId, "P1", null, null, null,
                "doctor01", Prescription.PENDING_REVIEW, null, null, null, new BigDecimal("25.00"),
                0L, Instant.now(), Prescription.BILLING_ORDER, orderId, LocalDate.now());
        Prescription dispensed = new Prescription(dispensedId, "RX-D1", patientId, "P1", null, null, null,
                "doctor01", Prescription.DISPENSED, null, null, null, new BigDecimal("25.00"),
                2L, Instant.now(), Prescription.BILLING_ORDER, orderId, LocalDate.now().minusDays(1));
        Prescription approved = new Prescription(approvedId, "RX-A1", patientId, "P1", null, null, null,
                "doctor01", Prescription.APPROVED, null, null, null, new BigDecimal("25.00"),
                0L, Instant.now(), Prescription.BILLING_ORDER, orderId, LocalDate.now().plusDays(1));
        when(repo.lockPrescriptionsBySourceOrder(orderId))
                .thenReturn(List.of(dispensed, pending, approved));
        when(repo.cancelPrescription(any(), anyLong(), anyString(), anyString())).thenReturn(true);

        PrescriptionCancelView view = service.cancelFromOrder(
                new CancelFromOrderReq(orderId, "停嘱", "doctor01"), "clinical");

        assertThat(view.cancelled()).isTrue();
        verify(repo).cancelPrescription(eq(pendingId), eq(0L), eq("停嘱"), eq("doctor01"));
        verify(repo).cancelPrescription(eq(approvedId), eq(0L), eq("停嘱"), eq("doctor01"));
        verify(repo, never()).cancelPrescription(eq(dispensedId), anyLong(), anyString(), anyString());
    }

    @Test
    @DisplayName("医嘱作废：从未转送（无处方）返回 cancelled=false，不报错")
    void cancelFromOrderWithoutPrescription() {
        UUID orderId = UUID.randomUUID();
        when(repo.lockPrescriptionsBySourceOrder(orderId)).thenReturn(List.of());

        PrescriptionCancelView view = service.cancelFromOrder(
                new CancelFromOrderReq(orderId, null, null), "clinical");

        assertThat(view.cancelled()).isFalse();
        assertThat(view.prescriptionId()).isNull();
        verify(repo, never()).cancelPrescription(any(), anyLong(), anyString(), anyString());
    }

    @Test
    @DisplayName("医嘱作废：APPROVED → CANCELLED，按来源处方的版本号 CAS 并留痕")
    void cancelFromOrderCancelsApproved() {
        UUID orderId = UUID.randomUUID();
        when(repo.lockPrescriptionsBySourceOrder(orderId))
                .thenReturn(List.of(rx(Prescription.APPROVED, new BigDecimal("25.00"), 3L)));
        when(repo.cancelPrescription(eq(rxId), eq(3L), anyString(), anyString())).thenReturn(true);

        PrescriptionCancelView view = service.cancelFromOrder(
                new CancelFromOrderReq(orderId, "医嘱撤销", "doctor01"), "clinical");

        assertThat(view.cancelled()).isTrue();
        assertThat(view.status()).isEqualTo(Prescription.CANCELLED);
        verify(repo).cancelPrescription(eq(rxId), eq(3L), eq("医嘱撤销"), eq("doctor01"));
        verify(repo).logChange(eq("doctor01"), eq("RX_CANCEL_FROM_ORDER"), eq("PRESCRIPTION"),
                anyString(), anyString());
    }

    @Test
    @DisplayName("医嘱作废：已发药处方拒绝（药已在患者手里，只能走退药）")
    void cancelFromOrderRejectsDispensed() {
        UUID orderId = UUID.randomUUID();
        when(repo.lockPrescriptionsBySourceOrder(orderId))
                .thenReturn(List.of(rx(Prescription.DISPENSED, new BigDecimal("25.00"), 4L)));

        assertThatThrownBy(() -> service.cancelFromOrder(
                new CancelFromOrderReq(orderId, null, null), "clinical"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("已发药");
        verify(repo, never()).cancelPrescription(any(), anyLong(), anyString(), anyString());
    }

    @Test
    @DisplayName("医嘱作废：已作废幂等返回，不重复写库")
    void cancelFromOrderIsIdempotent() {
        UUID orderId = UUID.randomUUID();
        when(repo.lockPrescriptionsBySourceOrder(orderId))
                .thenReturn(List.of(rx(Prescription.CANCELLED, new BigDecimal("25.00"), 5L)));

        PrescriptionCancelView view = service.cancelFromOrder(
                new CancelFromOrderReq(orderId, null, null), "clinical");

        assertThat(view.cancelled()).isTrue();
        verify(repo, never()).cancelPrescription(any(), anyLong(), anyString(), anyString());
    }

    // ---------------------------------------------------------------- 审核

    @Test
    @DisplayName("审核通过：PENDING_REVIEW → APPROVED")
    void reviewApproves() {
        when(repo.lockPrescription(rxId)).thenReturn(Optional.of(rx(Prescription.PENDING_REVIEW,
                new BigDecimal("25.00"), 2L)));
        when(repo.updatePrescriptionStatus(eq(rxId), eq(Prescription.PENDING_REVIEW),
                eq(Prescription.APPROVED), eq(2L), eq("pharm01"), eq(null))).thenReturn(true);
        when(repo.findPrescription(rxId)).thenReturn(Optional.of(rx(Prescription.APPROVED,
                new BigDecimal("25.00"), 3L)));

        PrescriptionView view = service.review(rxId, true, null, "pharm01");

        assertThat(view.prescription().status()).isEqualTo(Prescription.APPROVED);
        verify(repo).logChange(eq("pharm01"), eq("RX_APPROVE"), eq("PRESCRIPTION"),
                eq(rxId.toString()), anyString());
    }

    @Test
    @DisplayName("驳回必须写理由")
    void reviewRejectRequiresReason() {
        when(repo.lockPrescription(rxId)).thenReturn(Optional.of(rx(Prescription.PENDING_REVIEW,
                new BigDecimal("25.00"), 0L)));

        assertThatThrownBy(() -> service.review(rxId, false, " ", "pharm01"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("理由");
    }

    @Test
    @DisplayName("非待审核处方不能再审核")
    void reviewRejectsNonPending() {
        when(repo.lockPrescription(rxId)).thenReturn(Optional.of(rx(Prescription.DISPENSED,
                new BigDecimal("25.00"), 5L)));

        assertThatThrownBy(() -> service.review(rxId, true, null, "pharm01"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.PRESCRIPTION_STATUS_INVALID);
    }

    // ---------------------------------------------------------------- 发药

    @Test
    @DisplayName("发药：未审核的处方拒绝")
    void dispenseRequiresApproved() {
        when(repo.lockPrescription(rxId)).thenReturn(Optional.of(rx(Prescription.PENDING_REVIEW,
                new BigDecimal("25.00"), 0L)));

        assertThatThrownBy(() -> service.dispense(rxId, null, "pharm01"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.PRESCRIPTION_STATUS_INVALID);
    }

    @Test
    @DisplayName("发药：FEFO 跨批次拆分（近效期先出）")
    void dispenseSplitsAcrossBatchesByFefo() {
        DrugBatch near = batch("B-NEAR", 3, 30);
        DrugBatch far = batch("B-FAR", 10, 300);
        PrescriptionItem item = new PrescriptionItem(UUID.randomUUID(), rxId, drugId,
                amoxicillin.drugCode(), amoxicillin.name(), amoxicillin.spec(), 5, "盒",
                null, null, null, amoxicillin.price(), new BigDecimal("62.50"), null, null, null, null);

        when(repo.lockPrescription(rxId)).thenReturn(Optional.of(rx(Prescription.APPROVED,
                new BigDecimal("62.50"), 3L)));
        when(repo.findDispenseByPrescription(rxId)).thenReturn(Optional.empty());
        when(repo.listPrescriptionItems(rxId)).thenReturn(List.of(item));
        when(repo.findDrug(drugId)).thenReturn(Optional.of(amoxicillin));
        when(repo.listAvailableBatches(eq(drugId), any(LocalDate.class))).thenReturn(List.of(near, far));
        when(repo.deductBatch(any(), anyInt())).thenReturn(true);
        when(repo.updatePrescriptionStatus(eq(rxId), eq(Prescription.APPROVED),
                eq(Prescription.DISPENSED), eq(3L), eq(null), eq(null))).thenReturn(true);

        DispenseView view = service.dispense(rxId, null, "pharm01");

        assertThat(view.dispense().dispenseNo()).isEqualTo("DP20261031000001");
        verify(repo).deductBatch(near.id(), 3);
        verify(repo).deductBatch(far.id(), 2);
        // 跨两个批次 → 两条发药明细
        verify(repo, times(2)).insertDispenseItem(any(DispenseItem.class));
    }

    @Test
    @DisplayName("发药：库存不足整笔拒绝（不允许部分发药）")
    void dispenseRejectsWhenStockInsufficient() {
        DrugBatch only = batch("B-1", 2, 30);
        PrescriptionItem item = new PrescriptionItem(UUID.randomUUID(), rxId, drugId,
                amoxicillin.drugCode(), amoxicillin.name(), amoxicillin.spec(), 5, "盒",
                null, null, null, amoxicillin.price(), new BigDecimal("62.50"), null, null, null, null);

        when(repo.lockPrescription(rxId)).thenReturn(Optional.of(rx(Prescription.APPROVED,
                new BigDecimal("62.50"), 3L)));
        when(repo.findDispenseByPrescription(rxId)).thenReturn(Optional.empty());
        when(repo.listPrescriptionItems(rxId)).thenReturn(List.of(item));
        when(repo.findDrug(drugId)).thenReturn(Optional.of(amoxicillin));
        when(repo.listAvailableBatches(eq(drugId), any(LocalDate.class))).thenReturn(List.of(only));
        when(repo.deductBatch(any(), anyInt())).thenReturn(true);

        assertThatThrownBy(() -> service.dispense(rxId, null, "pharm01"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.DRUG_STOCK_INSUFFICIENT);
        verify(repo, never()).insertDispense(any());
    }

    @Test
    @DisplayName("麻醉药品：缺少核对人拒绝")
    void dispenseNarcoticRequiresChecker() {
        PrescriptionItem item = new PrescriptionItem(UUID.randomUUID(), rxId, morphine.id(),
                morphine.drugCode(), morphine.name(), morphine.spec(), 1, "支",
                null, null, null, morphine.price(), new BigDecimal("5.60"), null, null, null, null);

        when(repo.lockPrescription(rxId)).thenReturn(Optional.of(rx(Prescription.APPROVED,
                new BigDecimal("5.60"), 3L)));
        when(repo.findDispenseByPrescription(rxId)).thenReturn(Optional.empty());
        when(repo.listPrescriptionItems(rxId)).thenReturn(List.of(item));
        when(repo.findDrug(morphine.id())).thenReturn(Optional.of(morphine));

        assertThatThrownBy(() -> service.dispense(rxId, null, "pharm01"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("双人核对");
    }

    @Test
    @DisplayName("麻醉药品：核对人与发药人相同拒绝")
    void dispenseNarcoticRejectsSameChecker() {
        PrescriptionItem item = new PrescriptionItem(UUID.randomUUID(), rxId, morphine.id(),
                morphine.drugCode(), morphine.name(), morphine.spec(), 1, "支",
                null, null, null, morphine.price(), new BigDecimal("5.60"), null, null, null, null);

        when(repo.lockPrescription(rxId)).thenReturn(Optional.of(rx(Prescription.APPROVED,
                new BigDecimal("5.60"), 3L)));
        when(repo.findDispenseByPrescription(rxId)).thenReturn(Optional.empty());
        when(repo.listPrescriptionItems(rxId)).thenReturn(List.of(item));
        when(repo.findDrug(morphine.id())).thenReturn(Optional.of(morphine));

        assertThatThrownBy(() -> service.dispense(rxId, "pharm01", "pharm01"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("不能与发药人相同");
    }

    @Test
    @DisplayName("已发药的处方不能再发")
    void dispenseRejectsAlreadyDispensed() {
        when(repo.lockPrescription(rxId)).thenReturn(Optional.of(rx(Prescription.APPROVED,
                new BigDecimal("25.00"), 3L)));
        when(repo.findDispenseByPrescription(rxId)).thenReturn(Optional.of(dispense(Dispense.DISPENSED)));

        assertThatThrownBy(() -> service.dispense(rxId, null, "pharm01"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("已发过药");
    }

    // ---------------------------------------------------------------- 退药

    @Test
    @DisplayName("退药：超过可退数量拒绝")
    void returnRejectsExceed() {
        UUID dispenseId = UUID.randomUUID();
        UUID itemId = UUID.randomUUID();
        DispenseItem item = new DispenseItem(itemId, dispenseId, UUID.randomUUID(), drugId,
                UUID.randomUUID(), "B-1", LocalDate.now().plusDays(100), 5, 2,
                amoxicillin.price(), new BigDecimal("62.50"));
        when(repo.lockDispense(dispenseId)).thenReturn(Optional.of(dispense(Dispense.DISPENSED)));
        when(repo.findDispenseItem(itemId)).thenReturn(Optional.of(item));

        assertThatThrownBy(() -> service.returnDrug(dispenseId,
                new ReturnReq(itemId, 4, "患者停药"), "pharm01"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.DRUG_RETURN_EXCEEDS);
        verify(repo, never()).addReturnedQuantity(any(), anyInt());
    }

    @Test
    @DisplayName("退药：部分退 → PARTIAL_RETURNED，并回补原批次")
    void returnPartialUpdatesStatus() {
        UUID dispenseId = UUID.randomUUID();
        UUID itemId = UUID.randomUUID();
        UUID batchId = UUID.randomUUID();
        DispenseItem before = new DispenseItem(itemId, dispenseId, UUID.randomUUID(), drugId,
                batchId, "B-1", LocalDate.now().plusDays(100), 5, 0,
                amoxicillin.price(), new BigDecimal("62.50"));
        DispenseItem after = new DispenseItem(itemId, dispenseId, before.prescriptionItemId(), drugId,
                batchId, "B-1", before.expireDate(), 5, 2, amoxicillin.price(), before.amount());

        when(repo.lockDispense(dispenseId)).thenReturn(Optional.of(dispense(Dispense.DISPENSED)));
        when(repo.findDispenseItem(itemId)).thenReturn(Optional.of(before));
        when(repo.addReturnedQuantity(itemId, 2)).thenReturn(true);
        when(repo.addBatchQuantity(batchId, 2)).thenReturn(true);
        when(repo.listDispenseItems(dispenseId)).thenReturn(List.of(after));
        when(repo.updateDispenseStatus(eq(dispenseId), eq(Dispense.PARTIAL_RETURNED), eq(0L)))
                .thenReturn(true);

        DispenseView view = service.returnDrug(dispenseId,
                new ReturnReq(itemId, 2, "患者停药"), "pharm01");

        assertThat(view.dispense()).isNotNull();
        verify(repo).addBatchQuantity(batchId, 2);
        verify(repo).insertReturn(any());
        verify(repo).updateDispenseStatus(eq(dispenseId), eq(Dispense.PARTIAL_RETURNED), eq(0L));
        verify(repo).logChange(eq("pharm01"), eq("DISPENSE_RETURN"), eq("DISPENSE"),
                eq(dispenseId.toString()), anyString());
    }

    @Test
    @DisplayName("退药：全部退完 → RETURNED")
    void returnAllUpdatesStatusToReturned() {
        UUID dispenseId = UUID.randomUUID();
        UUID itemId = UUID.randomUUID();
        UUID batchId = UUID.randomUUID();
        DispenseItem before = new DispenseItem(itemId, dispenseId, UUID.randomUUID(), drugId,
                batchId, "B-1", LocalDate.now().plusDays(100), 3, 0,
                amoxicillin.price(), new BigDecimal("37.50"));
        DispenseItem after = new DispenseItem(itemId, dispenseId, before.prescriptionItemId(), drugId,
                batchId, "B-1", before.expireDate(), 3, 3, amoxicillin.price(), before.amount());

        when(repo.lockDispense(dispenseId)).thenReturn(Optional.of(dispense(Dispense.DISPENSED)));
        when(repo.findDispenseItem(itemId)).thenReturn(Optional.of(before));
        when(repo.addReturnedQuantity(itemId, 3)).thenReturn(true);
        when(repo.addBatchQuantity(batchId, 3)).thenReturn(true);
        when(repo.listDispenseItems(dispenseId)).thenReturn(List.of(after));
        when(repo.updateDispenseStatus(eq(dispenseId), eq(Dispense.RETURNED), eq(0L))).thenReturn(true);

        service.returnDrug(dispenseId, new ReturnReq(itemId, 3, "整单退回"), "pharm01");

        verify(repo).updateDispenseStatus(eq(dispenseId), eq(Dispense.RETURNED), eq(0L));
        // 全部退回必须同步作废处方，否则临床停嘱/撤销的下游作废永远 409（第六轮 MF-03）
        verify(repo).returnPrescription(eq(rxId), anyString(), eq("pharm01"));
    }

    // ---------------------------------------------------------------- 发药计费（N2 收尾）

    @Test
    @DisplayName("发药计费：DISPENSE 模式必须关联就诊")
    void createRejectsDispenseModeWithoutVisit() {
        when(repo.findDrugByCode("DRUG-AMOX-025")).thenReturn(Optional.of(amoxicillin));

        assertThatThrownBy(() -> service.create(new CreatePrescriptionReq(patientId, null, null, null,
                Prescription.BILLING_DISPENSE,
                List.of(new PrescriptionLine("DRUG-AMOX-025", 1, null, null, null, null, null, null))), "doctor01"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("visitId");
        verify(repo, never()).insertPrescription(any());
    }

    @Test
    @DisplayName("发药计费：DISPENSE 模式发药后调用收费域并置 CHARGED")
    void dispenseInDispenseModeChargesClinical() {
        UUID visitId = UUID.randomUUID();
        DrugBatch batch = batch("B-1", 10, 300);
        PrescriptionItem item = new PrescriptionItem(UUID.randomUUID(), rxId, drugId,
                amoxicillin.drugCode(), amoxicillin.name(), amoxicillin.spec(), 2, "盒",
                null, null, null, amoxicillin.price(), new BigDecimal("25.00"), null, null, null, null);

        when(repo.lockPrescription(rxId)).thenReturn(Optional.of(new Prescription(
                rxId, "RX20261031000001", patientId, "P20261031000001", visitId, null, null,
                "doctor01", Prescription.APPROVED, null, null, null,
                new BigDecimal("25.00"), 3L, Instant.now(), Prescription.BILLING_DISPENSE, null, null)));
        when(repo.findDispenseByPrescription(rxId)).thenReturn(Optional.empty());
        when(repo.listPrescriptionItems(rxId)).thenReturn(List.of(item));
        when(repo.findDrug(drugId)).thenReturn(Optional.of(amoxicillin));
        when(repo.listAvailableBatches(eq(drugId), any(LocalDate.class))).thenReturn(List.of(batch));
        when(repo.deductBatch(any(), anyInt())).thenReturn(true);
        when(repo.updatePrescriptionStatus(eq(rxId), eq(Prescription.APPROVED),
                eq(Prescription.DISPENSED), eq(3L), eq(null), eq(null))).thenReturn(true);
        when(chargeClient.charge(any(), any(), any(), anyString()))
                .thenReturn(new ClinicalChargeClient.Charged("C20261031000001", new BigDecimal("25.00")));
        when(repo.findDispense(any())).thenReturn(Optional.of(dispense(Dispense.DISPENSED,
                Dispense.BILLING_CHARGED)));

        service.dispense(rxId, null, "pharm01");

        verify(repo).insertDispense(org.mockito.ArgumentMatchers.argThat(
                d -> Dispense.BILLING_PENDING.equals(d.billingStatus())));
        verify(chargeClient).charge(eq(visitId), any(UUID.class), any(), eq("pharm01"));
        verify(repo).updateDispenseBillingStatus(any(UUID.class), eq(Dispense.BILLING_CHARGED));
    }

    @Test
    @DisplayName("发药计费：收费域不可用 → 标记 CHARGE_FAILED（不回滚发药）")
    void dispenseChargeFailureIsRecordedForRetry() {
        UUID visitId = UUID.randomUUID();
        DrugBatch batch = batch("B-1", 10, 300);
        PrescriptionItem item = new PrescriptionItem(UUID.randomUUID(), rxId, drugId,
                amoxicillin.drugCode(), amoxicillin.name(), amoxicillin.spec(), 1, "盒",
                null, null, null, amoxicillin.price(), new BigDecimal("12.50"), null, null, null, null);

        when(repo.lockPrescription(rxId)).thenReturn(Optional.of(new Prescription(
                rxId, "RX20261031000001", patientId, "P20261031000001", visitId, null, null,
                "doctor01", Prescription.APPROVED, null, null, null,
                new BigDecimal("12.50"), 3L, Instant.now(), Prescription.BILLING_DISPENSE, null, null)));
        when(repo.findDispenseByPrescription(rxId)).thenReturn(Optional.empty());
        when(repo.listPrescriptionItems(rxId)).thenReturn(List.of(item));
        when(repo.findDrug(drugId)).thenReturn(Optional.of(amoxicillin));
        when(repo.listAvailableBatches(eq(drugId), any(LocalDate.class))).thenReturn(List.of(batch));
        when(repo.deductBatch(any(), anyInt())).thenReturn(true);
        when(repo.updatePrescriptionStatus(any(), any(), any(), anyLong(), any(), any()))
                .thenReturn(true);
        when(chargeClient.charge(any(), any(), any(), anyString()))
                .thenThrow(new BusinessException(ErrorCode.UPSTREAM_UNAVAILABLE, "收费服务暂不可用"));

        service.dispense(rxId, null, "pharm01");

        verify(repo).updateDispenseBillingStatus(any(UUID.class), eq(Dispense.BILLING_CHARGE_FAILED));
        verify(repo).logChange(eq("pharm01"), eq("DISPENSE_CHARGE_FAILED"), eq("DISPENSE"),
                anyString(), anyString());
    }

    @Test
    @DisplayName("退药冲减：按累计已退金额调用收费域，已结算则标记待退费")
    void returnInDispenseModeSendsCumulativeRefund() {
        UUID visitId = UUID.randomUUID();
        UUID dispenseId = UUID.randomUUID();
        UUID itemId = UUID.randomUUID();
        UUID batchId = UUID.randomUUID();
        DispenseItem before = new DispenseItem(itemId, dispenseId, UUID.randomUUID(), drugId,
                batchId, "B-1", LocalDate.now().plusDays(100), 5, 0,
                amoxicillin.price(), new BigDecimal("62.50"));
        DispenseItem after = new DispenseItem(itemId, dispenseId, before.prescriptionItemId(), drugId,
                batchId, "B-1", before.expireDate(), 5, 2, amoxicillin.price(), before.amount());

        when(repo.lockDispense(dispenseId)).thenReturn(Optional.of(dispense(Dispense.DISPENSED,
                Dispense.BILLING_CHARGED)));
        when(repo.findDispenseItem(itemId)).thenReturn(Optional.of(before));
        when(repo.addReturnedQuantity(itemId, 2)).thenReturn(true);
        when(repo.addBatchQuantity(batchId, 2)).thenReturn(true);
        when(repo.listDispenseItems(dispenseId)).thenReturn(List.of(after));
        when(repo.updateDispenseStatus(eq(dispenseId), eq(Dispense.PARTIAL_RETURNED), eq(0L)))
                .thenReturn(true);
        when(repo.findPrescription(any())).thenReturn(Optional.of(new Prescription(
                rxId, "RX20261031000001", patientId, "P20261031000001", visitId, null, null,
                "doctor01", Prescription.DISPENSED, null, null, null,
                new BigDecimal("62.50"), 4L, Instant.now(), Prescription.BILLING_DISPENSE, null, null)));
        when(chargeClient.refund(any(), any(), anyString(), anyString()))
                .thenReturn(new ClinicalChargeClient.Refunded(BigDecimal.ZERO));

        service.returnDrug(dispenseId, new ReturnReq(itemId, 2, "患者停药"), "pharm01");

        // 累计已退 = 单价 12.50 × 已退 2 = 25.00
        verify(chargeClient).refund(eq(itemId), org.mockito.ArgumentMatchers.eq(new BigDecimal("25.00")),
                anyString(), eq("pharm01"));
        verify(repo).updateDispenseBillingStatus(eq(dispenseId), eq(Dispense.BILLING_CHARGED));
    }

    @Test
    @DisplayName("医嘱计费退药（B3）：按累计已退金额冲减临床医嘱，药房不持账")
    void returnInOrderModeRefundsClinicalOrder() {
        UUID visitId = UUID.randomUUID();
        UUID dispenseId = UUID.randomUUID();
        UUID itemId = UUID.randomUUID();
        UUID batchId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();
        DispenseItem before = new DispenseItem(itemId, dispenseId, UUID.randomUUID(), drugId,
                batchId, "B-1", LocalDate.now().plusDays(100), 5, 0,
                amoxicillin.price(), new BigDecimal("62.50"));
        DispenseItem after = new DispenseItem(itemId, dispenseId, before.prescriptionItemId(), drugId,
                batchId, "B-1", before.expireDate(), 5, 2, amoxicillin.price(), before.amount());

        when(repo.lockDispense(dispenseId)).thenReturn(Optional.of(dispense(Dispense.DISPENSED,
                Dispense.BILLING_NONE)));
        when(repo.findDispenseItem(itemId)).thenReturn(Optional.of(before));
        when(repo.addReturnedQuantity(itemId, 2)).thenReturn(true);
        when(repo.addBatchQuantity(batchId, 2)).thenReturn(true);
        when(repo.listDispenseItems(dispenseId)).thenReturn(List.of(after));
        when(repo.updateDispenseStatus(eq(dispenseId), eq(Dispense.PARTIAL_RETURNED), eq(0L)))
                .thenReturn(true);
        when(repo.findPrescription(any())).thenReturn(Optional.of(new Prescription(
                rxId, "RX20261031000001", patientId, "P20261031000001", visitId, null, null,
                "doctor01", Prescription.DISPENSED, null, null, null,
                new BigDecimal("62.50"), 4L, Instant.now(), Prescription.BILLING_ORDER, orderId, null)));
        when(chargeClient.refundOrder(any(), any(), any(), any(), anyString(), anyString()))
                .thenReturn(new ClinicalChargeClient.OrderRefunded(BigDecimal.ZERO,
                        BigDecimal.ZERO, BigDecimal.ZERO, false));

        service.returnDrug(dispenseId, new ReturnReq(itemId, 2, "患者停药"), "pharm01");

        // 累计已退 = 12.50 × 2 = 25.00；itemAmount = 整条发药明细 62.50
        verify(chargeClient).refundOrder(eq(orderId), eq(itemId),
                org.mockito.ArgumentMatchers.eq(new BigDecimal("62.50")),
                org.mockito.ArgumentMatchers.eq(new BigDecimal("25.00")),
                anyString(), eq("pharm01"));
        verify(repo).updateDispenseBillingStatus(eq(dispenseId), eq(Dispense.BILLING_NONE));
        verify(chargeClient, never()).refund(any(), any(), anyString(), anyString());
    }

    @Test
    @DisplayName("医嘱计费退药：冲减失败置 REFUND_FAILED，供 billing-retry 补偿")
    void orderRefundFailureIsRecordedForRetry() {
        UUID visitId = UUID.randomUUID();
        UUID dispenseId = UUID.randomUUID();
        UUID itemId = UUID.randomUUID();
        UUID batchId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();
        DispenseItem before = new DispenseItem(itemId, dispenseId, UUID.randomUUID(), drugId,
                batchId, "B-1", LocalDate.now().plusDays(100), 2, 0,
                amoxicillin.price(), new BigDecimal("25.00"));
        DispenseItem after = new DispenseItem(itemId, dispenseId, before.prescriptionItemId(), drugId,
                batchId, "B-1", before.expireDate(), 2, 2, amoxicillin.price(), before.amount());

        when(repo.lockDispense(dispenseId)).thenReturn(Optional.of(dispense(Dispense.DISPENSED,
                Dispense.BILLING_NONE)));
        when(repo.findDispenseItem(itemId)).thenReturn(Optional.of(before));
        when(repo.addReturnedQuantity(itemId, 2)).thenReturn(true);
        when(repo.addBatchQuantity(batchId, 2)).thenReturn(true);
        when(repo.listDispenseItems(dispenseId)).thenReturn(List.of(after));
        when(repo.updateDispenseStatus(eq(dispenseId), eq(Dispense.RETURNED), eq(0L))).thenReturn(true);
        when(repo.findPrescription(any())).thenReturn(Optional.of(new Prescription(
                rxId, "RX20261031000001", patientId, "P20261031000001", visitId, null, null,
                "doctor01", Prescription.DISPENSED, null, null, null,
                new BigDecimal("25.00"), 4L, Instant.now(), Prescription.BILLING_ORDER, orderId, null)));
        when(chargeClient.refundOrder(any(), any(), any(), any(), anyString(), anyString()))
                .thenThrow(new BusinessException(ErrorCode.UPSTREAM_UNAVAILABLE, "收费服务暂不可用"));

        service.returnDrug(dispenseId, new ReturnReq(itemId, 2, "整单退回"), "pharm01");

        verify(repo).updateDispenseBillingStatus(eq(dispenseId), eq(Dispense.BILLING_REFUND_FAILED));
    }

    // ---------------------------------------------------------------- 前置审方（用药安全）

    @Test
    @DisplayName("前置审方：儿童禁用药品开方直接拒绝，且不落处方")
    void createRejectsPediatricContraindicatedDrug() {
        when(patientClient.requireActive(patientId)).thenReturn(new PatientClient.PatientRef(
                patientId, "P20261031000001", "小明", LocalDate.now().minusYears(5)));
        Drug childForbidden = new Drug(UUID.randomUUID(), "DRUG-KID", "儿童禁用药", null, "1g",
                "TABLET", "盒", "片", 10, new BigDecimal("10.00"), null, "A", 0, false, true, 0L,
                null, true, false);
        when(repo.findDrugByCode("DRUG-KID")).thenReturn(Optional.of(childForbidden));

        assertThatThrownBy(() -> service.create(new CreatePrescriptionReq(patientId, null, null, null,
                null, List.of(new PrescriptionLine("DRUG-KID", 1, null, null, null, null, null, null))),
                "doctor01"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("用药禁忌");
        verify(repo, never()).insertPrescription(any());
    }

    @Test
    @DisplayName("前置审方：WARN 级问题落审方记录，处方仍可开立")
    void createRecordsWarnFindings() {
        Drug specialAntibiotic = new Drug(UUID.randomUUID(), "DRUG-ABX3", "特殊级抗菌药", null, "1g",
                "INJECTION", "支", "支", 1, new BigDecimal("88.00"), null, "B", 3, false, true, 0L,
                null, false, false);
        when(repo.findDrugByCode("DRUG-ABX3")).thenReturn(Optional.of(specialAntibiotic));

        service.create(new CreatePrescriptionReq(patientId, null, null, null, null,
                List.of(new PrescriptionLine("DRUG-ABX3", 1, null, null, null, null, null, null))),
                "doctor01");

        verify(repo).insertAudit(any());
        verify(repo).insertPrescription(any(Prescription.class));
    }

    // ---------------------------------------------------------------- 麻醉药品（V6 五专）

    @Test
    @DisplayName("麻醉药品：医师不在处方权名单 → 422，不落处方")
    void createNarcoticRequiresLicence() {
        when(repo.findDrugByCode(morphine.drugCode())).thenReturn(Optional.of(morphine));
        when(repo.findNarcoticPrescriber("doctor01")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.create(new CreatePrescriptionReq(patientId, null, null,
                null, null, List.of(new PrescriptionLine(morphine.drugCode(), 1, null, null, null,
                        null, null, null))), "doctor01"))
                .isInstanceOfSatisfying(BusinessException.class, e ->
                        assertThat(e.errorCode()).isEqualTo(ErrorCode.NARCOTIC_PRESCRIBER_REQUIRED));
        verify(repo, never()).insertPrescription(any());
    }

    @Test
    @DisplayName("麻醉药品：上次空安瓿未回收 → 422，不落处方")
    void createNarcoticRequiresAmpouleReturned() {
        when(repo.findDrugByCode(morphine.drugCode())).thenReturn(Optional.of(morphine));
        when(repo.findNarcoticPrescriber("doctor01")).thenReturn(Optional.of(
                prescriber(true)));
        when(repo.countOutstandingNarcotic(patientId, morphine.id())).thenReturn(1L);

        assertThatThrownBy(() -> service.create(new CreatePrescriptionReq(patientId, null, null,
                null, null, List.of(new PrescriptionLine(morphine.drugCode(), 1, null, null, null,
                        null, null, null))), "doctor01"))
                .isInstanceOfSatisfying(BusinessException.class, e ->
                        assertThat(e.errorCode()).isEqualTo(ErrorCode.NARCOTIC_AMPOULE_OUTSTANDING));
        verify(repo, never()).insertPrescription(any());
    }

    @Test
    @DisplayName("麻醉药品：有处方权且空安瓿已回收 → 可以开方")
    void createNarcoticOk() {
        when(repo.findDrugByCode(morphine.drugCode())).thenReturn(Optional.of(morphine));
        when(repo.findNarcoticPrescriber("doctor01")).thenReturn(Optional.of(
                prescriber(true)));
        when(repo.countOutstandingNarcotic(patientId, morphine.id())).thenReturn(0L);

        service.create(new CreatePrescriptionReq(patientId, null, null, null, null,
                List.of(new PrescriptionLine(morphine.drugCode(), 1, null, null, null, null, null,
                        null))), "doctor01");

        verify(repo).insertPrescription(any(Prescription.class));
    }

    @Test
    @DisplayName("麻醉药品：发药成功同事务写专册登记（待回收空安瓿）")
    void dispenseNarcoticWritesRegister() {
        DrugBatch batch = new DrugBatch(UUID.randomUUID(), morphine.id(), morphine.drugCode(),
                morphine.name(), "M-1", LocalDate.now().plusDays(300), 5, "国药", 0L);
        PrescriptionItem item = new PrescriptionItem(UUID.randomUUID(), rxId, morphine.id(),
                morphine.drugCode(), morphine.name(), morphine.spec(), 1, "支",
                null, null, null, morphine.price(), new BigDecimal("5.60"), null, null, null, null);

        when(repo.lockPrescription(rxId)).thenReturn(Optional.of(rx(Prescription.APPROVED,
                new BigDecimal("5.60"), 3L)));
        when(repo.findDispenseByPrescription(rxId)).thenReturn(Optional.empty());
        when(repo.listPrescriptionItems(rxId)).thenReturn(List.of(item));
        when(repo.findDrug(morphine.id())).thenReturn(Optional.of(morphine));
        when(repo.listAvailableBatches(eq(morphine.id()), any(LocalDate.class)))
                .thenReturn(List.of(batch));
        when(repo.deductBatch(any(), anyInt())).thenReturn(true);
        when(repo.updatePrescriptionStatus(any(), any(), any(), anyLong(), any(), any()))
                .thenReturn(true);

        service.dispense(rxId, "pharm02", "pharm01");

        verify(repo).insertNarcoticRegister(any());
        verify(repo).logChange(eq("pharm01"), eq("DISPENSE"), eq("PRESCRIPTION"),
                eq(rxId.toString()), anyString());
    }

    private com.medassist.pharmacy.domain.PharmacyModel.NarcoticPrescriber prescriber(
            boolean enabled) {
        return new com.medassist.pharmacy.domain.PharmacyModel.NarcoticPrescriber(
                UUID.randomUUID(), "doctor01", "MZ-001", "admin01", Instant.now(),
                enabled ? null : "admin01", enabled ? null : Instant.now(),
                enabled ? null : "离职", enabled, 0L);
    }
}
