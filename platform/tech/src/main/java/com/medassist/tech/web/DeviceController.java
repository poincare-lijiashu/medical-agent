package com.medassist.tech.web;

import com.medassist.common.api.ApiResponse;
import com.medassist.common.security.AuthHeaders;
import com.medassist.common.security.DataScope;
import com.medassist.common.security.Permissions;
import com.medassist.tech.domain.TechModel.Device;
import com.medassist.tech.service.DeviceQcService;
import com.medassist.tech.web.DeviceDtos.CreateDeviceReq;
import com.medassist.tech.web.DeviceDtos.DeviceSummary;
import com.medassist.tech.web.DeviceDtos.DeviceView;
import com.medassist.tech.web.DeviceDtos.RecordQcReq;
import com.medassist.tech.web.DeviceDtos.UpdateDeviceStatusReq;
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
 * 医技设备档案与质控 API（G35）。
 *
 * <p>查看 {@code tech:device:read}；建档/状态变更/质控登记 {@code tech:device:write}。
 */
@RestController
@RequestMapping("/tech/devices")
public class DeviceController {

    private static final String READ = "tech:device:read";
    private static final String WRITE = "tech:device:write";

    private final DeviceQcService service;

    public DeviceController(DeviceQcService service) {
        this.service = service;
    }

    @GetMapping
    public ApiResponse<List<Device>> list(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String modality,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Integer limit,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            DataScope scope) {
        Permissions.require(permissions, READ);
        return ApiResponse.ok(service.list(keyword, modality, status, limit, scope));
    }

    @GetMapping("/summary")
    public ApiResponse<DeviceSummary> summary(
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            DataScope scope) {
        Permissions.require(permissions, READ);
        return ApiResponse.ok(service.summary(scope));
    }

    @GetMapping("/{id}")
    public ApiResponse<DeviceView> detail(
            @PathVariable UUID id,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            DataScope scope) {
        Permissions.require(permissions, READ);
        return ApiResponse.ok(service.detail(id, scope));
    }

    @PostMapping
    public ApiResponse<DeviceView> create(
            @Valid @RequestBody CreateDeviceReq req,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(value = AuthHeaders.ACTOR, defaultValue = "system") String actor,
            DataScope scope) {
        Permissions.require(permissions, WRITE);
        return ApiResponse.ok(service.create(req, actor, scope));
    }

    /** 状态变更（CAS）：ACTIVE / MAINTENANCE / RETIRED，停用不可恢复。 */
    @PostMapping("/{id}/status")
    public ApiResponse<DeviceView> updateStatus(
            @PathVariable UUID id,
            @Valid @RequestBody UpdateDeviceStatusReq req,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(value = AuthHeaders.ACTOR, defaultValue = "system") String actor,
            DataScope scope) {
        Permissions.require(permissions, WRITE);
        return ApiResponse.ok(service.updateStatus(id, req, actor, scope));
    }

    /** 质控登记：结论 PASS/FAIL，记录只追加不可改。 */
    @PostMapping("/{id}/qc")
    public ApiResponse<DeviceView> recordQc(
            @PathVariable UUID id,
            @Valid @RequestBody RecordQcReq req,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(value = AuthHeaders.ACTOR, defaultValue = "system") String actor,
            DataScope scope) {
        Permissions.require(permissions, WRITE);
        return ApiResponse.ok(service.recordQc(id, req, actor, scope));
    }
}
