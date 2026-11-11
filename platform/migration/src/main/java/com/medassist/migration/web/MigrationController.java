package com.medassist.migration.web;

import com.medassist.common.api.ApiResponse;
import com.medassist.common.security.AuthHeaders;
import com.medassist.common.security.Permissions;
import com.medassist.migration.domain.MigrationModel.AuditLog;
import com.medassist.migration.domain.MigrationModel.Batch;
import com.medassist.migration.domain.MigrationModel.MappingProfile;
import com.medassist.migration.domain.MigrationModel.TargetDetail;
import com.medassist.migration.domain.MigrationRequests.PreviewReq;
import com.medassist.migration.domain.MigrationRequests.ProfileReq;
import com.medassist.migration.domain.MigrationRequests.RollbackReq;
import com.medassist.migration.service.MigrationService;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 历史数据迁移 API。
 *
 * <p>权限切分（auth V88）：
 * <ul>
 *   <li>{@code migration:read}：目标/模板/批次/对账/审计查询；</li>
 *   <li>{@code migration:write}：维护映射档案、上传并预检（只读预检，不写业务表）；</li>
 *   <li>{@code migration:apply}：应用批次（真正写业务历史数据）；</li>
 *   <li>{@code migration:rollback}：回滚批次；</li>
 *   <li>{@code migration:audit:read}：迁移审计。</li>
 * </ul>
 *
 * <p>所有写操作都要求真实操作人（{@code X-Actor}），缺失时 403。
 * 迁移属于高风险操作，"匿名导入"无法追责，因此连预检都要求操作人。
 */
@RestController
@RequestMapping("/migration")
public class MigrationController {

    private static final String READ = "migration:read";
    private static final String WRITE = "migration:write";
    private static final String APPLY = "migration:apply";
    private static final String ROLLBACK = "migration:rollback";
    private static final String AUDIT_READ = "migration:audit:read";

    private final MigrationService service;

    public MigrationController(MigrationService service) {
        this.service = service;
    }

    // ------------------------------------------------------------------ 目标与模板

    @GetMapping("/targets")
    public ApiResponse<List<TargetDetail>> targets(
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions) {
        Permissions.require(permissions, READ);
        return ApiResponse.ok(service.listTargets());
    }

    @GetMapping("/targets/{code}")
    public ApiResponse<TargetDetail> target(
            @PathVariable String code,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions) {
        Permissions.require(permissions, READ);
        return ApiResponse.ok(service.target(code));
    }

    /** 下载模板（UTF-8 BOM + CRLF，Excel 双击打开中文不乱码）。 */
    @GetMapping(value = "/targets/{code}/template", produces = "text/csv;charset=UTF-8")
    public ResponseEntity<byte[]> template(
            @PathVariable String code,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions) {
        Permissions.require(permissions, READ);
        byte[] body = service.template(code).getBytes(StandardCharsets.UTF_8);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"template_" + code + ".csv\"")
                .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .body(body);
    }

    // ------------------------------------------------------------------ 映射档案

    @GetMapping("/mapping-profiles")
    public ApiResponse<List<MappingProfile>> profiles(
            @RequestParam(required = false) String targetCode,
            @RequestParam(required = false) String sourceSystem,
            @RequestParam(required = false) Integer limit,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions) {
        Permissions.require(permissions, READ);
        return ApiResponse.ok(service.listProfiles(targetCode, sourceSystem, limit));
    }

    @PostMapping("/mapping-profiles")
    public ApiResponse<MappingProfile> createProfile(
            @Valid @RequestBody ProfileReq req,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(value = AuthHeaders.ACTOR, required = false) String actor,
            @RequestHeader(value = AuthHeaders.CLIENT_IP, required = false) String clientIp,
            @RequestHeader(value = AuthHeaders.TRACE_ID, required = false) String traceId) {
        Permissions.require(permissions, WRITE);
        return ApiResponse.ok(service.createProfile(req, actor, clientIp, traceId));
    }

    @PostMapping("/mapping-profiles/{profileCode}/retire")
    public ApiResponse<MappingProfile> retireProfile(
            @PathVariable String profileCode,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(value = AuthHeaders.ACTOR, required = false) String actor,
            @RequestHeader(value = AuthHeaders.CLIENT_IP, required = false) String clientIp,
            @RequestHeader(value = AuthHeaders.TRACE_ID, required = false) String traceId) {
        Permissions.require(permissions, WRITE);
        return ApiResponse.ok(service.retireProfile(profileCode, actor, clientIp, traceId));
    }

    // ------------------------------------------------------------------ 批次

    @PostMapping("/batches/preview")
    public ApiResponse<Batch> preview(
            @Valid @RequestBody PreviewReq req,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(value = AuthHeaders.ACTOR, required = false) String actor,
            @RequestHeader(value = AuthHeaders.CLIENT_IP, required = false) String clientIp,
            @RequestHeader(value = AuthHeaders.TRACE_ID, required = false) String traceId) {
        Permissions.require(permissions, WRITE);
        return ApiResponse.ok(service.preview(req, actor, clientIp, traceId));
    }

    @GetMapping("/batches")
    public ApiResponse<List<Batch>> batches(
            @RequestParam(required = false) String targetCode,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Integer limit,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions) {
        Permissions.require(permissions, READ);
        return ApiResponse.ok(service.listBatches(targetCode, status, limit));
    }

    @GetMapping("/batches/{id}")
    public ApiResponse<Batch> batch(
            @PathVariable UUID id,
            @RequestParam(required = false) Integer limit,
            @RequestParam(required = false) Integer offset,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions) {
        Permissions.require(permissions, READ);
        return ApiResponse.ok(service.detail(id, limit, offset));
    }

    @PostMapping("/batches/{id}/apply")
    public ApiResponse<Batch> apply(
            @PathVariable UUID id,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(value = AuthHeaders.ACTOR, required = false) String actor,
            @RequestHeader(value = AuthHeaders.CLIENT_IP, required = false) String clientIp,
            @RequestHeader(value = AuthHeaders.TRACE_ID, required = false) String traceId) {
        Permissions.require(permissions, APPLY);
        return ApiResponse.ok(service.apply(id, actor, clientIp, traceId));
    }

    /** 回滚一个或多个批次（自动按依赖逆序；冲突时整批拒绝）。 */
    @PostMapping("/batches/rollback")
    public ApiResponse<List<Batch>> rollback(
            @Valid @RequestBody RollbackReq req,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(value = AuthHeaders.ACTOR, required = false) String actor,
            @RequestHeader(value = AuthHeaders.CLIENT_IP, required = false) String clientIp,
            @RequestHeader(value = AuthHeaders.TRACE_ID, required = false) String traceId) {
        Permissions.require(permissions, ROLLBACK);
        return ApiResponse.ok(service.rollback(req.batchNos(), req.reason(), actor, clientIp, traceId));
    }

    @GetMapping("/batches/{id}/reconciliation")
    public ApiResponse<Map<String, Object>> reconciliation(
            @PathVariable UUID id,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions) {
        Permissions.require(permissions, READ);
        return ApiResponse.ok(service.reconciliation(id));
    }

    // ------------------------------------------------------------------ 审计

    @GetMapping("/audit")
    public ApiResponse<List<AuditLog>> audit(
            @RequestParam(required = false) Integer limit,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions) {
        Permissions.require(permissions, AUDIT_READ);
        return ApiResponse.ok(service.audit(limit));
    }
}
