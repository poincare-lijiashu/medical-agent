package com.medassist.pharmacy.web;

import com.medassist.common.api.ApiResponse;
import com.medassist.common.security.AuthHeaders;
import com.medassist.common.security.DataScope;
import com.medassist.common.security.Permissions;
import com.medassist.pharmacy.domain.PharmacyModel.Prescription;
import com.medassist.pharmacy.service.PrescriptionService;
import com.medassist.pharmacy.web.PharmacyDtos.CreatePrescriptionReq;
import com.medassist.pharmacy.web.PharmacyDtos.DispenseReq;
import com.medassist.pharmacy.web.PharmacyDtos.DispenseView;
import com.medassist.pharmacy.web.PharmacyDtos.PrescriptionAuditView;
import com.medassist.pharmacy.web.PharmacyDtos.PrescriptionView;
import com.medassist.pharmacy.web.PharmacyDtos.ReturnReq;
import com.medassist.pharmacy.web.PharmacyDtos.ReviewReq;
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
 * 处方签发、审核、发药与退药 API。
 *
 * <p>权限划分（岗位隔离）：
 * <ul>
 *   <li>{@code pharmacy:prescription:create}——医师开方；</li>
 *   <li>{@code pharmacy:prescription:review}——药师审核（通过/驳回）；</li>
 *   <li>{@code pharmacy:dispense:execute}——药师发药；</li>
 *   <li>{@code pharmacy:dispense:return}——药师退药；</li>
 *   <li>{@code pharmacy:read}——查询。</li>
 * </ul>
 * 审核与发药分开授权，是为了满足"开方-审核-调配"三权分立的药事管理要求。
 */
@RestController
@RequestMapping("/pharmacy/prescriptions")
public class PrescriptionController {

    private static final String CREATE = "pharmacy:prescription:create";
    private static final String REVIEW = "pharmacy:prescription:review";
    private static final String DISPENSE = "pharmacy:dispense:execute";
    private static final String RETURN = "pharmacy:dispense:return";
    private static final String READ = "pharmacy:read";

    private final PrescriptionService service;
    private final com.medassist.pharmacy.service.PrescriptionAccessGuard guard;

    public PrescriptionController(PrescriptionService service,
                                  com.medassist.pharmacy.service.PrescriptionAccessGuard guard) {
        this.service = service;
        this.guard = guard;
    }

    @PostMapping
    public ApiResponse<PrescriptionView> create(
            @Valid @RequestBody CreatePrescriptionReq req,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(value = AuthHeaders.ACTOR, defaultValue = "system") String actor,
            DataScope scope) {
        Permissions.require(permissions, CREATE);
        return ApiResponse.ok(PhiMask.view(service.create(req, actor, scope.campusForWrite()), scope));
    }

    @GetMapping
    public ApiResponse<List<Prescription>> list(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) UUID patientId,
            @RequestParam(required = false) Integer limit,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            DataScope scope) {
        Permissions.require(permissions, READ);
        return ApiResponse.ok(service.list(status, patientId, limit, scope.campusFilter()).stream()
                .map(p -> PhiMask.prescription(p, scope))
                .toList());
    }

    /**
     * 各状态处方数量（P0-F4 工作台 KPI）。
     *
     * <p>与列表分开：列表按状态分页取（可能被 200 条上限截断），
     * 计数必须走聚合才能准确；前端由此只需「1 次计数 + 1 次当前页签」两次请求。
     */
    @GetMapping("/summary")
    public ApiResponse<java.util.Map<String, Integer>> summary(
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            DataScope scope) {
        Permissions.require(permissions, READ);
        return ApiResponse.ok(service.statusCounts(scope.campusFilter()));
    }

    @GetMapping("/{id}")
    public ApiResponse<PrescriptionView> detail(
            @PathVariable UUID id,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            DataScope scope) {
        Permissions.require(permissions, READ);
        guard.require(scope, id);
        return ApiResponse.ok(PhiMask.view(service.detail(id), scope));
    }

    /** 审核：approve=true 通过；false 驳回（必须写理由）。存在 BLOCK 级审方问题时不能通过。 */
    @PostMapping("/{id}/review")
    public ApiResponse<PrescriptionView> review(
            @PathVariable UUID id,
            @Valid @RequestBody ReviewReq req,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(value = AuthHeaders.ACTOR, defaultValue = "system") String actor,
            DataScope scope) {
        Permissions.require(permissions, REVIEW);
        guard.require(scope, id);
        return ApiResponse.ok(PhiMask.view(service.review(id, req.approve(), req.reason(), actor), scope));
    }

    /** 审方记录（开方/审核两次评估的规则命中，供药师端展示与追溯）。 */
    @GetMapping("/{id}/audit")
    public ApiResponse<PrescriptionAuditView> audit(
            @PathVariable UUID id,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            DataScope scope) {
        Permissions.require(permissions, READ);
        guard.require(scope, id);
        return ApiResponse.ok(service.auditView(id));
    }

    /** 发药（按近效期优先扣批次；麻醉/精神类必须提供核对人）。 */
    @PostMapping("/{id}/dispense")
    public ApiResponse<DispenseView> dispense(
            @PathVariable UUID id,
            @Valid @RequestBody(required = false) DispenseReq req,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(value = AuthHeaders.ACTOR, defaultValue = "system") String actor,
            DataScope scope) {
        Permissions.require(permissions, DISPENSE);
        guard.require(scope, id);
        return ApiResponse.ok(service.dispense(id, req == null ? null : req.checker(), actor));
    }

    /**
     * 按处方查发药单（含批次明细与退药记录）。
     *
     * <p>未发药返回空数据；已发药时前端据此展示退药入口，
     * 不需要调用方自行保存发药单号。
     */
    @GetMapping("/{id}/dispense")
    public ApiResponse<DispenseView> dispenseOf(
            @PathVariable UUID id,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            DataScope scope) {
        Permissions.require(permissions, READ);
        guard.require(scope, id);
        return ApiResponse.ok(service.dispenseByPrescription(id));
    }
}
