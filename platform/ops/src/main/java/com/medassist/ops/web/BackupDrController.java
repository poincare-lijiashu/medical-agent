package com.medassist.ops.web;

import com.medassist.common.api.ApiResponse;
import com.medassist.common.api.PageResult;
import com.medassist.common.security.AuthHeaders;
import com.medassist.common.security.Permissions;
import com.medassist.ops.domain.OpsModel.BackupPolicy;
import com.medassist.ops.domain.OpsModel.BackupRun;
import com.medassist.ops.domain.OpsModel.DrDrill;
import com.medassist.ops.service.BackupDrService;
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
 * 备份与灾备演练 API（§297）。
 *
 * <p>权限切分（auth V105）：
 * <ul>
 *   <li>{@code ops:backup:read}：备份策略与运行记录查询；</li>
 *   <li>{@code ops:backup:manage}：维护备份策略、上报备份运行；</li>
 *   <li>{@code ops:dr:read}：演练与门禁查询；</li>
 *   <li>{@code ops:dr:manage}：创建/开始/更新步骤/判失败/中止；</li>
 *   <li>{@code ops:dr:verify}：判定通过（复核人，不能是执行人）。</li>
 * </ul>
 *
 * <p>操作人来自网关注入的 {@code X-Actor}，不接受请求体自报身份。
 */
@RestController
@RequestMapping("/ops")
public class BackupDrController {

    private static final String BACKUP_READ = "ops:backup:read";
    private static final String BACKUP_MANAGE = "ops:backup:manage";
    private static final String DR_READ = "ops:dr:read";
    private static final String DR_MANAGE = "ops:dr:manage";
    private static final String DR_VERIFY = "ops:dr:verify";

    private final BackupDrService service;

    public BackupDrController(BackupDrService service) {
        this.service = service;
    }

    // ------------------------------------------------------------------ 备份

    @GetMapping("/backup/policies")
    public ApiResponse<List<BackupPolicy>> policies(
            @RequestParam(defaultValue = "true") boolean onlyActive,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions) {
        Permissions.require(permissions, BACKUP_READ);
        return ApiResponse.ok(service.listPolicies(onlyActive));
    }

    @PostMapping("/backup/policies")
    public ApiResponse<BackupPolicy> upsertPolicy(
            @Valid @RequestBody OpsDtos.BackupPolicyReq req,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(value = AuthHeaders.ACTOR, required = false) String actor,
            @RequestHeader(value = AuthHeaders.CLIENT_IP, required = false) String clientIp,
            @RequestHeader(value = AuthHeaders.TRACE_ID, required = false) String traceId) {
        Permissions.require(permissions, BACKUP_MANAGE);
        return ApiResponse.ok(service.upsertPolicy(req, actor, clientIp, traceId));
    }

    @GetMapping("/backup/runs")
    public ApiResponse<PageResult<BackupRun>> runs(
            @RequestParam(required = false) String policyCode,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions) {
        Permissions.require(permissions, BACKUP_READ);
        int safePage = Math.max(page, 1);
        int safeSize = Math.min(Math.max(size, 1), PageResult.MAX_PAGE_SIZE);
        long total = service.countRuns(policyCode, status);
        List<BackupRun> items = service.listRuns(policyCode, status, safeSize,
                (safePage - 1) * safeSize);
        return ApiResponse.ok(PageResult.of(safePage, safeSize, total, items));
    }

    /** 备份平台上报（幂等键由上报方生成，重试必须复用）。 */
    @PostMapping("/backup/runs")
    public ApiResponse<BackupRun> reportRun(
            @Valid @RequestBody OpsDtos.BackupRunReq req,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(value = AuthHeaders.ACTOR, required = false) String actor,
            @RequestHeader(value = AuthHeaders.CLIENT_IP, required = false) String clientIp,
            @RequestHeader(value = AuthHeaders.TRACE_ID, required = false) String traceId) {
        Permissions.require(permissions, BACKUP_MANAGE);
        return ApiResponse.ok(service.reportRun(req, actor, clientIp, traceId));
    }

    // ------------------------------------------------------------------ 演练

    @GetMapping("/dr/drills")
    public ApiResponse<PageResult<DrDrill>> drills(
            @RequestParam(required = false) String scope,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions) {
        Permissions.require(permissions, DR_READ);
        int safePage = Math.max(page, 1);
        int safeSize = Math.min(Math.max(size, 1), PageResult.MAX_PAGE_SIZE);
        long total = service.countDrills(scope, status);
        List<DrDrill> items = service.listDrills(scope, status, safeSize, (safePage - 1) * safeSize);
        return ApiResponse.ok(PageResult.of(safePage, safeSize, total, items));
    }

