package com.medassist.pharmacy.service;

import com.medassist.common.api.ErrorCode;
import com.medassist.common.exception.BusinessException;
import com.medassist.pharmacy.client.PatientClient;
import com.medassist.pharmacy.domain.PharmacyModel.Dispense;
import com.medassist.pharmacy.domain.PharmacyModel.DispenseItem;
import com.medassist.pharmacy.domain.PharmacyModel.Drug;
import com.medassist.pharmacy.domain.PharmacyModel.DrugBatch;
import com.medassist.pharmacy.domain.PharmacyModel.Prescription;
import com.medassist.pharmacy.domain.PharmacyModel.PrescriptionItem;
import com.medassist.pharmacy.repo.PharmacyRepository;
import com.medassist.pharmacy.web.PharmacyDtos.CreatePrescriptionReq;
import com.medassist.pharmacy.web.PharmacyDtos.DispenseView;
import com.medassist.pharmacy.web.PharmacyDtos.PrescriptionLine;
import com.medassist.pharmacy.web.PharmacyDtos.PrescriptionView;
import com.medassist.pharmacy.web.PharmacyDtos.ReturnReq;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
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
    private PrescriptionService service;

    private final UUID patientId = UUID.randomUUID();
    private final UUID drugId = UUID.randomUUID();
    private final UUID rxId = UUID.randomUUID();

    private final Drug amoxicillin = new Drug(drugId, "DRUG-AMOX-025", "阿莫西林胶囊", "阿莫仙",
            "0.25g*24粒", "CAPSULE", "盒", "粒", 24, new BigDecimal("12.50"),
            "XA01AA0401010101010", "A", 1, false, true, 0L);

    private final Drug morphine = new Drug(UUID.randomUUID(), "DRUG-MOR-10", "盐酸吗啡注射液", null,
            "10mg", "INJECTION", "支", "支", 1, new BigDecimal("5.60"),
            null, "B", 0, true, true, 0L);

    @BeforeEach
    void setUp() {
        repo = mock(PharmacyRepository.class);
        patientClient = mock(PatientClient.class);
        service = new PrescriptionService(repo, patientClient, JsonMapper.builder().build());

        when(patientClient.requireActive(patientId))
                .thenReturn(new PatientClient.PatientRef(patientId, "P20261031000001", "张三"));
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
        return new Prescription(rxId, "RX20261031000001", patientId, "P20261031000001",
                null, null, null, "doctor01", status, null, null, null, total, version, Instant.now());
    }

    private Dispense dispense(String status) {
        return new Dispense(UUID.randomUUID(), "DP20261031000001", rxId, "pharm01", null,
                status, new BigDecimal("25.00"), Instant.now(), 0L);
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

        PrescriptionView view = service.create(new CreatePrescriptionReq(patientId, null, null, null,
                List.of(new PrescriptionLine("DRUG-AMOX-025", 2, "0.5g", "tid", 3))), "doctor01");

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

        assertThatThrownBy(() -> service.create(new CreatePrescriptionReq(patientId, null, null, null,
                List.of(new PrescriptionLine("DRUG-AMOX-025", 1, null, null, null),
                        new PrescriptionLine("DRUG-AMOX-025", 2, null, null, null))), "doctor01"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("重复开立");
        verify(repo, never()).insertPrescription(any());
    }

    @Test
    @DisplayName("开方：药品不存在或已停用 → 404")
    void createRejectsUnknownDrug() {
        when(repo.findDrugByCode("DRUG-X")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.create(new CreatePrescriptionReq(patientId, null, null, null,
                List.of(new PrescriptionLine("DRUG-X", 1, null, null, null))), "doctor01"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.DRUG_NOT_FOUND);
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
                null, null, null, amoxicillin.price(), new BigDecimal("62.50"));

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
                null, null, null, amoxicillin.price(), new BigDecimal("62.50"));

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
                null, null, null, morphine.price(), new BigDecimal("5.60"));

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
                null, null, null, morphine.price(), new BigDecimal("5.60"));

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
    }
}
