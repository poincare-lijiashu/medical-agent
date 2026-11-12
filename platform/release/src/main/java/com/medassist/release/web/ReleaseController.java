package com.medassist.release.web;

import com.medassist.common.api.ApiResponse;
import com.medassist.common.api.PageResult;
import com.medassist.common.security.AuthHeaders;
import com.medassist.common.security.Permissions;
import com.medassist.release.domain.ReleaseIntegrationModel.CiEvent;
import com.medassist.release.domain.ReleaseIntegrationModel.CiSource;
import com.medassist.release.domain.ReleaseIntegrationModel.CiSourceCreated;
import com.medassist.release.domain.ReleaseModel.AuditLog;
import com.medassist.release.domain.ReleaseModel.ChangeWindow;
import com.medassist.release.domain.ReleaseModel.Compatibility;
import com.medassist.release.domain.ReleaseModel.Component;
import com.medassist.release.domain.ReleaseModel.Deployment;
import com.medassist.release.domain.ReleaseModel.Patch;
import com.medassist.release.domain.ReleaseModel.Train;
import com.medassist.release.domain.ReleaseModel.Version;
import com.medassist.release.repo.ReleaseRepository;
import com.medassist.release.service.ChangeWindowService;
import com.medassist.release.service.CiIngestService;
import com.medassist.release.service.CompatibilityService;
import com.medassist.release.service.DeploymentService;
import com.medassist.release.service.PatchService;
import com.medassist.release.service.ReleaseCatalogService;
import com.medassist.release.service.ReleaseDashboardService;
import com.medassist.release.service.ReleaseTrainService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
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
 * 版本与补丁 API。
 *
 * <p>权限切分（auth V91）：
 * <ul>
 *   <li>{@code release:read}：组件/版本/列车/兼容/补丁/窗口/部署/看板；</li>
 *   <li>{@code release:write}：注册组件与版本、编排列车、创建补丁与窗口；</li>
 *   <li>{@code release:approve}：版本发布、列车冻结/发布、补丁审批/发布；</li>
 *   <li>{@code release:deploy}：生产部署与回滚记录；</li>
 *   <li>{@code release:audit:read}：发布审计。</li>
 * </ul>
 */
@RestController
@RequestMapping("/release")
public class ReleaseController {

    private static final String READ = "release:read";
    private static final String WRITE = "release:write";
    private static final String APPROVE = "release:approve";
    private static final String DEPLOY = "release:deploy";
    private static final String AUDIT_READ = "release:audit:read";
    private static final String CI_MANAGE = "release:integration:manage";

    private final ReleaseCatalogService catalogService;
    private final ReleaseTrainService trainService;
    private final CompatibilityService compatibilityService;
    private final PatchService patchService;
    private final ChangeWindowService windowService;
    private final DeploymentService deploymentService;
    private final ReleaseDashboardService dashboardService;
    private final CiIngestService ciIngestService;
    private final ReleaseRepository repo;

    public ReleaseController(ReleaseCatalogService catalogService, ReleaseTrainService trainService,
                             CompatibilityService compatibilityService, PatchService patchService,
                             ChangeWindowService windowService, DeploymentService deploymentService,
                             ReleaseDashboardService dashboardService, CiIngestService ciIngestService,
                             ReleaseRepository repo) {
        this.catalogService = catalogService;
        this.trainService = trainService;
        this.compatibilityService = compatibilityService;
        this.patchService = patchService;
        this.windowService = windowService;
        this.deploymentService = deploymentService;
        this.dashboardService = dashboardService;
        this.ciIngestService = ciIngestService;
        this.repo = repo;
    }

    // ------------------------------------------------------------------ 组件/版本

    @GetMapping("/components")
    public ApiResponse<List<Component>> components(
            @RequestParam(defaultValue = "true") boolean onlyActive,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions) {
        Permissions.require(permissions, READ);
        return ApiResponse.ok(catalogService.listComponents(onlyActive));
    }

