package com.medassist.tech.web;

import com.medassist.common.api.ApiResponse;
import com.medassist.common.security.AuthHeaders;
import com.medassist.common.security.Permissions;
import com.medassist.tech.domain.TechModel.DeviceWorkOrder;
import com.medassist.tech.service.DeviceWorkOrderService;
import com.medassist.tech.web.DeviceWorkOrderDtos.AcceptWorkOrderReq;
import com.medassist.tech.web.DeviceWorkOrderDtos.CancelWorkOrderReq;
import com.medassist.tech.web.DeviceWorkOrderDtos.CreateWorkOrderReq;
import com.medassist.tech.web.DeviceWorkOrderDtos.FinishWorkOrderReq;
import com.medassist.tech.web.DeviceWorkOrderDtos.StartWorkOrderReq;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * 设备维保工单 API（G82）。
 *
 * <p>权限三分：
 * <ul>
 *   <li>{@code tech:device:read} —— 看工单列表与详情（技师/质控/管理员）；</li>
 *   <li>{@code tech:device:write} —— 报修、开始维修、完工、取消（技师/管理员）；</li>
 *   <li>{@code tech:device:accept} —— 验收（设备科/质控）。与 write 分离，
 *       确保"修的人"和"验收的人"不是同一个权限面（另有建单人不得自验的四眼校验）。</li>
 * </ul>
 */
@RestController
@RequestMapping("/tech")
public class DeviceWorkOrderController {

    private static final String READ = "tech:device:read";
    private static final String WRITE = "tech:device:write";
    private static final String ACCEPT = "tech:device:accept";

    private final DeviceWorkOrderService service;

    public DeviceWorkOrderController(DeviceWorkOrderService service) {
        this.service = service;
    }

    /** 全部工单（按状态/类型/设备过滤）；设备详情页用同一个接口带 deviceId。 */
    @GetMapping("/device-work-orders")
    public ApiResponse<List<DeviceWorkOrder>> list(
            @RequestParam(required = false) UUID deviceId,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String orderType,
            @RequestParam(required = false) Integer limit,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions) {
        Permissions.require(permissions, READ);
        return ApiResponse.ok(service.list(deviceId, status, orderType, limit));
    }

    @GetMapping("/device-work-orders/{id}")
    public ApiResponse<DeviceWorkOrder> detail(
            @PathVariable UUID id,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions) {
        Permissions.require(permissions, READ);
        return ApiResponse.ok(service.detail(id));
    }

    /** 报修/建单：挂在设备下，保证工单一定能追溯到设备档案。 */
    @PostMapping("/devices/{deviceId}/work-orders")
    public ApiResponse<DeviceWorkOrder> create(
            @PathVariable UUID deviceId, @Valid @RequestBody CreateWorkOrderReq req,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(value = AuthHeaders.ACTOR, defaultValue = "system") String actor) {
        Permissions.require(permissions, WRITE);
        return ApiResponse.ok(service.create(deviceId, req, actor));
    }

    @PostMapping("/device-work-orders/{id}/start")
    public ApiResponse<DeviceWorkOrder> start(
            @PathVariable UUID id, @Valid @RequestBody StartWorkOrderReq req,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(value = AuthHeaders.ACTOR, defaultValue = "system") String actor) {
        Permissions.require(permissions, WRITE);
        return ApiResponse.ok(service.start(id, req.version(), actor));
    }

    @PostMapping("/device-work-orders/{id}/finish")
    public ApiResponse<DeviceWorkOrder> finish(
            @PathVariable UUID id, @Valid @RequestBody FinishWorkOrderReq req,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(value = AuthHeaders.ACTOR, defaultValue = "system") String actor) {
        Permissions.require(permissions, WRITE);
        return ApiResponse.ok(service.finish(id, req, actor));
    }

    @PostMapping("/device-work-orders/{id}/accept")
    public ApiResponse<DeviceWorkOrder> accept(
            @PathVariable UUID id, @Valid @RequestBody AcceptWorkOrderReq req,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(value = AuthHeaders.ACTOR, defaultValue = "system") String actor) {
        Permissions.require(permissions, ACCEPT);
        return ApiResponse.ok(service.accept(id, req, actor));
    }

    @PostMapping("/device-work-orders/{id}/cancel")
    public ApiResponse<DeviceWorkOrder> cancel(
            @PathVariable UUID id, @Valid @RequestBody CancelWorkOrderReq req,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(value = AuthHeaders.ACTOR, defaultValue = "system") String actor) {
        Permissions.require(permissions, WRITE);
        return ApiResponse.ok(service.cancel(id, req, actor));
    }
}
