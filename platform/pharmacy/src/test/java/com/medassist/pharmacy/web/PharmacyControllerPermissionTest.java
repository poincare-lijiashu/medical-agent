package com.medassist.pharmacy.web;

import com.medassist.common.api.ErrorCode;
import com.medassist.common.exception.BusinessException;
import com.medassist.common.security.DataScope;
import com.medassist.pharmacy.service.DrugCatalogService;
import com.medassist.pharmacy.service.PharmacyBillingService;
import com.medassist.pharmacy.service.PrescriptionService;
import com.medassist.pharmacy.service.StockService;
import com.medassist.pharmacy.web.PharmacyDtos.CreateDrugReq;
import com.medassist.pharmacy.web.PharmacyDtos.CreatePrescriptionReq;
import com.medassist.pharmacy.web.PharmacyDtos.PrescriptionLine;
import com.medassist.pharmacy.web.PharmacyDtos.ReceiveStockReq;
import com.medassist.pharmacy.web.PharmacyDtos.ReviewReq;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * 药房接口权限矩阵（直接驱动 Controller，不启动 Spring 上下文）。
 *
 * <p>重点锁住"开方—审核—发药"三权分立：医师没有审核/发药权限，药师没有开方权限。
 */
class PharmacyControllerPermissionTest {

    private DrugCatalogService drugService;
    private StockService stockService;
    private PrescriptionService prescriptionService;

    private DrugController drugController;
    private StockController stockController;
    private PrescriptionController prescriptionController;
    private DispenseController dispenseController;

    private final UUID id = UUID.randomUUID();
    /** 本类只测权限点；PHI 掩码由各服务的 PhiMask 单测覆盖。 */
    private final DataScope scope = DataScope.all("pharm01", true);

    @BeforeEach
    void setUp() {
        drugService = mock(DrugCatalogService.class);
        stockService = mock(StockService.class);
        prescriptionService = mock(PrescriptionService.class);
        drugController = new DrugController(drugService);
        stockController = new StockController(stockService);
        prescriptionController = new PrescriptionController(prescriptionService);
        dispenseController = new DispenseController(prescriptionService,
                mock(PharmacyBillingService.class));
    }

    @Test
    @DisplayName("药品字典维护：只有 pharmacy:drug:manage 能建药")
    void drugCreateRequiresManage() {
        var req = new CreateDrugReq("D1", "药A", null, "1g", "TABLET", "盒", null, null,
                java.math.BigDecimal.ONE, null, null, null, null, null, null, null);

        assertThatThrownBy(() -> drugController.create(req, "pharmacy:read", "pharm01"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.FORBIDDEN);
        verify(drugService, never()).create(any(), anyString());
    }

    @Test
    @DisplayName("入库：需要 pharmacy:stock:manage")
    void stockRequiresManage() {
        var req = new ReceiveStockReq("B1", LocalDate.now().plusDays(30), 10, null);

        assertThatThrownBy(() -> stockController.receive(id, req, "pharmacy:read", "pharm01"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.FORBIDDEN);
        verify(stockService, never()).receive(any(), any(), anyString());
    }

    @Test
    @DisplayName("开方：医师权限 pharmacy:prescription:create 放行")
    void prescriptionCreateAllowedForDoctorPermission() {
        var req = new CreatePrescriptionReq(id, null, null, null, null,
                List.of(new PrescriptionLine("D1", 1, null, null, null, null, null, null)));

        prescriptionController.create(req, "pharmacy:prescription:create", "doctor01", scope);

        verify(prescriptionService).create(any(), anyString());
    }

    @Test
    @DisplayName("审核：医师不能自己审核自己的处方（缺 review 权限即 403）")
    void reviewRequiresReviewPermission() {
        var req = new ReviewReq(true, null);

        assertThatThrownBy(() -> prescriptionController.review(id, req,
                "pharmacy:prescription:create", "doctor01", scope))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.FORBIDDEN);
        verify(prescriptionService, never()).review(any(), anyBoolean(), any(), anyString());
    }

    @Test
    @DisplayName("发药：需要 pharmacy:dispense:execute")
    void dispenseRequiresExecutePermission() {
        assertThatThrownBy(() -> prescriptionController.dispense(id, null,
                "pharmacy:prescription:review", "pharm01"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.FORBIDDEN);
        verify(prescriptionService, never()).dispense(any(), any(), anyString());
    }

    @Test
    @DisplayName("退药：需要 pharmacy:dispense:return")
    void returnRequiresReturnPermission() {
        var req = new PharmacyDtos.ReturnReq(UUID.randomUUID(), 1, "停药");

        assertThatThrownBy(() -> dispenseController.returnDrug(id, req,
                "pharmacy:dispense:execute", "pharm01"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.FORBIDDEN);
        verify(prescriptionService, never()).returnDrug(any(), any(), anyString());
    }

    @Test
    @DisplayName("权限头为空：提示重新登录（403）")
    void emptyPermissionsHeader() {
        assertThatThrownBy(() -> prescriptionController.detail(id, null, scope))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.FORBIDDEN);
    }
}
