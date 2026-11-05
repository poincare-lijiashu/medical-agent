package com.medassist.tech.web;

import com.medassist.common.api.ErrorCode;
import com.medassist.common.exception.BusinessException;
import com.medassist.common.security.DataScope;
import com.medassist.tech.domain.TechModel.DeviceWorkOrder;
import com.medassist.tech.service.DeviceWorkOrderService;
import com.medassist.tech.web.DeviceWorkOrderDtos.AcceptWorkOrderReq;
import com.medassist.tech.web.DeviceWorkOrderDtos.CancelWorkOrderReq;
import com.medassist.tech.web.DeviceWorkOrderDtos.CreateWorkOrderReq;
import com.medassist.tech.web.DeviceWorkOrderDtos.FinishWorkOrderReq;
import com.medassist.tech.web.DeviceWorkOrderDtos.StartWorkOrderReq;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 维保工单权限矩阵：读 / 维修 / 验收三权分立。 */
class DeviceWorkOrderControllerPermissionTest {

    private DeviceWorkOrderService service;
    private DeviceWorkOrderController controller;

    private final UUID orderId = UUID.randomUUID();
    private final UUID deviceId = UUID.randomUUID();
    private final DataScope scope = DataScope.all("tech01", true);

    @BeforeEach
    void setUp() {
        service = mock(DeviceWorkOrderService.class);
        controller = new DeviceWorkOrderController(service);
    }

    private void assertForbidden(Runnable call) {
        assertThatThrownBy(call::run)
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.FORBIDDEN);
    }

    private DeviceWorkOrder order() {
        return new DeviceWorkOrder(orderId, "WO20261105000001", deviceId, "DEV1", "CT-1",
                DeviceWorkOrder.CORRECTIVE, "NORMAL", "球管异响", null, "tech01",
                Instant.now(), null, DeviceWorkOrder.OPEN, null, null, null, null, null, null,
                null, null, "tech01", 0L, Instant.now());
    }

    @Test
    @DisplayName("列表/详情：必须 tech:device:read")
    void readsRequireDeviceRead() {
        when(service.list(null, null, null, null, scope)).thenReturn(List.of());
        when(service.detail(orderId, scope)).thenReturn(order());

        assertForbidden(() -> controller.list(null, null, null, null, "tech:device:write", scope));
        assertForbidden(() -> controller.detail(orderId, "tech:device:write", scope));
        controller.list(null, null, null, null, "tech:device:read", scope);
        controller.detail(orderId, "tech:device:read", scope);

        verify(service).list(null, null, null, null, scope);
        verify(service).detail(orderId, scope);
    }

    @Test
    @DisplayName("报修/开始/完工/取消：必须 tech:device:write")
    void writesRequireDeviceWrite() {
        CreateWorkOrderReq create = new CreateWorkOrderReq("CORRECTIVE", null, "球管异响",
                null, null, null);

        assertForbidden(() -> controller.create(deviceId, create, "tech:device:read", "tech01",
                scope));
        assertForbidden(() -> controller.start(orderId, new StartWorkOrderReq(0L),
                "tech:device:read", "tech01", scope));
        assertForbidden(() -> controller.finish(orderId,
                new FinishWorkOrderReq(0L, null, "更换球管", null), "tech:device:read", "tech01",
                scope));
        assertForbidden(() -> controller.cancel(orderId, new CancelWorkOrderReq(0L, "重复报修"),
                "tech:device:read", "tech01", scope));

        verify(service, never()).create(any(), any(), any(), any());
        verify(service, never()).start(any(), anyLong(), any(), any());
        verify(service, never()).finish(any(), any(), any(), any());
        verify(service, never()).cancel(any(), any(), any(), any());
    }

    @Test
    @DisplayName("验收：只有 tech:device:accept 可以，维修写权限不够")
    void acceptRequiresDedicatedPermission() {
        AcceptWorkOrderReq req = new AcceptWorkOrderReq(0L, "验收合格");

        assertForbidden(() -> controller.accept(orderId, req, "tech:device:write", "tech02", scope));
        controller.accept(orderId, req, "tech:device:accept", "qc01", scope);

        verify(service).accept(orderId, req, "qc01", scope);
    }
}
