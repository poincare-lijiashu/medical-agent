package com.medassist.material.web;

import com.medassist.common.api.ErrorCode;
import com.medassist.common.exception.BusinessException;
import com.medassist.common.security.DataScope;
import com.medassist.material.service.ConsumableCatalogService;
import com.medassist.material.service.ConsumableIssueService;
import com.medassist.material.service.ConsumableStockService;
import com.medassist.material.service.ConsumableTraceService;
import com.medassist.material.web.MaterialDtos.CreateConsumableReq;
import com.medassist.material.web.MaterialDtos.CreateIssueReq;
import com.medassist.material.web.MaterialDtos.CreateTraceReq;
import com.medassist.material.web.MaterialDtos.IssueLine;
import com.medassist.material.web.MaterialDtos.ReceiveBatchReq;
import com.medassist.material.web.MaterialDtos.UpdatePriceReq;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * 物资耗材接口权限矩阵：目录/入库/出库/追溯读取四层分开授权。
 */
class MaterialControllerPermissionTest {

    private ConsumableCatalogService catalogService;
    private ConsumableStockService stockService;
    private ConsumableIssueService issueService;
    private ConsumableTraceService traceService;

    private MaterialCatalogController catalogController;
    private MaterialStockController stockController;
    private MaterialIssueController issueController;
    private MaterialTraceController traceController;

    private final UUID id = UUID.randomUUID();
    private final DataScope scope = DataScope.all("mat01", true);

    @BeforeEach
    void setUp() {
        catalogService = mock(ConsumableCatalogService.class);
        stockService = mock(ConsumableStockService.class);
        issueService = mock(ConsumableIssueService.class);
        traceService = mock(ConsumableTraceService.class);
        catalogController = new MaterialCatalogController(catalogService);
        stockController = new MaterialStockController(stockService);
        issueController = new MaterialIssueController(issueService);
        traceController = new MaterialTraceController(traceService);
    }

    @Test
    @DisplayName("建档：只有 material:catalog:manage 放行")
    void createRequiresCatalogManage() {
        var req = new CreateConsumableReq("C1", "注射器", "5ml", "支", "GENERAL",
                BigDecimal.ONE, null, null);

        assertThatThrownBy(() -> catalogController.create(req, "material:read", "mat01"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.FORBIDDEN);
        verify(catalogService, never()).create(any(), anyString());
    }

    @Test
    @DisplayName("调价：只有 material:catalog:manage 放行")
    void priceRequiresCatalogManage() {
        assertThatThrownBy(() -> catalogController.adjustPrice(id, new UpdatePriceReq(BigDecimal.ONE, 0L),
                "material:issue:write", "mat01"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.FORBIDDEN);
    }

    @Test
    @DisplayName("入库：需要 material:stock:manage")
    void receiveRequiresStockManage() {
        var req = new ReceiveBatchReq("B1", LocalDate.now().plusDays(30), 5, null, null);

        assertThatThrownBy(() -> stockController.receive(id, req, "material:read", "mat01"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.FORBIDDEN);
        verify(stockService, never()).receive(any(), any(), anyString());
    }

    @Test
    @DisplayName("出库：需要 material:issue:write")
    void issueRequiresIssueWrite() {
        var req = new CreateIssueReq(id, "手术室", "SURGERY", "req-1", null,
                List.of(new IssueLine(UUID.randomUUID(), 1)));

        assertThatThrownBy(() -> issueController.issue(req, "material:trace:write", "nurse01"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.FORBIDDEN);
        verify(issueService, never()).issue(any(), anyString());
    }

    @Test
    @DisplayName("追溯登记：需要 material:trace:write")
    void traceRegisterRequiresTraceWrite() {
        var req = new CreateTraceReq(UUID.randomUUID(), 1, UUID.randomUUID(), "P000001",
                null, null, null, null);

        assertThatThrownBy(() -> traceController.register(req, "material:issue:write", "nurse01", scope))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.FORBIDDEN);
        verify(traceService, never()).register(any(), anyString());
    }

    @Test
    @DisplayName("召回：需要 material:trace:read")
    void recallRequiresTraceRead() {
        assertThatThrownBy(() -> traceController.recall("B-1", "material:read", scope))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.FORBIDDEN);
        verify(traceService, never()).recall(anyString());
    }

    @Test
    @DisplayName("查询：material:read 放行")
    void listAllowsRead() {
        catalogController.list(null, null, null, null, "material:read");

        verify(catalogService).list(null, null, null, null);
    }
}
