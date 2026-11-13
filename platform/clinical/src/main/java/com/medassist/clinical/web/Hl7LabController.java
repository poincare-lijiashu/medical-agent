package com.medassist.clinical.web;

import com.medassist.clinical.domain.Hl7LabModel.Case;
import com.medassist.clinical.domain.Hl7LabModel.Dashboard;
import com.medassist.clinical.domain.Hl7LabModel.Endpoint;
import com.medassist.clinical.domain.Hl7LabModel.Run;
import com.medassist.clinical.service.Hl7LabService;
import com.medassist.common.api.ApiResponse;
import com.medassist.common.api.PageResult;
import com.medassist.common.security.AuthHeaders;
import com.medassist.common.security.Permissions;
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
 * HL7 v2 联调测试台 API（§301）。
 *
 * <p>权限：查询 {@code integration:hl7:read}；端点/用例维护 {@code integration:hl7:manage}；
 * 执行测试 {@code integration:hl7:run}。执行人来自网关注入的 {@code X-Actor}。
 */
@RestController
@RequestMapping("/clinical/integration/hl7")
public class Hl7LabController {

    private static final String READ = "integration:hl7:read";
    private static final String MANAGE = "integration:hl7:manage";
    private static final String RUN = "integration:hl7:run";

    private final Hl7LabService service;

    public Hl7LabController(Hl7LabService service) {
        this.service = service;
    }

    @GetMapping("/dashboard")
    public ApiResponse<Dashboard> dashboard(
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions) {
        Permissions.require(permissions, READ);
        return ApiResponse.ok(service.dashboard());
    }

    // ------------------------------------------------------------------ 端点

    @GetMapping("/endpoints")
    public ApiResponse<List<Endpoint>> endpoints(
            @RequestParam(defaultValue = "true") boolean onlyActive,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions) {
        Permissions.require(permissions, READ);
        return ApiResponse.ok(service.listEndpoints(onlyActive));
    }

    @PostMapping("/endpoints")
    public ApiResponse<Endpoint> upsertEndpoint(
            @Valid @RequestBody Hl7LabDtos.EndpointReq req,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(value = AuthHeaders.ACTOR, required = false) String actor) {
        Permissions.require(permissions, MANAGE);
        return ApiResponse.ok(service.upsertEndpoint(req, actor));
    }

    @PostMapping("/endpoints/{id}/status")
    public ApiResponse<Endpoint> endpointStatus(
            @PathVariable UUID id,
            @Valid @RequestBody Hl7LabDtos.StatusReq req,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(value = AuthHeaders.ACTOR, required = false) String actor) {
        Permissions.require(permissions, MANAGE);
        return ApiResponse.ok(service.changeEndpointStatus(id, req.status(), actor));
    }

    // ------------------------------------------------------------------ 用例

    @GetMapping("/cases")
    public ApiResponse<List<Case>> cases(
            @RequestParam(defaultValue = "true") boolean onlyActive,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions) {
        Permissions.require(permissions, READ);
        return ApiResponse.ok(service.listCases(onlyActive));
    }

    @GetMapping("/cases/{id}")
    public ApiResponse<Case> hl7Case(
            @PathVariable UUID id,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions) {
        Permissions.require(permissions, READ);
        return ApiResponse.ok(service.hl7Case(id));
    }

    @PostMapping("/cases")
    public ApiResponse<Case> upsertCase(
            @Valid @RequestBody Hl7LabDtos.CaseReq req,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(value = AuthHeaders.ACTOR, required = false) String actor) {
        Permissions.require(permissions, MANAGE);
        return ApiResponse.ok(service.upsertCase(req, actor));
    }

    @PostMapping("/cases/{id}/status")
    public ApiResponse<Case> caseStatus(
            @PathVariable UUID id,
            @Valid @RequestBody Hl7LabDtos.StatusReq req,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(value = AuthHeaders.ACTOR, required = false) String actor) {
        Permissions.require(permissions, MANAGE);
        return ApiResponse.ok(service.changeCaseStatus(id, req.status(), actor));
    }

    // ------------------------------------------------------------------ 执行

    @PostMapping("/run")
    public ApiResponse<Run> run(
            @Valid @RequestBody Hl7LabDtos.RunReq req,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(value = AuthHeaders.ACTOR, required = false) String actor) {
        Permissions.require(permissions, RUN);
        return ApiResponse.ok(service.run(req.caseId(), req.endpointId(), actor));
    }

    @GetMapping("/runs")
    public ApiResponse<PageResult<Run>> runs(
            @RequestParam(required = false) String caseCode,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions) {
        Permissions.require(permissions, READ);
        int safePage = Math.max(page, 1);
        int safeSize = Math.min(Math.max(size, 1), PageResult.MAX_PAGE_SIZE);
        long total = service.countRuns(caseCode, status);
        List<Run> items = service.listRuns(caseCode, status, safeSize, (safePage - 1) * safeSize);
        return ApiResponse.ok(PageResult.of(safePage, safeSize, total, items));
    }

    @GetMapping("/runs/{id}")
    public ApiResponse<Run> run(
            @PathVariable UUID id,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions) {
        Permissions.require(permissions, READ);
        return ApiResponse.ok(service.run(id));
    }
}
