package com.medassist.pharmacy.web;

import com.medassist.common.api.ErrorCode;
import com.medassist.common.exception.BusinessException;
import com.medassist.pharmacy.service.DrugTransferService;
import com.medassist.pharmacy.web.DrugTransferDtos.CreateDrugTransferReq;
import com.medassist.pharmacy.web.DrugTransferDtos.TransferLine;
import com.medassist.pharmacy.web.DrugTransferDtos.TransferReasonReq;
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
 * 调拨接口权限矩阵：药房侧（建单/发出/撤回）与科室侧（接收/拒收）分权。
 */
class DrugTransferControllerPermissionTest {

    private DrugTransferService service;
    private DrugTransferController controller;

    private final UUID id = UUID.randomUUID();
    private final UUID deptId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = mock(DrugTransferService.class);
        controller = new DrugTransferController(service);
    }

    private void assertForbidden(Runnable call) {
        assertThatThrownBy(call::run)
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.FORBIDDEN);
    }

    @Test
    @DisplayName("查询：pharmacy:read 放行，调拨权限不能替代读权限")
    void queriesRequireRead() {
        assertForbidden(() -> controller.list(null, null, 10, "pharmacy:transfer:write"));
        controller.list("SUBMITTED", deptId, 10, "pharmacy:read");
        verify(service).list("SUBMITTED", deptId, 10);

        assertForbidden(() -> controller.detail(id, "pharmacy:transfer:accept"));
        controller.detail(id, "pharmacy:read");
        verify(service).detail(id);

        assertForbidden(() -> controller.deptStock(deptId, null, 10, "pharmacy:transfer:accept"));
        controller.deptStock(deptId, null, 10, "pharmacy:read");
        verify(service).deptStock(deptId, null, 10);
    }

    @Test
    @DisplayName("建单/发出/撤回：必须 pharmacy:transfer:write，接收权限不能代发")
    void writeActionsRequireWrite() {
        CreateDrugTransferReq req = new CreateDrugTransferReq(deptId, null,
                List.of(new TransferLine(UUID.randomUUID(), 1)));

        assertForbidden(() -> controller.create(req, "pharmacy:transfer:accept", "pharm01"));
        verify(service, never()).create(any(), any());
        controller.create(req, "pharmacy:transfer:write", "pharm01");
        verify(service).create(req, "pharm01");

        assertForbidden(() -> controller.dispatch(id, "pharmacy:transfer:accept", "pharm01"));
        verify(service, never()).dispatch(any(), any());
        controller.dispatch(id, "pharmacy:transfer:write", "pharm01");
        verify(service).dispatch(id, "pharm01");

        TransferReasonReq reason = new TransferReasonReq("重复建单");
        assertForbidden(() -> controller.cancel(id, reason, "pharmacy:transfer:accept", "pharm01"));
        controller.cancel(id, reason, "pharmacy:transfer:write", "pharm01");
        verify(service).cancel(id, reason, "pharm01");
    }

    @Test
    @DisplayName("接收/拒收：必须 pharmacy:transfer:accept，药房发出权限不能代收")
    void acceptActionsRequireAccept() {
        assertForbidden(() -> controller.accept(id, "pharmacy:transfer:write", "nurse01"));
        verify(service, never()).accept(any(), any());
        controller.accept(id, "pharmacy:transfer:accept", "nurse01");
        verify(service).accept(id, "nurse01");

        TransferReasonReq reason = new TransferReasonReq("药品破损");
        assertForbidden(() -> controller.reject(id, reason, "pharmacy:transfer:write", "nurse01"));
        verify(service, never()).reject(any(), any(), any());
        controller.reject(id, reason, "pharmacy:transfer:accept", "nurse01");
        verify(service).reject(id, reason, "nurse01");
    }

    @Test
    @DisplayName("权限头为空：403 且不触达服务层")
    void emptyPermissionsForbidden() {
        assertForbidden(() -> controller.detail(id, null));
        verify(service, never()).detail(any());
    }
}
