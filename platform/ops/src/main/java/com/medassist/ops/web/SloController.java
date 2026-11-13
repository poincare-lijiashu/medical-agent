package com.medassist.ops.web;

import com.medassist.common.api.ApiResponse;
import com.medassist.common.security.AuthHeaders;
import com.medassist.common.security.Permissions;
import com.medassist.ops.domain.SloModel.Dashboard;
import com.medassist.ops.domain.SloModel.Detail;
import com.medassist.ops.domain.SloModel.Snapshot;
import com.medassist.ops.service.SloService;
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
import java.util.Map;
import java.util.UUID;

/**
 * SLO 与错误预算 API（§300）。
 *
 * <p>权限：查询 {@code ops:slo:read}；定义/状态/快照 {@code ops:slo:manage}。
 * 操作人来自网关注入的 {@code X-Actor}。
 */
@RestController
@RequestMapping("/ops/slo")
public class SloController {

    private static final String READ = "ops:slo:read";
    private static final String MANAGE = "ops:slo:manage";

    private final SloService service;

    public SloController(SloService service) {
        this.service = service;
    }

    @GetMapping
    public ApiResponse<List<Detail>> list(
            @RequestParam(defaultValue = "true") boolean onlyActive,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions) {
        Permissions.require(permissions, READ);
        return ApiResponse.ok(service.list(onlyActive));
    }

    @GetMapping("/dashboard")
    public ApiResponse<Dashboard> dashboard(
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions) {
        Permissions.require(permissions, READ);
        return ApiResponse.ok(service.dashboard());
    }

    @GetMapping("/services")
    public ApiResponse<List<Map<String, Object>>> services(
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions) {
        Permissions.require(permissions, READ);
        return ApiResponse.ok(service.services());
    }

    @PostMapping
    public ApiResponse<Detail> upsert(
            @Valid @RequestBody OpsDtos.SloUpsertReq req,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(value = AuthHeaders.ACTOR, required = false) String actor,
            @RequestHeader(value = AuthHeaders.CLIENT_IP, required = false) String clientIp,
            @RequestHeader(value = AuthHeaders.TRACE_ID, required = false) String traceId) {
        Permissions.require(permissions, MANAGE);
        return ApiResponse.ok(service.upsert(req, actor, clientIp, traceId));
    }

    @GetMapping("/{id}")
    public ApiResponse<Detail> detail(
            @PathVariable UUID id,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions) {
        Permissions.require(permissions, READ);
        return ApiResponse.ok(service.detail(id));
    }

    @PostMapping("/{id}/status")
    public ApiResponse<Detail> status(
            @PathVariable UUID id,
            @Valid @RequestBody OpsDtos.SloStatusReq req,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(value = AuthHeaders.ACTOR, required = false) String actor,
            @RequestHeader(value = AuthHeaders.CLIENT_IP, required = false) String clientIp,
            @RequestHeader(value = AuthHeaders.TRACE_ID, required = false) String traceId) {
        Permissions.require(permissions, MANAGE);
        return ApiResponse.ok(service.changeStatus(id, req.status(), actor, clientIp, traceId));
    }

    @PostMapping("/{id}/snapshots")
    public ApiResponse<Snapshot> snapshot(
            @PathVariable UUID id,
            @Valid @RequestBody OpsDtos.SloSnapshotReq req,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(value = AuthHeaders.ACTOR, required = false) String actor,
            @RequestHeader(value = AuthHeaders.CLIENT_IP, required = false) String clientIp,
            @RequestHeader(value = AuthHeaders.TRACE_ID, required = false) String traceId) {
        Permissions.require(permissions, MANAGE);
        return ApiResponse.ok(service.recordSnapshot(id, req, actor, clientIp, traceId));
    }

    @GetMapping("/{id}/snapshots")
    public ApiResponse<List<Snapshot>> snapshots(
            @PathVariable UUID id,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions) {
        Permissions.require(permissions, READ);
        return ApiResponse.ok(service.detail(id).snapshots());
    }
}
