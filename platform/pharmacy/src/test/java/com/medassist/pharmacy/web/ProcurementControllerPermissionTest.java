package com.medassist.pharmacy.web;

import com.medassist.common.api.ErrorCode;
import com.medassist.common.exception.BusinessException;
import com.medassist.pharmacy.service.ProcurementService;
import com.medassist.pharmacy.service.SupplierService;
import com.medassist.pharmacy.web.PurchaseDtos.CancelOrderReq;
import com.medassist.pharmacy.web.PurchaseDtos.CreatePurchaseOrderReq;
import com.medassist.pharmacy.web.PurchaseDtos.CreateReceiptReq;
import com.medassist.pharmacy.web.PurchaseDtos.CreateSupplierReq;
import com.medassist.pharmacy.web.PurchaseDtos.OrderVersionReq;
import com.medassist.pharmacy.web.PurchaseDtos.PurchaseOrderLine;
import com.medassist.pharmacy.web.PurchaseDtos.ReceiptLine;
import com.medassist.pharmacy.web.PurchaseDtos.SupplierStatusReq;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * 采购接口权限矩阵：建单、审批、验收三权分立。
 */
class ProcurementControllerPermissionTest {

    private ProcurementService procurementService;
    private SupplierService supplierService;
    private PurchaseController purchaseController;
    private SupplierController supplierController;

    private final UUID id = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        procurementService = mock(ProcurementService.class);
        supplierService = mock(SupplierService.class);
        purchaseController = new PurchaseController(procurementService);
        supplierController = new SupplierController(supplierService);
    }

    @Test
    @DisplayName("建单：只有 pharmacy:purchase:write 放行")
    void createRequiresWrite() {
        CreatePurchaseOrderReq req = new CreatePurchaseOrderReq(id, null, null,
                List.of(new PurchaseOrderLine(UUID.randomUUID(), 1)));

        assertThatThrownBy(() -> purchaseController.create(req, "pharmacy:purchase:read", "pharm01"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.FORBIDDEN);
        verify(procurementService, never()).createOrder(any(), anyString());
    }

    @Test
    @DisplayName("提交：只有 pharmacy:purchase:approve 放行（建单人不能自行审批）")
    void submitRequiresApprove() {
        assertThatThrownBy(() -> purchaseController.submit(id, new OrderVersionReq(1L),
                "pharmacy:purchase:write", "pharm01"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.FORBIDDEN);
        verify(procurementService, never()).submit(any(), anyLong(), anyString());
    }

    @Test
    @DisplayName("取消：需要 pharmacy:purchase:approve")
    void cancelRequiresApprove() {
        assertThatThrownBy(() -> purchaseController.cancel(id, new CancelOrderReq("重复下单", 1L),
                "pharmacy:purchase:read", "pharm01"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.FORBIDDEN);
        verify(procurementService, never()).cancel(any(), any(), anyString());
    }

    @Test
    @DisplayName("验收：只有 pharmacy:receipt:write 放行（直接改库存，风险最高）")
    void receiveRequiresReceiptPermission() {
        CreateReceiptReq req = new CreateReceiptReq("req-1", null, null, null, null,
                List.of(new ReceiptLine(UUID.randomUUID(), "B-1", LocalDate.now().plusDays(30), 1)));

        assertThatThrownBy(() -> purchaseController.receive(id, req,
                "pharmacy:purchase:approve", "pharm01"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.FORBIDDEN);
        verify(procurementService, never()).receive(any(), any(), anyString());
    }

    @Test
    @DisplayName("供应商维护：需要 pharmacy:supplier:manage")
    void supplierCreateRequiresManage() {
        CreateSupplierReq req = new CreateSupplierReq("SUP-01", "国药控股", null, null,
                "LIC-001", LocalDate.now().plusDays(365), null);

        assertThatThrownBy(() -> supplierController.create(req,
                "pharmacy:purchase:write", "pharm01"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.FORBIDDEN);
        verify(supplierService, never()).create(any(), anyString());
    }

    @Test
    @DisplayName("供应商启停：需要 pharmacy:supplier:manage")
    void supplierStatusRequiresManage() {
        assertThatThrownBy(() -> supplierController.updateStatus(id,
                new SupplierStatusReq("SUSPENDED", 1L), "pharmacy:read", "pharm01"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.FORBIDDEN);
        verify(supplierService, never()).updateStatus(any(), anyString(), anyLong(), anyString());
    }

    @Test
    @DisplayName("查询：pharmacy:purchase:read 放行")
    void listAllowsRead() {
        purchaseController.list(null, null, null, null, "pharmacy:purchase:read");

        verify(procurementService).list(null, null, null, null);
    }
}