    @PostMapping("/components")
    public ApiResponse<Component> upsertComponent(
            @Valid @RequestBody ReleaseDtos.ComponentReq req,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(value = AuthHeaders.ACTOR, required = false) String actor,
            @RequestHeader(value = AuthHeaders.CLIENT_IP, required = false) String clientIp,
            @RequestHeader(value = AuthHeaders.TRACE_ID, required = false) String traceId) {
        Permissions.require(permissions, WRITE);
        return ApiResponse.ok(catalogService.upsertComponent(req, actor, clientIp, traceId));
    }

    @GetMapping("/versions")
    public ApiResponse<PageResult<Version>> versions(
            @RequestParam(required = false) String componentCode,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions) {
        Permissions.require(permissions, READ);
        int safePage = Math.max(page, 1);
        int safeSize = Math.min(Math.max(size, 1), PageResult.MAX_PAGE_SIZE);
        long total = catalogService.countVersions(componentCode, status);
        return ApiResponse.ok(PageResult.of(safePage, safeSize, total,
                catalogService.listVersions(componentCode, status, safeSize,
                        (safePage - 1) * safeSize)));
    }

    @PostMapping("/versions")
    public ApiResponse<Version> registerVersion(
            @Valid @RequestBody ReleaseDtos.VersionReq req,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(value = AuthHeaders.ACTOR, required = false) String actor,
            @RequestHeader(value = AuthHeaders.CLIENT_IP, required = false) String clientIp,
            @RequestHeader(value = AuthHeaders.TRACE_ID, required = false) String traceId) {
        Permissions.require(permissions, WRITE);
        return ApiResponse.ok(catalogService.registerVersion(req, actor, clientIp, traceId));
    }

    @PostMapping("/versions/{id}/publish")
    public ApiResponse<Version> publishVersion(
            @PathVariable UUID id,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(value = AuthHeaders.ACTOR, required = false) String actor,
            @RequestHeader(value = AuthHeaders.CLIENT_IP, required = false) String clientIp,
            @RequestHeader(value = AuthHeaders.TRACE_ID, required = false) String traceId) {
        Permissions.require(permissions, APPROVE);
        return ApiResponse.ok(catalogService.publishVersion(id, actor, clientIp, traceId));
    }

    @PostMapping("/versions/{id}/deprecate")
    public ApiResponse<Version> deprecateVersion(
            @PathVariable UUID id,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(value = AuthHeaders.ACTOR, required = false) String actor,
            @RequestHeader(value = AuthHeaders.CLIENT_IP, required = false) String clientIp,
            @RequestHeader(value = AuthHeaders.TRACE_ID, required = false) String traceId) {
        Permissions.require(permissions, APPROVE);
        return ApiResponse.ok(catalogService.deprecateVersion(id, actor, clientIp, traceId));
    }

    @PostMapping("/versions/{id}/yank")
    public ApiResponse<Version> yankVersion(
            @PathVariable UUID id,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(value = AuthHeaders.ACTOR, required = false) String actor,
            @RequestHeader(value = AuthHeaders.CLIENT_IP, required = false) String clientIp,
            @RequestHeader(value = AuthHeaders.TRACE_ID, required = false) String traceId) {
        Permissions.require(permissions, APPROVE);
        return ApiResponse.ok(catalogService.yankVersion(id, actor, clientIp, traceId));
    }

    // ------------------------------------------------------------------ 兼容矩阵

    @GetMapping("/compatibility")
    public ApiResponse<List<Compatibility>> compatibility(
            @RequestParam UUID versionId,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions) {
        Permissions.require(permissions, READ);
        return ApiResponse.ok(compatibilityService.list(versionId));
    }

    @PostMapping("/compatibility")
    public ApiResponse<Compatibility> addCompatibility(
            @Valid @RequestBody ReleaseDtos.CompatibilityReq req,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(value = AuthHeaders.ACTOR, required = false) String actor,
            @RequestHeader(value = AuthHeaders.CLIENT_IP, required = false) String clientIp,
            @RequestHeader(value = AuthHeaders.TRACE_ID, required = false) String traceId) {
        Permissions.require(permissions, WRITE);
        return ApiResponse.ok(compatibilityService.add(req, actor, clientIp, traceId));
    }

