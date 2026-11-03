package com.medassist.emr.web;

import com.medassist.common.api.ApiResponse;
import com.medassist.common.api.ErrorCode;
import com.medassist.common.exception.BusinessException;
import com.medassist.common.security.AuthHeaders;
import com.medassist.common.security.DataScope;
import com.medassist.common.security.Permissions;
import com.medassist.emr.domain.EmrModel.HomepageSummary;
import com.medassist.emr.service.EmrAccessGuard;
import com.medassist.emr.service.HomepageService;
import com.medassist.emr.web.EmrDtos.CreateHomepageReq;
import com.medassist.emr.web.EmrDtos.HomepageView;
import com.medassist.emr.web.EmrDtos.UpdateHomepageReq;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * 病案首页 API。
 *
 * <p>状态机：DRAFT →（补录+完整性校验）→ SUBMITTED →（四眼归档）→ ARCHIVED。
 * 生成时从临床域取回诊断/费用/出院时间快照，提交后不可修改。
 */
@RestController
@RequestMapping("/emr/homepages")
public class HomepageController {

    private static final String READ = "emr:homepage:read";
    private static final String WRITE = "emr:homepage:write";
    private static final String ARCHIVE = "emr:homepage:archive";

    private final HomepageService service;
    private final EmrAccessGuard guard;

    public HomepageController(HomepageService service, EmrAccessGuard guard) {
        this.service = service;
        this.guard = guard;
    }

    /** 生成首页（仅已出院住院就诊；同一就诊只能生成一份）。 */
    @PostMapping
    public ApiResponse<HomepageView> create(
            @Valid @RequestBody CreateHomepageReq req,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(value = AuthHeaders.ACTOR, defaultValue = "system") String actor,
            DataScope scope) {
        Permissions.require(permissions, WRITE);
        return ApiResponse.ok(service.create(req, actor, scope));
    }

    /**
     * 列表：只返回摘要（姓名掩码、不含诊断/费用明细），并写 HOMEPAGE_QUERY 审计。
     *
     * <p>数据范围：显式传入的 {@code deptId} 必须可见；未传时按当前账号的科室过滤，
     * 避免病案室以外的人用"不传 deptId"绕过科室隔离拉全院首页。
     */
    @GetMapping
    public ApiResponse<List<HomepageSummary>> list(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) UUID patientId,
            @RequestParam(required = false) UUID deptId,
            @RequestParam(required = false) Integer limit,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(value = AuthHeaders.ACTOR, defaultValue = "system") String actor,
            DataScope scope) {
        Permissions.require(permissions, READ);
        UUID filterDept = deptId;
        if (!scope.allDepartments()) {
            if (deptId != null && !scope.canSeeDepartment(deptId)) {
                throw new BusinessException(ErrorCode.EMR_HOMEPAGE_NOT_FOUND);
            }
            if (deptId == null && scope.deptCodes().size() == 1) {
                filterDept = scope.deptCodes().iterator().next();
            }
        }
        return ApiResponse.ok(service.list(status, patientId, filterDept, limit, actor).stream()
                .filter(row -> scope.canSeeDepartment(row.deptId()))
                .toList());
    }

    /** 详情：整份 PHI，必须留痕（HOMEPAGE_VIEW）。 */
    @GetMapping("/{id}")
    public ApiResponse<HomepageView> detail(
            @PathVariable UUID id,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(value = AuthHeaders.ACTOR, defaultValue = "system") String actor,
            DataScope scope) {
        Permissions.require(permissions, READ);
        guard.requireHomepage(scope, id);
        return ApiResponse.ok(service.viewDetail(id, actor));
    }

    /** 打印视图（HTML，浏览器打印 → 另存为 PDF）；未归档带水印，打印行为留痕。 */
    @GetMapping(value = "/{id}/print", produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> print(
            @PathVariable UUID id,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(value = AuthHeaders.ACTOR, defaultValue = "system") String actor,
            DataScope scope) {
        Permissions.require(permissions, READ);
        guard.requireHomepage(scope, id);
        // 整页 PHI：禁脚本、禁外链、禁缓存
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("text/html; charset=UTF-8"))
                .header("Content-Security-Policy", "default-src 'none'; style-src 'unsafe-inline'")
                .header("X-Content-Type-Options", "nosniff")
                .header("Cache-Control", "no-store, no-cache, must-revalidate, private")
                .header("Pragma", "no-cache")
                .body(service.printHtml(id, actor));
    }

    /** 补录首页专有信息（仅草稿；必须带版本号）。 */
    @PutMapping("/{id}")
    public ApiResponse<HomepageView> update(
            @PathVariable UUID id,
            @Valid @RequestBody UpdateHomepageReq req,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(value = AuthHeaders.ACTOR, defaultValue = "system") String actor,
            DataScope scope) {
        Permissions.require(permissions, WRITE);
        guard.requireHomepage(scope, id);
        return ApiResponse.ok(service.update(id, req, actor));
    }

    /** 重新生成快照（仅草稿；出院后退费/补录诊断导致快照过期时使用）。 */
    @PostMapping("/{id}/recalculate")
    public ApiResponse<HomepageView> recalculate(
            @PathVariable UUID id,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(value = AuthHeaders.ACTOR, defaultValue = "system") String actor,
            DataScope scope) {
        Permissions.require(permissions, WRITE);
        guard.requireHomepage(scope, id);
        return ApiResponse.ok(service.recalculate(id, actor));
    }

    /** 提交（完整性校验不通过返回 409 与缺失项明细）。 */
    @PostMapping("/{id}/submit")
    public ApiResponse<HomepageView> submit(
            @PathVariable UUID id,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(value = AuthHeaders.ACTOR, required = false) String actor,
            @RequestHeader(value = AuthHeaders.USER_ID, required = false) String userId,
            DataScope scope) {
        Permissions.require(permissions, WRITE);
        guard.requireHomepage(scope, id);
        return ApiResponse.ok(service.submit(id, actor, parseUuid(userId)));
    }

    /** 归档（终态；提交人不得归档自己的首页，按 X-User-Id 判定）。 */
    @PostMapping("/{id}/archive")
    public ApiResponse<HomepageView> archive(
            @PathVariable UUID id,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(value = AuthHeaders.ACTOR, required = false) String actor,
            @RequestHeader(value = AuthHeaders.USER_ID, required = false) String userId,
            DataScope scope) {
        Permissions.require(permissions, ARCHIVE);
        guard.requireHomepage(scope, id);
        return ApiResponse.ok(service.archive(id, actor, parseUuid(userId)));
    }

    /** 身份头缺失或非法一律按"没有身份"处理，由服务层 fail-closed 拒绝。 */
    private static UUID parseUuid(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(raw.trim());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
