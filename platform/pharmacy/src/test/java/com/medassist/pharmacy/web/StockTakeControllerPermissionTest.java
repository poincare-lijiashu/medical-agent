package com.medassist.pharmacy.web;

import com.medassist.common.api.ErrorCode;
import com.medassist.common.exception.BusinessException;
import com.medassist.pharmacy.service.StockTakeService;
import com.medassist.pharmacy.web.StockTakeDtos.CancelStockTakeReq;
import com.medassist.pharmacy.web.StockTakeDtos.CountItemReq;
import com.medassist.pharmacy.web.StockTakeDtos.CreateStockTakeReq;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * 盘点接口权限矩阵：建单/实盘（take）与审批（approve）分权，查询用只读权限。
 */
class StockTakeControllerPermissionTest {

    private StockTakeService service;
    private StockTakeController controller;

    private final UUID id = UUID.randomUUID();
    private final UUID itemId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = mock(StockTakeService.class);
        controller = new StockTakeController(service);
    }

    private void assertForbidden(Runnable call) {
        assertThatThrownBy(call::run)
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.FORBIDDEN);
    }

    @Test
    @DisplayName("查询：pharmacy:read 放行，盘点权限不能替代读权限")
    void listAndDetailRequireRead() {
        assertForbidden(() -> controller.list(null, 10, "pharmacy:stock:take"));
        controller.list("COUNTING", 10, "pharmacy:read");
        verify(service).list("COUNTING", 10);

        assertForbidden(() -> controller.detail(id, "pharmacy:stock:approve"));
        controller.detail(id, "pharmacy:read");
        verify(service).detail(id);
    }

    @Test
    @DisplayName("建单/实盘/提交/取消：必须 pharmacy:stock:take，审批权限不能代录入")
    void takeActionsRequireTake() {
        CreateStockTakeReq create = new CreateStockTakeReq(null, null);
        assertForbidden(() -> controller.create(create, "pharmacy:stock:approve", "pharm01"));
        verify(service, never()).create(any(), any());

        controller.create(create, "pharmacy:stock:take", "pharm01");
        verify(service).create(create, "pharm01");

        CountItemReq count = new CountItemReq(9, "盘亏", 1L);
        assertForbidden(() -> controller.count(id, itemId, count, "pharmacy:read", "pharm01"));
        verify(service, never()).count(any(), any(), any(), any());

        assertForbidden(() -> controller.submit(id, "pharmacy:read", "pharm01"));
        controller.submit(id, "pharmacy:stock:take", "pharm01");
        verify(service).submit(id, "pharm01");

        assertForbidden(() -> controller.cancel(id, new CancelStockTakeReq("误建"),
                "pharmacy:stock:approve", "pharm01"));
        controller.cancel(id, new CancelStockTakeReq("误建"), "pharmacy:stock:take", "pharm01");
        verify(service).cancel(any(), any(), any());
    }

    @Test
    @DisplayName("审批：必须 pharmacy:stock:approve，建单权限不能自审")
    void approveRequiresApprove() {
        assertForbidden(() -> controller.approve(id, "pharmacy:stock:take", "pharm01"));
        verify(service, never()).approve(any(), any());

        controller.approve(id, "pharmacy:stock:approve", "pharm02");
        verify(service).approve(id, "pharm02");
    }

    @Test
    @DisplayName("权限头为空：403 且不触达服务层")
    void emptyPermissionsForbidden() {
        assertForbidden(() -> controller.detail(id, null));
        verify(service, never()).detail(any());
    }
}
