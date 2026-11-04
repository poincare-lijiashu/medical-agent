package com.medassist.pharmacy.web;

import com.medassist.common.api.ErrorCode;
import com.medassist.common.exception.BusinessException;
import com.medassist.pharmacy.service.StockLossService;
import com.medassist.pharmacy.web.StockLossDtos.CreateStockLossReq;
import com.medassist.pharmacy.web.StockLossDtos.LossLine;
import com.medassist.pharmacy.web.StockLossDtos.ReviewStockLossReq;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * 报损接口权限矩阵：提交与审批分权，查询用只读权限。
 */
class StockLossControllerPermissionTest {

    private StockLossService service;
    private StockLossController controller;

    private final UUID id = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = mock(StockLossService.class);
        controller = new StockLossController(service);
    }

    private void assertForbidden(Runnable call) {
        assertThatThrownBy(call::run)
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.FORBIDDEN);
    }

    @Test
    @DisplayName("查询：pharmacy:read 放行，报损权限不能替代读权限")
    void listAndDetailRequireRead() {
        assertForbidden(() -> controller.list(null, 10, "pharmacy:loss:write"));
        controller.list("SUBMITTED", 10, "pharmacy:read");
        verify(service).list("SUBMITTED", 10);

        assertForbidden(() -> controller.detail(id, "pharmacy:loss:approve"));
        controller.detail(id, "pharmacy:read");
        verify(service).detail(id);
    }

    @Test
    @DisplayName("提交：必须 pharmacy:loss:write，审批权限不能代提交")
    void createRequiresWrite() {
        CreateStockLossReq req = new CreateStockLossReq("BROKEN", null,
                List.of(new LossLine(UUID.randomUUID(), 1, null)));

        assertForbidden(() -> controller.create(req, "pharmacy:loss:approve", "pharm01"));
        verify(service, never()).create(any(), any());

        controller.create(req, "pharmacy:loss:write", "pharm01");
        verify(service).create(req, "pharm01");
    }

    @Test
    @DisplayName("审批：必须 pharmacy:loss:approve，提交权限不能自审")
    void reviewRequiresApprove() {
        ReviewStockLossReq req = new ReviewStockLossReq(true, null);

        assertForbidden(() -> controller.review(id, req, "pharmacy:loss:write", "pharm01"));
        verify(service, never()).review(any(), any(), any());

        controller.review(id, req, "pharmacy:loss:approve", "pharm02");
        verify(service).review(id, req, "pharm02");
    }

    @Test
    @DisplayName("草稿提交 / 自动草稿：必须 pharmacy:loss:write，读权限与审批权限都不够")
    void submitAndAutoDraftRequireWrite() {
        assertForbidden(() -> controller.submit(id, "pharmacy:read", "pharm01"));
        assertForbidden(() -> controller.submit(id, "pharmacy:loss:approve", "pharm01"));
        verify(service, never()).submit(any(), any());

        controller.submit(id, "pharmacy:loss:write", "pharm01");
        verify(service).submit(id, "pharm01");

        assertForbidden(() -> controller.autoDraft("pharmacy:read", "pharm01"));
        verify(service, never()).autoDraftExpired(any());

        controller.autoDraft("pharmacy:loss:write", "pharm01");
        verify(service).autoDraftExpired("pharm01");
    }

    @Test
    @DisplayName("权限头为空：403 且不触达服务层")
    void emptyPermissionsForbidden() {
        assertForbidden(() -> controller.detail(id, null));
        verify(service, never()).detail(any());
    }
}
