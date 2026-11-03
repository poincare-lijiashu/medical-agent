package com.medassist.pharmacy.service;

import com.medassist.common.api.ErrorCode;
import com.medassist.common.exception.BusinessException;
import com.medassist.pharmacy.domain.PharmacyModel.Drug;
import com.medassist.pharmacy.domain.PharmacyModel.DrugBatch;
import com.medassist.pharmacy.domain.PharmacyModel.GoodsReceipt;
import com.medassist.pharmacy.domain.PharmacyModel.PurchaseOrder;
import com.medassist.pharmacy.domain.PharmacyModel.PurchaseOrderItem;
import com.medassist.pharmacy.domain.PharmacyModel.Supplier;
import com.medassist.pharmacy.repo.PharmacyRepository;
import com.medassist.pharmacy.repo.PurchaseRepository;
import com.medassist.pharmacy.web.PurchaseDtos.CancelOrderReq;
import com.medassist.pharmacy.web.PurchaseDtos.CreatePurchaseOrderReq;
import com.medassist.pharmacy.web.PurchaseDtos.CreateReceiptReq;
import com.medassist.pharmacy.web.PurchaseDtos.GoodsReceiptView;
import com.medassist.pharmacy.web.PurchaseDtos.PurchaseOrderLine;
import com.medassist.pharmacy.web.PurchaseDtos.PurchaseOrderView;
import com.medassist.pharmacy.web.PurchaseDtos.ReceiptLine;
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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 采购与验收规则（G2）。
 *
 * <p>锁住四条不能退让的底线：
 * 超量验收、过期批次验收、重复验收幂等、供应商资质。
 */
class ProcurementServiceTest {

    private PurchaseRepository purchaseRepo;
    private PharmacyRepository pharmacyRepo;
    private ProcurementService service;

    private final UUID supplierId = UUID.randomUUID();
    private final UUID drugId = UUID.randomUUID();
    private final UUID orderId = UUID.randomUUID();
    private final UUID itemId = UUID.randomUUID();

    private final Supplier activeSupplier = new Supplier(supplierId, "SUP-01", "国药控股",
            "张经理", "13800000000", "LIC-001", LocalDate.now().plusDays(365),
            Supplier.ACTIVE, null, 0L, Instant.now());

    private final Drug drug = new Drug(drugId, "DRUG-AMOX-025", "阿莫西林胶囊", null, "0.25g*24粒",
            "CAPSULE", "盒", "粒", 24, new BigDecimal("12.50"), null, "A", 1, false, true, 0L,
            null, false, false);

    @BeforeEach
    void setUp() {
        purchaseRepo = mock(PurchaseRepository.class);
        pharmacyRepo = mock(PharmacyRepository.class);
        SupplierService supplierService = new SupplierService(purchaseRepo, JsonMapper.builder().build());
        service = new ProcurementService(purchaseRepo, pharmacyRepo, supplierService,
                JsonMapper.builder().build());
        when(purchaseRepo.findSupplier(supplierId)).thenReturn(Optional.of(activeSupplier));
        when(purchaseRepo.nextOrderNo()).thenReturn("PO20261103000001");
        when(purchaseRepo.nextReceiptNo()).thenReturn("GR20261103000001");
        when(pharmacyRepo.findDrug(drugId)).thenReturn(Optional.of(drug));
    }

    // ---------------------------------------------------------------- 建单

    @Test
    @DisplayName("建单：单价取药品字典快照，总额由服务端计算（客户端不传价）")
    void createOrderSnapshotsPrice() {
        PurchaseOrderView view = service.createOrder(new CreatePurchaseOrderReq(supplierId, null,
                null, List.of(new PurchaseOrderLine(drugId, 3))), "pharm01");

        assertThat(view.order().status()).isEqualTo(PurchaseOrder.DRAFT);
        assertThat(view.order().totalAmount()).isEqualByComparingTo("37.50");
        assertThat(view.items()).hasSize(1);
        assertThat(view.items().get(0).unitPrice()).isEqualByComparingTo("12.50");
        assertThat(view.items().get(0).receivedQuantity()).isZero();
        verify(purchaseRepo).insertOrder(any());
        verify(purchaseRepo).insertOrderItem(any());
    }

