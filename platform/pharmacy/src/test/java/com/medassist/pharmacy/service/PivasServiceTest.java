package com.medassist.pharmacy.service;

import com.medassist.common.api.ErrorCode;
import com.medassist.common.exception.BusinessException;
import com.medassist.pharmacy.domain.PharmacyModel.DrugBatch;
import com.medassist.pharmacy.domain.PharmacyModel.PivasItem;
import com.medassist.pharmacy.domain.PharmacyModel.PivasItemBatch;
import com.medassist.pharmacy.domain.PharmacyModel.PivasOrder;
import com.medassist.pharmacy.domain.PharmacyModel.PivasStatusCount;
import com.medassist.pharmacy.domain.PharmacyModel.Prescription;
import com.medassist.pharmacy.domain.PharmacyModel.PrescriptionItem;
import com.medassist.pharmacy.repo.PharmacyRepository;
import com.medassist.pharmacy.web.PivasDtos.CheckPivasReq;
import com.medassist.pharmacy.web.PivasDtos.CreatePivasOrderReq;
import com.medassist.pharmacy.web.PivasDtos.PivasSummary;
import com.medassist.pharmacy.web.PivasDtos.PivasView;
import com.medassist.pharmacy.web.PivasDtos.RejectPivasReq;
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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 静配中心：建单资格、FEFO 扣批次、双人复核与成品标签追溯。
 */
class PivasServiceTest {

    private PharmacyRepository repo;
    private PivasService service;

