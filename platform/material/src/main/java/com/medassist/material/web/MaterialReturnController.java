package com.medassist.material.web;

import com.medassist.common.api.ApiResponse;
import com.medassist.common.security.AuthHeaders;
import com.medassist.common.security.DataScope;
import com.medassist.common.security.Permissions;
import com.medassist.material.domain.MaterialModel.ConsumableReturn;
import com.medassist.material.domain.MaterialModel.DeptConsumableStock;
import com.medassist.material.service.ConsumableReturnService;
import com.medassist.material.service.MaterialAccessGuard;
import com.medassist.material.web.MaterialReturnDtos.ConsumableReturnView;
import com.medassist.material.web.MaterialReturnDtos.CreateReturnReq;
import com.medassist.material.web.MaterialReturnDtos.DeptConsumableStockView;
import com.medassist.material.web.MaterialReturnDtos.ReturnReasonReq;
import com.medassist.material.web.MaterialReturnDtos.ReviewReturnReq;
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
 * 耗材未使用退回 API（科室二级库 → 中心库房）。
 *
 * <p>权限：查询 {@code material:read}；建单/撤回 {@code material:return:write}；
 * 审批/驳回 {@code material:return:approve}（四眼由服务层强制，审批人≠申请人）。
 */
@RestController
@RequestMapping("/material")
public class MaterialReturnController {

    private static final String READ = "material:read";
    private static final String WRITE = "material:return:write";
    private static final String APPROVE = "material:return:approve";

    private final ConsumableReturnService service;
    private final MaterialAccessGuard guard;

    public MaterialReturnController(ConsumableReturnService service, MaterialAccessGuard guard) {
        this.service = service;
        this.guard = guard;
    }

    @GetMapping("/returns")
    public ApiResponse<List<ConsumableReturn>> list(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) UUID deptId,
            @RequestParam(required = false) Integer limit,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            DataScope scope) {
        Permissions.require(permissions, READ);
        return ApiResponse.ok(service.list(status, deptId, scope.campusFilter(), limit));
    }

    @GetMapping("/returns/{id}")
    public ApiResponse<ConsumableReturnView> detail(
            @PathVariable UUID id,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            DataScope scope) {
        Permissions.require(permissions, READ);
        guard.requireReturn(scope, id);
        return ApiResponse.ok(service.detail(id));
    }

    /** 建单：按科室二级库余额预校验，不扣库存。 */
    @PostMapping("/returns")
    public ApiResponse<ConsumableReturnView> create(
            @Valid @RequestBody CreateReturnReq req,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(value = AuthHeaders.ACTOR, defaultValue = "system") String actor,
            DataScope scope) {
        Permissions.require(permissions, WRITE);
        return ApiResponse.ok(service.create(req, scope.campusForWrite(), actor));
    }

    /** 审批通过：扣科室二级库并回冲中心批次。 */
    @PostMapping("/returns/{id}/approve")
    public ApiResponse<ConsumableReturnView> approve(
            @PathVariable UUID id,
            @RequestBody(required = false) ReviewReturnReq req,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(value = AuthHeaders.ACTOR, defaultValue = "system") String actor,
            DataScope scope) {
        Permissions.require(permissions, APPROVE);
        guard.requireReturn(scope, id);
        return ApiResponse.ok(service.approve(id, req, actor));
    }

    /** 驳回：必须写原因，不动库存。 */
    @PostMapping("/returns/{id}/reject")
    public ApiResponse<ConsumableReturnView> reject(
            @PathVariable UUID id,
            @Valid @RequestBody ReturnReasonReq req,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(value = AuthHeaders.ACTOR, defaultValue = "system") String actor,
            DataScope scope) {
        Permissions.require(permissions, APPROVE);
        guard.requireReturn(scope, id);
        return ApiResponse.ok(service.reject(id, req, actor));
    }

    /** 撤回：仅未审批（SUBMITTED）可撤回。 */
    @PostMapping("/returns/{id}/cancel")
    public ApiResponse<ConsumableReturnView> cancel(
            @PathVariable UUID id,
            @Valid @RequestBody ReturnReasonReq req,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(value = AuthHeaders.ACTOR, defaultValue = "system") String actor,
            DataScope scope) {
        Permissions.require(permissions, WRITE);
        guard.requireReturn(scope, id);
        return ApiResponse.ok(service.cancel(id, req, actor));
    }

    /** 科室二级库余额。 */
    @GetMapping("/dept-stocks")
    public ApiResponse<DeptConsumableStockView> deptStock(
            @RequestParam UUID deptId,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) Integer limit,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions) {
        Permissions.require(permissions, READ);
        List<DeptConsumableStock> items = service.deptStock(deptId, keyword, limit);
        return ApiResponse.ok(new DeptConsumableStockView(deptId, items));
    }
}