    @Test
    @DisplayName("建单：停用供应商不能新开采购单")
    void createOrderRejectsSuspendedSupplier() {
        when(purchaseRepo.findSupplier(supplierId)).thenReturn(Optional.of(new Supplier(
                supplierId, "SUP-01", "国药控股", null, null, "LIC-001",
                LocalDate.now().plusDays(30), Supplier.SUSPENDED, null, 1L, Instant.now())));

        assertThatThrownBy(() -> service.createOrder(
                new CreatePurchaseOrderReq(supplierId, null, null,
                        List.of(new PurchaseOrderLine(drugId, 1))), "pharm01"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.SUPPLIER_INACTIVE);
        verify(purchaseRepo, never()).insertOrder(any());
    }

    @Test
    @DisplayName("建单：许可证过期的供应商不能新开采购单（GSP 飞检缺陷项）")
    void createOrderRejectsExpiredLicense() {
        when(purchaseRepo.findSupplier(supplierId)).thenReturn(Optional.of(new Supplier(
                supplierId, "SUP-01", "国药控股", null, null, "LIC-001",
                LocalDate.now().minusDays(1), Supplier.ACTIVE, null, 1L, Instant.now())));

        assertThatThrownBy(() -> service.createOrder(
                new CreatePurchaseOrderReq(supplierId, null, null,
                        List.of(new PurchaseOrderLine(drugId, 1))), "pharm01"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.SUPPLIER_LICENSE_EXPIRED);
    }

    @Test
    @DisplayName("建单：同一药品重复出现时拒绝（数据库唯一键的友好前置校验）")
    void createOrderRejectsDuplicateDrug() {
        assertThatThrownBy(() -> service.createOrder(
                new CreatePurchaseOrderReq(supplierId, null, null,
                        List.of(new PurchaseOrderLine(drugId, 1), new PurchaseOrderLine(drugId, 2))),
                "pharm01"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
    }

    // ---------------------------------------------------------------- 状态机

    @Test
    @DisplayName("提交：非草稿状态拒绝（已提交的单不能重复提交）")
    void submitRejectsNonDraft() {
        when(purchaseRepo.lockOrder(orderId)).thenReturn(Optional.of(order(PurchaseOrder.SUBMITTED, 2L)));

        assertThatThrownBy(() -> service.submit(orderId, 2L, "pharm01"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.PURCHASE_ORDER_STATUS_INVALID);
        verify(purchaseRepo, never()).submitOrder(any(), anyString(), anyLong());
    }

    @Test
    @DisplayName("取消：已到货（RECEIVED）的采购单不能取消，必须走退货/报损")
    void cancelRejectsReceivedOrder() {
        when(purchaseRepo.lockOrder(orderId)).thenReturn(Optional.of(order(PurchaseOrder.RECEIVED, 9L)));

        assertThatThrownBy(() -> service.cancel(orderId, new CancelOrderReq("重复下单", 9L), "pharm01"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.PURCHASE_ORDER_STATUS_INVALID);
    }

    @Test
    @DisplayName("提交：版本号过期时提示刷新（CAS 未命中）")
    void submitRejectsStaleVersion() {
        when(purchaseRepo.lockOrder(orderId)).thenReturn(Optional.of(order(PurchaseOrder.DRAFT, 3L)));
        when(purchaseRepo.submitOrder(eq(orderId), eq("pharm01"), eq(2L))).thenReturn(false);

        assertThatThrownBy(() -> service.submit(orderId, 2L, "pharm01"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.CONFLICT);
    }

    // ---------------------------------------------------------------- 验收

    @Test
    @DisplayName("验收：超过未到数量直接拒绝，且不写任何入库记录")
    void receiveRejectsOverQuantity() {
        when(purchaseRepo.lockOrder(orderId)).thenReturn(Optional.of(order(PurchaseOrder.SUBMITTED, 2L)));
        when(purchaseRepo.findReceiptByRequestId(orderId, "req-1")).thenReturn(Optional.empty());
        when(purchaseRepo.findOrderItem(itemId)).thenReturn(Optional.of(item(10, 9)));

        assertThatThrownBy(() -> service.receive(orderId,
                receipt("req-1", itemId, "B-1", LocalDate.now().plusDays(90), 5), "pharm01"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.PURCHASE_RECEIPT_EXCEEDS);
        verify(purchaseRepo, never()).insertReceipt(any());
    }

    @Test
    @DisplayName("验收：过期批次拒绝入库（不得把过期药记进库存）")
    void receiveRejectsExpiredBatch() {
        when(purchaseRepo.lockOrder(orderId)).thenReturn(Optional.of(order(PurchaseOrder.SUBMITTED, 2L)));
        when(purchaseRepo.findReceiptByRequestId(orderId, "req-1")).thenReturn(Optional.empty());
        when(purchaseRepo.findOrderItem(itemId)).thenReturn(Optional.of(item(10, 0)));

        assertThatThrownBy(() -> service.receive(orderId,
                receipt("req-1", itemId, "B-OLD", LocalDate.now().minusDays(1), 2), "pharm01"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.DRUG_BATCH_EXPIRED);
    }

    @Test
    @DisplayName("验收：同一 requestId 重复提交只入库一次（幂等）")
    void receiveIsIdempotent() {
        GoodsReceipt existing = new GoodsReceipt(UUID.randomUUID(), "GR20261103000009", orderId,
                supplierId, "req-1", null, null, null, "pharm01", null,
                new BigDecimal("25.00"), Instant.now());
        when(purchaseRepo.lockOrder(orderId)).thenReturn(Optional.of(order(PurchaseOrder.PARTIAL, 3L)));
        when(purchaseRepo.findReceiptByRequestId(orderId, "req-1")).thenReturn(Optional.of(existing));

        GoodsReceiptView view = service.receive(orderId,
                receipt("req-1", itemId, "B-1", LocalDate.now().plusDays(90), 2), "pharm01");

        assertThat(view.receipt().receiptNo()).isEqualTo("GR20261103000009");
        verify(purchaseRepo, never()).insertReceipt(any());
        verify(pharmacyRepo, never()).insertBatch(any(), any(), anyString(), any(), anyInt(), any());
    }

    @Test
    @DisplayName("验收：明细不属于该采购单时 404（防止跨单改库存）")
    void receiveRejectsForeignItem() {
        when(purchaseRepo.lockOrder(orderId)).thenReturn(Optional.of(order(PurchaseOrder.SUBMITTED, 2L)));
        when(purchaseRepo.findReceiptByRequestId(orderId, "req-1")).thenReturn(Optional.empty());
        when(purchaseRepo.findOrderItem(itemId)).thenReturn(Optional.of(new PurchaseOrderItem(
                itemId, UUID.randomUUID(), drugId, "D1", "药A", "1g", "盒", 10, 0,
                BigDecimal.ONE, BigDecimal.TEN)));

        assertThatThrownBy(() -> service.receive(orderId,
                receipt("req-1", itemId, "B-1", LocalDate.now().plusDays(90), 1), "pharm01"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.PURCHASE_ITEM_NOT_FOUND);
    }

    @Test
    @DisplayName("验收：部分到货 → PARTIAL；全部到齐 → RECEIVED")
    void receiveUpdatesOrderProgress() {
        when(purchaseRepo.lockOrder(orderId))
                .thenReturn(Optional.of(order(PurchaseOrder.SUBMITTED, 2L)))
                .thenReturn(Optional.of(order(PurchaseOrder.PARTIAL, 3L)));
        when(purchaseRepo.findReceiptByRequestId(eq(orderId), anyString())).thenReturn(Optional.empty());
        when(purchaseRepo.findOrderItem(itemId))
                .thenReturn(Optional.of(item(10, 0)))
                .thenReturn(Optional.of(item(10, 4)));
        when(purchaseRepo.addReceivedQuantity(itemId, 4)).thenReturn(true);
        when(purchaseRepo.addReceivedQuantity(itemId, 6)).thenReturn(true);
        // 第一次验收后只到 4 件；第二次到齐 10 件
        when(purchaseRepo.listOrderItems(orderId))
                .thenReturn(List.of(item(10, 4)))
                .thenReturn(List.of(item(10, 10)));

        service.receive(orderId,
                receipt("req-1", itemId, "B-1", LocalDate.now().plusDays(90), 4), "pharm01");
        service.receive(orderId,
                receipt("req-2", itemId, "B-2", LocalDate.now().plusDays(90), 6), "pharm01");

        verify(purchaseRepo).updateReceiptStatus(orderId, PurchaseOrder.PARTIAL);
        verify(purchaseRepo).updateReceiptStatus(orderId, PurchaseOrder.RECEIVED);
    }

    @Test
    @DisplayName("验收：同批号不同效期拒绝（串批会污染 FEFO 取药顺序）")
    void receiveRejectsBatchExpiryMismatch() {
        when(purchaseRepo.lockOrder(orderId)).thenReturn(Optional.of(order(PurchaseOrder.SUBMITTED, 2L)));
        when(purchaseRepo.findReceiptByRequestId(orderId, "req-1")).thenReturn(Optional.empty());
        when(purchaseRepo.findOrderItem(itemId)).thenReturn(Optional.of(item(10, 0)));
        when(pharmacyRepo.findBatchByDrugAndBatchNo(drugId, "B-1")).thenReturn(Optional.of(
                new DrugBatch(UUID.randomUUID(), drugId, drug.drugCode(), drug.name(), "B-1",
                        LocalDate.now().plusDays(30), 5, "国药控股", 0L)));

        assertThatThrownBy(() -> service.receive(orderId,
                receipt("req-1", itemId, "B-1", LocalDate.now().plusDays(90), 2), "pharm01"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
        verify(pharmacyRepo, never()).addBatchQuantity(any(), anyInt());
    }

    @Test
    @DisplayName("验收：已存在的同批号按补录累加，不新建批次")
    void receiveAddsToExistingBatch() {
        UUID batchId = UUID.randomUUID();
        when(purchaseRepo.lockOrder(orderId)).thenReturn(Optional.of(order(PurchaseOrder.SUBMITTED, 2L)));
        when(purchaseRepo.findReceiptByRequestId(orderId, "req-1")).thenReturn(Optional.empty());
        when(purchaseRepo.findOrderItem(itemId)).thenReturn(Optional.of(item(10, 0)));
        when(pharmacyRepo.findBatchByDrugAndBatchNo(drugId, "B-1")).thenReturn(Optional.of(
                new DrugBatch(batchId, drugId, drug.drugCode(), drug.name(), "B-1",
                        LocalDate.now().plusDays(90), 5, "国药控股", 0L)));
        when(purchaseRepo.addReceivedQuantity(itemId, 2)).thenReturn(true);
        when(purchaseRepo.listOrderItems(orderId)).thenReturn(List.of(item(10, 2)));

        service.receive(orderId,
                receipt("req-1", itemId, "B-1", LocalDate.now().plusDays(90), 2), "pharm01");

        verify(pharmacyRepo).addBatchQuantity(batchId, 2);
        verify(pharmacyRepo, never()).insertBatch(any(), any(), anyString(), any(), anyInt(), any());
    }

    // ---------------------------------------------------------------- 夹具

    private PurchaseOrder order(String status, long version) {
        return new PurchaseOrder(orderId, "PO20261103000001", supplierId, status, null,
                new BigDecimal("125.00"), null, "pharm01", null, null, null, null, null,
                version, Instant.now());
    }

    private PurchaseOrderItem item(int quantity, int received) {
        return new PurchaseOrderItem(itemId, orderId, drugId, drug.drugCode(), drug.name(),
                drug.spec(), drug.unit(), quantity, received, new BigDecimal("12.50"),
                new BigDecimal("12.50").multiply(BigDecimal.valueOf(quantity)));
    }

    private CreateReceiptReq receipt(String requestId, UUID poItemId, String batchNo,
                                     LocalDate expireDate, int quantity) {
        return new CreateReceiptReq(requestId, null, null, null, null,
                List.of(new ReceiptLine(poItemId, batchNo, expireDate, quantity)));
    }
}