    @DeleteMapping("/compatibility/{id}")
    public ApiResponse<Map<String, Object>> removeCompatibility(
            @PathVariable UUID id,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(value = AuthHeaders.ACTOR, required = false) String actor,
            @RequestHeader(value = AuthHeaders.CLIENT_IP, required = false) String clientIp,
            @RequestHeader(value = AuthHeaders.TRACE_ID, required = false) String traceId) {
        Permissions.require(permissions, WRITE);
        compatibilityService.remove(id, actor, clientIp, traceId);
        return ApiResponse.ok(Map.of("ok", true));
    }

    // ------------------------------------------------------------------ 发布列车

    @GetMapping("/trains")
    public ApiResponse<PageResult<Train>> trains(
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions) {
        Permissions.require(permissions, READ);
        int safePage = Math.max(page, 1);
        int safeSize = Math.min(Math.max(size, 1), PageResult.MAX_PAGE_SIZE);
        long total = trainService.count(status);
        return ApiResponse.ok(PageResult.of(safePage, safeSize, total,
                trainService.list(status, safeSize, (safePage - 1) * safeSize)));
    }

    @PostMapping("/trains")
    public ApiResponse<Train> createTrain(
            @Valid @RequestBody ReleaseDtos.TrainReq req,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(value = AuthHeaders.ACTOR, required = false) String actor,
            @RequestHeader(value = AuthHeaders.CLIENT_IP, required = false) String clientIp,
            @RequestHeader(value = AuthHeaders.TRACE_ID, required = false) String traceId) {
        Permissions.require(permissions, WRITE);
        return ApiResponse.ok(trainService.create(req, actor, clientIp, traceId));
    }

    @GetMapping("/trains/{id}")
    public ApiResponse<Train> train(
            @PathVariable UUID id,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions) {
        Permissions.require(permissions, READ);
        return ApiResponse.ok(trainService.detail(id));
    }

    @PostMapping("/trains/{id}/items")
    public ApiResponse<Train> addTrainItem(
            @PathVariable UUID id,
            @Valid @RequestBody ReleaseDtos.TrainItemReq req,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(value = AuthHeaders.ACTOR, required = false) String actor,
            @RequestHeader(value = AuthHeaders.CLIENT_IP, required = false) String clientIp,
            @RequestHeader(value = AuthHeaders.TRACE_ID, required = false) String traceId) {
        Permissions.require(permissions, WRITE);
        return ApiResponse.ok(trainService.addItem(id, req, actor, clientIp, traceId));
    }

    @DeleteMapping("/trains/{id}/items/{componentCode}")
    public ApiResponse<Train> removeTrainItem(
            @PathVariable UUID id,
            @PathVariable String componentCode,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(value = AuthHeaders.ACTOR, required = false) String actor,
            @RequestHeader(value = AuthHeaders.CLIENT_IP, required = false) String clientIp,
            @RequestHeader(value = AuthHeaders.TRACE_ID, required = false) String traceId) {
        Permissions.require(permissions, WRITE);
        return ApiResponse.ok(trainService.removeItem(id, componentCode, actor, clientIp, traceId));
    }

    @PostMapping("/trains/{id}/freeze")
    public ApiResponse<Train> freezeTrain(
            @PathVariable UUID id,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(value = AuthHeaders.ACTOR, required = false) String actor,
            @RequestHeader(value = AuthHeaders.CLIENT_IP, required = false) String clientIp,
            @RequestHeader(value = AuthHeaders.TRACE_ID, required = false) String traceId) {
        Permissions.require(permissions, APPROVE);
        return ApiResponse.ok(trainService.freeze(id, actor, clientIp, traceId));
    }

    @PostMapping("/trains/{id}/release")
    public ApiResponse<Train> releaseTrain(
            @PathVariable UUID id,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(value = AuthHeaders.ACTOR, required = false) String actor,
            @RequestHeader(value = AuthHeaders.CLIENT_IP, required = false) String clientIp,
            @RequestHeader(value = AuthHeaders.TRACE_ID, required = false) String traceId) {
        Permissions.require(permissions, APPROVE);
        return ApiResponse.ok(trainService.release(id, actor, clientIp, traceId));
    }