    private final UUID orderId = UUID.randomUUID();
    private final UUID rxId = UUID.randomUUID();
    private final UUID patientId = UUID.randomUUID();
    private final UUID drugId = UUID.randomUUID();
    private final UUID itemId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        repo = mock(PharmacyRepository.class);
        service = new PivasService(repo, JsonMapper.builder().build());
    }

    private void assertCode(Runnable call, ErrorCode code) {
        assertThatThrownBy(call::run)
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(code);
    }

    private Prescription rx(String status) {
        return new Prescription(rxId, "RX20261104000301", patientId, "P20261104000301", null,
                "V20261104000301", null, "doctor01", status, null, null, null,
                new BigDecimal("100.00"), 1L, Instant.now(), Prescription.BILLING_ORDER, null, null);
    }

    private PrescriptionItem rxItem() {
        return new PrescriptionItem(itemId, rxId, drugId, "DRUG-IV-1", "注射用头孢曲松钠",
                "1g", 3, "支", "2g", "qd", 3, new BigDecimal("30.00"),
                new BigDecimal("90.00"), null, null, null, null);
    }

    private PivasOrder order(String status, String preparedBy, long version) {
        return new PivasOrder(orderId, "PV20261104000001", rxId, "RX20261104000301",
                patientId, "P20261104000301", null, "V20261104000301", null,
                PivasOrder.PRIORITY_NORMAL, status, "0.9% 氯化钠注射液", 100,
                "注射用头孢曲松钠×3支", "B1 08:00", null, preparedBy,
                preparedBy == null ? null : Instant.now(),
                PivasOrder.CHECKED.equals(status) ? "pharm02" : null,
                PivasOrder.CHECKED.equals(status) ? Instant.now() : null,
                PivasOrder.CHECKED.equals(status) ? "BAG20261104000001" : null,
                PivasOrder.REJECTED.equals(status) ? "溶媒错误" : null,
                "pharm01", version, Instant.now());
    }

    private PivasItem pivasItem() {
        return new PivasItem(itemId, orderId, 1, drugId, "DRUG-IV-1", "注射用头孢曲松钠",
                "1g", 3, "支", "2g", "qd");
    }

    private DrugBatch batch(String batchNo, int quantity, int daysToExpire) {
        return new DrugBatch(UUID.randomUUID(), drugId, "DRUG-IV-1", "注射用头孢曲松钠", batchNo,
                LocalDate.now().plusDays(daysToExpire), quantity, "国药", 0L);
    }

    private void stubView() {
        when(repo.findPivasOrder(orderId)).thenReturn(Optional.of(order(PivasOrder.PENDING, null, 0L)));
        when(repo.listPivasItems(orderId)).thenReturn(List.of(pivasItem()));
        when(repo.listPivasItemBatches(orderId)).thenReturn(List.of());
    }

    // ---------------------------------------------------------------- 建单

    @Test
    @DisplayName("建单：非已审核处方 → 422")
    void createRejectsUnreviewedPrescription() {
        when(repo.lockPrescription(rxId)).thenReturn(Optional.of(rx(Prescription.PENDING_REVIEW)));

        assertCode(() -> service.create(new CreatePivasOrderReq(rxId, null, null, null, null, null),
                "pharm01"), ErrorCode.PIVAS_PRESCRIPTION_INVALID);
        verify(repo, never()).insertPivasOrder(any());
    }

    @Test
    @DisplayName("建单：同一处方重复建单 → 409")
    void createRejectsDuplicateOrder() {
        when(repo.lockPrescription(rxId)).thenReturn(Optional.of(rx(Prescription.APPROVED)));
        when(repo.findPivasOrderByPrescription(rxId))
                .thenReturn(Optional.of(order(PivasOrder.PENDING, null, 0L)));

        assertCode(() -> service.create(new CreatePivasOrderReq(rxId, null, null, null, null, null),
                "pharm01"), ErrorCode.PIVAS_ORDER_EXISTS);
        verify(repo, never()).insertPivasOrder(any());
    }

    @Test
    @DisplayName("G57 出口互斥：处方已窗口发药 → 不能再进静配中心")
    void createRejectsDispensedPrescription() {
        when(repo.lockPrescription(rxId)).thenReturn(Optional.of(rx(Prescription.APPROVED)));
        when(repo.findPivasOrderByPrescription(rxId)).thenReturn(Optional.empty());
        when(repo.findDispenseByPrescription(rxId)).thenReturn(Optional.of(
                new com.medassist.pharmacy.domain.PharmacyModel.Dispense(UUID.randomUUID(),
                        "DP20261104000001", rxId, "pharm01", null,
                        com.medassist.pharmacy.domain.PharmacyModel.Dispense.DISPENSED,
                        new java.math.BigDecimal("10.00"), java.time.Instant.now(), 0L,
                        com.medassist.pharmacy.domain.PharmacyModel.Dispense.BILLING_NONE)));

        assertCode(() -> service.create(new CreatePivasOrderReq(rxId, null, null, null, null, null),
                "pharm01"), ErrorCode.PIVAS_PRESCRIPTION_INVALID);
        verify(repo, never()).insertPivasOrder(any());
    }

    @Test
    @DisplayName("建单：优先级非法 → 422")
    void createRejectsInvalidPriority() {
        when(repo.lockPrescription(rxId)).thenReturn(Optional.of(rx(Prescription.APPROVED)));
        when(repo.findPivasOrderByPrescription(rxId)).thenReturn(Optional.empty());
        when(repo.listPrescriptionItems(rxId)).thenReturn(List.of(rxItem()));

        assertCode(() -> service.create(new CreatePivasOrderReq(rxId, "NOW", null, null, null, null),
                "pharm01"), ErrorCode.VALIDATION_FAILED);
        verify(repo, never()).insertPivasOrder(any());
    }

    @Test
    @DisplayName("建单：快照处方明细并写审计")
    void createSnapshotsItems() {
        when(repo.lockPrescription(rxId)).thenReturn(Optional.of(rx(Prescription.APPROVED)));
        when(repo.findPivasOrderByPrescription(rxId)).thenReturn(Optional.empty());
        when(repo.listPrescriptionItems(rxId)).thenReturn(List.of(rxItem()));
        when(repo.nextPivasOrderNo()).thenReturn("PV20261104000001");
        stubView();

        PivasView view = service.create(new CreatePivasOrderReq(rxId, "urgent", "0.9% 氯化钠注射液",
                100, "B1 08:00", null), "pharm01");

        ArgumentCaptor<PivasOrder> captor = ArgumentCaptor.forClass(PivasOrder.class);
        verify(repo).insertPivasOrder(captor.capture());
        assertThat(captor.getValue().priority()).isEqualTo(PivasOrder.PRIORITY_URGENT);
        assertThat(captor.getValue().drugSummary()).isEqualTo("注射用头孢曲松钠×3支");
        assertThat(captor.getValue().status()).isEqualTo(PivasOrder.PENDING);
        verify(repo).insertPivasItem(any(PivasItem.class));
        verify(repo).logChange(eq("pharm01"), eq("PIVAS_CREATE"), eq("PIVAS_ORDER"),
                any(String.class), any(String.class));
        assertThat(view.order().status()).isEqualTo(PivasOrder.PENDING);
    }

    // ---------------------------------------------------------------- 配置

    @Test
    @DisplayName("配置：非待配置状态 → 409")
    void prepareRejectsWrongStatus() {
        when(repo.lockPivasOrder(orderId))
                .thenReturn(Optional.of(order(PivasOrder.PREPARED, "pharm01", 1L)));

        assertCode(() -> service.prepare(orderId, "pharm01"), ErrorCode.PIVAS_STATUS_INVALID);
        verify(repo, never()).deductBatch(any(), any(Integer.class));
    }

    @Test
    @DisplayName("配置：FEFO 跨批次扣减并记录耗用")
    void prepareDeductsFefoBatches() {
        DrugBatch near = batch("B-NEAR", 2, 30);
        DrugBatch far = batch("B-FAR", 10, 300);
        when(repo.lockPivasOrder(orderId))
                .thenReturn(Optional.of(order(PivasOrder.PENDING, null, 0L)));
        when(repo.listPivasItems(orderId)).thenReturn(List.of(pivasItem()));
        when(repo.listAvailableBatches(eq(drugId), any(LocalDate.class)))
                .thenReturn(List.of(near, far));
        when(repo.deductBatch(any(), any(Integer.class))).thenReturn(true);
        when(repo.markPivasPrepared(eq(orderId), eq(0L), eq("pharm01"))).thenReturn(true);
        stubView();

        service.prepare(orderId, "pharm01");

        verify(repo).deductBatch(near.id(), 2);
        verify(repo).deductBatch(far.id(), 1);
        verify(repo, org.mockito.Mockito.times(2)).insertPivasItemBatch(any(PivasItemBatch.class));
        verify(repo).logChange(eq("pharm01"), eq("PIVAS_PREPARE"), eq("PIVAS_ORDER"),
                eq(orderId.toString()), any(String.class));
    }

    @Test
    @DisplayName("配置：库存不足 → 409，不推进状态")
    void prepareRejectsInsufficientStock() {
        DrugBatch only = batch("B-1", 1, 30);
        when(repo.lockPivasOrder(orderId))
                .thenReturn(Optional.of(order(PivasOrder.PENDING, null, 0L)));
        when(repo.listPivasItems(orderId)).thenReturn(List.of(pivasItem()));
        when(repo.listAvailableBatches(eq(drugId), any(LocalDate.class)))
                .thenReturn(List.of(only));
        when(repo.deductBatch(any(), any(Integer.class))).thenReturn(true);

        assertCode(() -> service.prepare(orderId, "pharm01"), ErrorCode.PIVAS_STOCK_INSUFFICIENT);
        verify(repo, never()).markPivasPrepared(any(), anyLong(), any());
    }

    // ---------------------------------------------------------------- 复核

    @Test
    @DisplayName("复核：非已配置状态 → 409")
    void checkRejectsWrongStatus() {
        when(repo.lockPivasOrder(orderId))
                .thenReturn(Optional.of(order(PivasOrder.PENDING, null, 0L)));

        assertCode(() -> service.check(orderId, new CheckPivasReq(true, null), "pharm02"),
                ErrorCode.PIVAS_STATUS_INVALID);
    }

    @Test
    @DisplayName("复核：复核人=配置人 → 403")
    void checkRejectsSelfCheck() {
        when(repo.lockPivasOrder(orderId))
                .thenReturn(Optional.of(order(PivasOrder.PREPARED, "pharm01", 1L)));

        assertCode(() -> service.check(orderId, new CheckPivasReq(true, null), "pharm01"),
                ErrorCode.PIVAS_SELF_CHECK);
        verify(repo, never()).markPivasChecked(any(), anyLong(), any(), any());
    }

    @Test
    @DisplayName("复核通过：生成成品标签号并写审计")
    void checkIssuesBagNo() {
        when(repo.lockPivasOrder(orderId))
                .thenReturn(Optional.of(order(PivasOrder.PREPARED, "pharm01", 1L)));
        when(repo.nextPivasBagNo()).thenReturn("BAG20261104000001");
        when(repo.markPivasChecked(eq(orderId), eq(1L), eq("pharm02"),
                eq("BAG20261104000001"))).thenReturn(true);
        when(repo.findPivasOrder(orderId))
                .thenReturn(Optional.of(order(PivasOrder.CHECKED, "pharm01", 2L)));
        when(repo.listPivasItems(orderId)).thenReturn(List.of(pivasItem()));
        when(repo.listPivasItemBatches(orderId)).thenReturn(List.of());

        PivasView view = service.check(orderId, new CheckPivasReq(true, "外观合格"), "pharm02");

        assertThat(view.order().status()).isEqualTo(PivasOrder.CHECKED);
        verify(repo).markPivasChecked(orderId, 1L, "pharm02", "BAG20261104000001");
        verify(repo).logChange(eq("pharm02"), eq("PIVAS_CHECK"), eq("PIVAS_ORDER"),
                eq(orderId.toString()), any(String.class));
    }

    @Test
    @DisplayName("复核不通过：必须写原因")
    void checkRejectRequiresReason() {
        when(repo.lockPivasOrder(orderId))
                .thenReturn(Optional.of(order(PivasOrder.PREPARED, "pharm01", 1L)));

        assertCode(() -> service.check(orderId, new CheckPivasReq(false, "  "), "pharm02"),
                ErrorCode.PIVAS_REJECT_REASON_REQUIRED);
        verify(repo, never()).markPivasRejected(any(), anyLong(), any());
    }

    @Test
    @DisplayName("复核不通过：原路回补批次并落 REJECTED")
    void checkRejectRestoresBatches() {
        UUID batchId = UUID.randomUUID();
        PivasItemBatch allocation = new PivasItemBatch(UUID.randomUUID(), itemId, drugId,
                batchId, "B-1", LocalDate.now().plusDays(100), 3, Instant.now());
        when(repo.lockPivasOrder(orderId))
                .thenReturn(Optional.of(order(PivasOrder.PREPARED, "pharm01", 1L)));
        when(repo.listPivasItemBatches(orderId)).thenReturn(List.of(allocation));
        when(repo.addBatchQuantity(batchId, 3)).thenReturn(true);
        when(repo.markPivasRejected(eq(orderId), eq(1L), eq("溶媒错误"))).thenReturn(true);
        when(repo.findPivasOrder(orderId))
                .thenReturn(Optional.of(order(PivasOrder.REJECTED, "pharm01", 2L)));
        when(repo.listPivasItems(orderId)).thenReturn(List.of(pivasItem()));
        when(repo.listPivasItemBatches(orderId)).thenReturn(List.of(allocation));

        PivasView view = service.check(orderId, new CheckPivasReq(false, "溶媒错误"), "pharm02");

        assertThat(view.order().status()).isEqualTo(PivasOrder.REJECTED);
        verify(repo).addBatchQuantity(batchId, 3);
        verify(repo).markPivasRejected(orderId, 1L, "溶媒错误");
    }

    // ---------------------------------------------------------------- 驳回 / 查询

    @Test
    @DisplayName("驳回待配置单：必须写原因")
    void rejectRequiresReason() {
        when(repo.lockPivasOrder(orderId))
                .thenReturn(Optional.of(order(PivasOrder.PENDING, null, 0L)));

        assertCode(() -> service.reject(orderId, new RejectPivasReq(" "), "pharm01"),
                ErrorCode.PIVAS_REJECT_REASON_REQUIRED);
    }

    @Test
    @DisplayName("驳回待配置单：CAS 成功并写审计")
    void rejectPendingOrder() {
        when(repo.lockPivasOrder(orderId))
                .thenReturn(Optional.of(order(PivasOrder.PENDING, null, 0L)));
        when(repo.markPivasRejected(orderId, 0L, "处方已停用")).thenReturn(true);
        when(repo.findPivasOrder(orderId))
                .thenReturn(Optional.of(order(PivasOrder.REJECTED, null, 1L)));
        when(repo.listPivasItems(orderId)).thenReturn(List.of(pivasItem()));
        when(repo.listPivasItemBatches(orderId)).thenReturn(List.of());

        service.reject(orderId, new RejectPivasReq("处方已停用"), "pharm01");

        verify(repo).markPivasRejected(orderId, 0L, "处方已停用");
        verify(repo).logChange(eq("pharm01"), eq("PIVAS_REJECT"), eq("PIVAS_ORDER"),
                eq(orderId.toString()), any(String.class));
    }

    @Test
    @DisplayName("成品追溯：标签号为空或不存在 → 404")
    void traceRejectsMissingBag() {
        assertCode(() -> service.trace(" "), ErrorCode.PIVAS_ORDER_NOT_FOUND);
        when(repo.findPivasOrderByBagNo("BAG-X")).thenReturn(Optional.empty());
        assertCode(() -> service.trace("BAG-X"), ErrorCode.PIVAS_ORDER_NOT_FOUND);
    }

    @Test
    @DisplayName("成品追溯：按标签号返回批次耗用")
    void traceReturnsBatches() {
        PivasItemBatch allocation = new PivasItemBatch(UUID.randomUUID(), itemId, drugId,
                UUID.randomUUID(), "B-1", LocalDate.now().plusDays(100), 3, Instant.now());
        when(repo.findPivasOrderByBagNo("BAG20261104000001"))
                .thenReturn(Optional.of(order(PivasOrder.CHECKED, "pharm01", 2L)));
        when(repo.listPivasItems(orderId)).thenReturn(List.of(pivasItem()));
        when(repo.listPivasItemBatches(orderId)).thenReturn(List.of(allocation));

        PivasView view = service.trace(" BAG20261104000001 ");

        assertThat(view.order().bagNo()).isEqualTo("BAG20261104000001");
        assertThat(view.batches()).hasSize(1);
    }

    @Test
    @DisplayName("看板计数：按状态归并")
    void summaryAggregatesStatusCounts() {
        when(repo.pivasStatusCounts()).thenReturn(List.of(
                new PivasStatusCount(PivasOrder.PENDING, 2),
                new PivasStatusCount(PivasOrder.PREPARED, 1),
                new PivasStatusCount(PivasOrder.CHECKED, 5),
                new PivasStatusCount(PivasOrder.REJECTED, 1)));

        PivasSummary summary = service.summary();

        assertThat(summary.pending()).isEqualTo(2);
        assertThat(summary.prepared()).isEqualTo(1);
        assertThat(summary.checked()).isEqualTo(5);
        assertThat(summary.rejected()).isEqualTo(1);
        assertThat(summary.total()).isEqualTo(9);
    }

    @Test
    @DisplayName("列表：非法状态 422，limit 上限 200")
    void listNormalizesParams() {
        assertCode(() -> service.list("BAD", 10), ErrorCode.VALIDATION_FAILED);
        service.list(" pending ", 9999);
        verify(repo).listPivasOrders("PENDING", 200);
    }
}
