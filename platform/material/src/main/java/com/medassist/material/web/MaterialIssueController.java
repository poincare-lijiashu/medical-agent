package com.medassist.material.web;

import com.medassist.common.api.ApiResponse;
import com.medassist.common.security.AuthHeaders;
import com.medassist.common.security.DataScope;
import com.medassist.common.security.Permissions;
import com.medassist.material.service.ConsumableIssueService;
import com.medassist.material.service.MaterialAccessGuard;
import com.medassist.material.web.MaterialDtos.CreateIssueReq;
import com.medassist.material.web.MaterialDtos.IssueView;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 耗材出库 API（库房 → 科室 / 手术间 / 门诊）。
 *
 * <p>权限：{@code material:issue:write}（护士/物资员请领出库）；查询用 {@code material:read}。
 * 出库是库存唯一扣减入口，追溯登记不扣库存。
 */
@RestController
@RequestMapping("/material/issues")
public class MaterialIssueController {

    private static final String WRITE = "material:issue:write";
    private static final String READ = "material:read";

    private final ConsumableIssueService service;
    private final MaterialAccessGuard guard;

    public MaterialIssueController(ConsumableIssueService service, MaterialAccessGuard guard) {
        this.service = service;
        this.guard = guard;
    }

    /** 出库：requestId 为幂等键，重试不会重复扣库存。 */
    @PostMapping
    public ApiResponse<IssueView> issue(
            @Valid @RequestBody CreateIssueReq req,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(value = AuthHeaders.ACTOR, defaultValue = "system") String actor,
            DataScope scope) {
        Permissions.require(permissions, WRITE);
        return ApiResponse.ok(service.issue(req, scope.campusForWrite(), actor));
    }

    @GetMapping
    public ApiResponse<List<IssueView>> list(
            @RequestParam(required = false) UUID deptId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(required = false) Integer limit,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            DataScope scope) {
        Permissions.require(permissions, READ);
        return ApiResponse.ok(service.list(deptId, from, to, scope.campusFilter(), limit));
    }

    @GetMapping("/{id}")
    public ApiResponse<IssueView> detail(
            @PathVariable UUID id,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            DataScope scope) {
        Permissions.require(permissions, READ);
        guard.requireIssue(scope, id);
        return ApiResponse.ok(service.detail(id));
    }
}