    @PostMapping("/trains/{id}/cancel")
    public ApiResponse<Train> cancelTrain(
            @PathVariable UUID id,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(value = AuthHeaders.ACTOR, required = false) String actor,
            @RequestHeader(value = AuthHeaders.CLIENT_IP, required = false) String clientIp,
            @RequestHeader(value = AuthHeaders.TRACE_ID, required = false) String traceId) {
        Permissions.require(permissions, WRITE);
        return ApiResponse.ok(trainService.cancel(id, actor, clientIp, traceId));
    }

    @GetMapping("/trains/{id}/readiness")
    public ApiResponse<Map<String, Object>> trainReadiness(
            @PathVariable UUID id,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions) {
        Permissions.require(permissions, READ);
        return ApiResponse.ok(trainService.readiness(id));
    }

    @GetMapping("/trains/{id}/compatibility")
    public ApiResponse<List<Map<String, Object>>> trainCompatibility(
            @PathVariable UUID id,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions) {
        Permissions.require(permissions, READ);
        return ApiResponse.ok(trainService.compatibilityConflicts(id));
    }

    // ------------------------------------------------------------------ 补丁

    @GetMapping("/patches")
    public ApiResponse<PageResult<Patch>> patches(
            @RequestParam(required = false) String componentCode,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions) {
        Permissions.require(permissions, READ);
        int safePage = Math.max(page, 1);
        int safeSize = Math.min(Math.max(size, 1), PageResult.MAX_PAGE_SIZE);
        long total = patchService.count(componentCode, status);
        return ApiResponse.ok(PageResult.of(safePage, safeSize, total,
                patchService.list(componentCode, status, safeSize, (safePage - 1) * safeSize)));
    }

    @PostMapping("/patches")
    public ApiResponse<Patch> createPatch(
            @Valid @RequestBody ReleaseDtos.PatchReq req,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(value = AuthHeaders.ACTOR, required = false) String actor,
            @RequestHeader(value = AuthHeaders.CLIENT_IP, required = false) String clientIp,
            @RequestHeader(value = AuthHeaders.TRACE_ID, required = false) String traceId) {
        Permissions.require(permissions, WRITE);
        return ApiResponse.ok(patchService.create(req, actor, clientIp, traceId));
    }

    @GetMapping("/patches/{id}")
    public ApiResponse<Patch> patch(
            @PathVariable UUID id,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions) {
        Permissions.require(permissions, READ);
        return ApiResponse.ok(patchService.requirePatch(id));
    }

    @PostMapping("/patches/{id}/submit")
    public ApiResponse<Patch> submitPatch(
            @PathVariable UUID id,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(value = AuthHeaders.ACTOR, required = false) String actor,
            @RequestHeader(value = AuthHeaders.CLIENT_IP, required = false) String clientIp,
            @RequestHeader(value = AuthHeaders.TRACE_ID, required = false) String traceId) {
        Permissions.require(permissions, WRITE);
        return ApiResponse.ok(patchService.submit(id, actor, clientIp, traceId));
    }

    @PostMapping("/patches/{id}/approve")
    public ApiResponse<Patch> approvePatch(
            @PathVariable UUID id,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(value = AuthHeaders.ACTOR, required = false) String actor,
            @RequestHeader(value = AuthHeaders.CLIENT_IP, required = false) String clientIp,
            @RequestHeader(value = AuthHeaders.TRACE_ID, required = false) String traceId) {
        Permissions.require(permissions, APPROVE);
        return ApiResponse.ok(patchService.approve(id, actor, clientIp, traceId));
    }

    @PostMapping("/patches/{id}/release")
    public ApiResponse<Patch> releasePatch(
            @PathVariable UUID id,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(value = AuthHeaders.ACTOR, required = false) String actor,
            @RequestHeader(value = AuthHeaders.CLIENT_IP, required = false) String clientIp,
            @RequestHeader(value = AuthHeaders.TRACE_ID, required = false) String traceId) {
        Permissions.require(permissions, APPROVE);
        return ApiResponse.ok(patchService.release(id, actor, clientIp, traceId));
    }