    @PostMapping("/dr/drills")
    public ApiResponse<DrDrill> createDrill(
            @Valid @RequestBody OpsDtos.DrDrillCreateReq req,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(value = AuthHeaders.ACTOR, required = false) String actor,
            @RequestHeader(value = AuthHeaders.CLIENT_IP, required = false) String clientIp,
            @RequestHeader(value = AuthHeaders.TRACE_ID, required = false) String traceId) {
        Permissions.require(permissions, DR_MANAGE);
        return ApiResponse.ok(service.createDrill(req, actor, clientIp, traceId));
    }

    @GetMapping("/dr/drills/{id}")
    public ApiResponse<DrDrill> drill(
            @PathVariable UUID id,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions) {
        Permissions.require(permissions, DR_READ);
        return ApiResponse.ok(service.detail(id));
    }

    @PostMapping("/dr/drills/{id}/start")
    public ApiResponse<DrDrill> start(
            @PathVariable UUID id,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(value = AuthHeaders.ACTOR, required = false) String actor,
            @RequestHeader(value = AuthHeaders.CLIENT_IP, required = false) String clientIp,
            @RequestHeader(value = AuthHeaders.TRACE_ID, required = false) String traceId) {
        Permissions.require(permissions, DR_MANAGE);
        return ApiResponse.ok(service.start(id, actor, clientIp, traceId));
    }

    @PostMapping("/dr/drills/{id}/steps/{stepNo}")
    public ApiResponse<DrDrill> updateStep(
            @PathVariable UUID id,
            @PathVariable int stepNo,
            @Valid @RequestBody OpsDtos.DrDrillStepUpdateReq req,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(value = AuthHeaders.ACTOR, required = false) String actor,
            @RequestHeader(value = AuthHeaders.CLIENT_IP, required = false) String clientIp,
            @RequestHeader(value = AuthHeaders.TRACE_ID, required = false) String traceId) {
        Permissions.require(permissions, DR_MANAGE);
        return ApiResponse.ok(service.updateStep(id, stepNo, req, actor, clientIp, traceId));
    }

    /** 判定通过：需要复核权限，且复核人不能是执行人（服务层强制四眼）。 */
    @PostMapping("/dr/drills/{id}/complete")
    public ApiResponse<DrDrill> complete(
            @PathVariable UUID id,
            @Valid @RequestBody OpsDtos.DrDrillCompleteReq req,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(value = AuthHeaders.ACTOR, required = false) String actor,
            @RequestHeader(value = AuthHeaders.CLIENT_IP, required = false) String clientIp,
            @RequestHeader(value = AuthHeaders.TRACE_ID, required = false) String traceId) {
        Permissions.require(permissions, DR_VERIFY);
        return ApiResponse.ok(service.complete(id, req, actor, clientIp, traceId));
    }

    @PostMapping("/dr/drills/{id}/fail")
    public ApiResponse<DrDrill> fail(
            @PathVariable UUID id,
            @Valid @RequestBody OpsDtos.DrDrillReasonReq req,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(value = AuthHeaders.ACTOR, required = false) String actor,
            @RequestHeader(value = AuthHeaders.CLIENT_IP, required = false) String clientIp,
            @RequestHeader(value = AuthHeaders.TRACE_ID, required = false) String traceId) {
        Permissions.require(permissions, DR_MANAGE);
        return ApiResponse.ok(service.fail(id, req.reason(), actor, clientIp, traceId));
    }

    @PostMapping("/dr/drills/{id}/abort")
    public ApiResponse<DrDrill> abort(
            @PathVariable UUID id,
            @Valid @RequestBody OpsDtos.DrDrillReasonReq req,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(value = AuthHeaders.ACTOR, required = false) String actor,
            @RequestHeader(value = AuthHeaders.CLIENT_IP, required = false) String clientIp,
            @RequestHeader(value = AuthHeaders.TRACE_ID, required = false) String traceId) {
        Permissions.require(permissions, DR_MANAGE);
        return ApiResponse.ok(service.abort(id, req.reason(), actor, clientIp, traceId));
    }

    /** 发布门禁自检：给定范围/环境，返回最近有效期内已复核的通过记录。 */
    @GetMapping("/dr/gate")
    public ApiResponse<Map<String, Object>> gate(
            @RequestParam String scope,
            @RequestParam(defaultValue = "PROD") String environment,
            @RequestParam(defaultValue = "90") int days,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions) {
        Permissions.require(permissions, DR_READ);
        return ApiResponse.ok(service.gate(scope, environment, days));
    }
}