    @PostMapping("/patches/{id}/withdraw")
    public ApiResponse<Patch> withdrawPatch(
            @PathVariable UUID id,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(value = AuthHeaders.ACTOR, required = false) String actor,
            @RequestHeader(value = AuthHeaders.CLIENT_IP, required = false) String clientIp,
            @RequestHeader(value = AuthHeaders.TRACE_ID, required = false) String traceId) {
        Permissions.require(permissions, WRITE);
        return ApiResponse.ok(patchService.withdraw(id, actor, clientIp, traceId));
    }

    // ------------------------------------------------------------------ 变更窗口

    @GetMapping("/change-windows")
    public ApiResponse<List<ChangeWindow>> windows(
            @RequestParam(required = false) String kind,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "50") int limit,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions) {
        Permissions.require(permissions, READ);
        return ApiResponse.ok(windowService.list(kind, status, limit));
    }

    @PostMapping("/change-windows")
    public ApiResponse<ChangeWindow> createWindow(
            @Valid @RequestBody ReleaseDtos.WindowReq req,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(value = AuthHeaders.ACTOR, required = false) String actor,
            @RequestHeader(value = AuthHeaders.CLIENT_IP, required = false) String clientIp,
            @RequestHeader(value = AuthHeaders.TRACE_ID, required = false) String traceId) {
        Permissions.require(permissions, WRITE);
        return ApiResponse.ok(windowService.create(req, actor, clientIp, traceId));
    }

    @PostMapping("/change-windows/{id}/cancel")
    public ApiResponse<ChangeWindow> cancelWindow(
            @PathVariable UUID id,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(value = AuthHeaders.ACTOR, required = false) String actor,
            @RequestHeader(value = AuthHeaders.CLIENT_IP, required = false) String clientIp,
            @RequestHeader(value = AuthHeaders.TRACE_ID, required = false) String traceId) {
        Permissions.require(permissions, WRITE);
        return ApiResponse.ok(windowService.cancel(id, actor, clientIp, traceId));
    }

    // ------------------------------------------------------------------ 部署

    @GetMapping("/deployments")
    public ApiResponse<PageResult<Deployment>> deployments(
            @RequestParam(required = false) String componentCode,
            @RequestParam(required = false) String environment,
            @RequestParam(required = false) String result,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions) {
        Permissions.require(permissions, READ);
        int safePage = Math.max(page, 1);
        int safeSize = Math.min(Math.max(size, 1), PageResult.MAX_PAGE_SIZE);
        long total = deploymentService.count(componentCode, environment, result);
        return ApiResponse.ok(PageResult.of(safePage, safeSize, total,
                deploymentService.list(componentCode, environment, result, safeSize,
                        (safePage - 1) * safeSize)));
    }

    @PostMapping("/deployments")
    public ApiResponse<Deployment> recordDeployment(
            @Valid @RequestBody ReleaseDtos.DeployReq req,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(value = AuthHeaders.ACTOR, required = false) String actor,
            @RequestHeader(value = AuthHeaders.CLIENT_IP, required = false) String clientIp,
            @RequestHeader(value = AuthHeaders.TRACE_ID, required = false) String traceId) {
        Permissions.require(permissions, DEPLOY);
        return ApiResponse.ok(deploymentService.record(req, actor, clientIp, traceId));
    }

    @GetMapping("/deployments/{id}")
    public ApiResponse<Deployment> deployment(
            @PathVariable UUID id,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions) {
        Permissions.require(permissions, READ);
        return ApiResponse.ok(deploymentService.requireDeployment(id));
    }

    @PostMapping("/deployments/{id}/rollback")
    public ApiResponse<Deployment> rollback(
            @PathVariable UUID id,
            @Valid @RequestBody ReleaseDtos.RollbackReq req,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(value = AuthHeaders.ACTOR, required = false) String actor,
            @RequestHeader(value = AuthHeaders.CLIENT_IP, required = false) String clientIp,
            @RequestHeader(value = AuthHeaders.TRACE_ID, required = false) String traceId) {
        Permissions.require(permissions, DEPLOY);
        return ApiResponse.ok(deploymentService.rollback(id, req.reason(), actor, clientIp, traceId));
    }

    // ------------------------------------------------------------------ CI/CD 接入（§276）

    @GetMapping("/integrations/ci-sources")
    public ApiResponse<List<CiSource>> ciSources(
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions) {
        Permissions.require(permissions, READ);
        return ApiResponse.ok(ciIngestService.listSources());
    }

    @PostMapping("/integrations/ci-sources")
    public ApiResponse<CiSourceCreated> createCiSource(
            @Valid @RequestBody ReleaseIntegrationDtos.CiSourceReq req,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(value = AuthHeaders.ACTOR, required = false) String actor,
            @RequestHeader(value = AuthHeaders.CLIENT_IP, required = false) String clientIp,
            @RequestHeader(value = AuthHeaders.TRACE_ID, required = false) String traceId) {
        Permissions.require(permissions, CI_MANAGE);
        return ApiResponse.ok(ciIngestService.createSource(req, actor, clientIp, traceId));
    }

    @PostMapping("/integrations/ci-sources/{id}/rotate")
    public ApiResponse<CiSourceCreated> rotateCiSource(
            @PathVariable UUID id,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(value = AuthHeaders.ACTOR, required = false) String actor,
            @RequestHeader(value = AuthHeaders.CLIENT_IP, required = false) String clientIp,
            @RequestHeader(value = AuthHeaders.TRACE_ID, required = false) String traceId) {
        Permissions.require(permissions, CI_MANAGE);
        return ApiResponse.ok(ciIngestService.rotate(id, actor, clientIp, traceId));
    }

    @PostMapping("/integrations/ci-sources/{id}/disable")
    public ApiResponse<CiSource> disableCiSource(
            @PathVariable UUID id,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(value = AuthHeaders.ACTOR, required = false) String actor,
            @RequestHeader(value = AuthHeaders.CLIENT_IP, required = false) String clientIp,
            @RequestHeader(value = AuthHeaders.TRACE_ID, required = false) String traceId) {
        Permissions.require(permissions, CI_MANAGE);
        return ApiResponse.ok(ciIngestService.disable(id, actor, clientIp, traceId));
    }

    @GetMapping("/integrations/ci-events")
    public ApiResponse<List<CiEvent>> ciEvents(
            @RequestParam(required = false) String sourceCode,
            @RequestParam(required = false) String kind,
            @RequestParam(required = false) Integer limit,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions) {
        Permissions.require(permissions, READ);
        return ApiResponse.ok(ciIngestService.listEvents(sourceCode, kind, limit));
    }

    /**
     * 流水线入站：网关白名单放行，令牌校验通过后按事件幂等号处理。
     * 部署仍走完整门禁（冻结窗口/生产检查单/回滚链）。
     */
    @PostMapping("/hooks/cicd/{sourceCode}")
    public ApiResponse<Map<String, Object>> ingestCi(
            @PathVariable String sourceCode,
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestHeader(value = "X-CI-Event-Id", required = false) String eventId,
            @RequestBody ReleaseIntegrationDtos.CiEventReq req,
            @RequestHeader(value = AuthHeaders.CLIENT_IP, required = false) String clientIp,
            @RequestHeader(value = AuthHeaders.TRACE_ID, required = false) String traceId) {
        return ApiResponse.ok(ciIngestService.ingest(sourceCode, authorization, eventId, req,
                clientIp, traceId));
    }

    // ------------------------------------------------------------------ 看板/审计

    @GetMapping("/dashboard")
    public ApiResponse<Map<String, Object>> dashboard(
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions) {
        Permissions.require(permissions, READ);
        return ApiResponse.ok(dashboardService.dashboard());
    }

    @GetMapping("/audit")
    public ApiResponse<List<AuditLog>> audit(
            @RequestParam(required = false) Integer limit,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions) {
        Permissions.require(permissions, AUDIT_READ);
        int size = limit == null ? 50 : Math.min(Math.max(limit, 1), 200);
        return ApiResponse.ok(repo.listAudit(size));
    }
}
