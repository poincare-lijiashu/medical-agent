package com.medassist.pharmacy.web;

import com.medassist.common.api.ApiResponse;
import com.medassist.common.security.AuthHeaders;
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

    public PrescriptionController(PrescriptionService service) {
        this.service = service;
    }

    @PostMapping
    public ApiResponse<PrescriptionView> create(
            @Valid @RequestBody CreatePrescriptionReq req,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(value = AuthHeaders.ACTOR, defaultValue = "system") String actor) {
        Permissions.require(permissions, CREATE);
        return ApiResponse.ok(service.create(req, actor));
    }

    @GetMapping
    public ApiResponse<List<Prescription>> list(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) UUID patientId,
            @RequestParam(required = false) Integer limit,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions) {
        Permissions.require(permissions, READ);
        return ApiResponse.ok(service.list(status, patientId, limit));
    }

    @GetMapping("/{id}")
    public ApiResponse<PrescriptionView> detail(
            @PathVariable UUID id,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions) {
        Permissions.require(permissions, READ);
        return ApiResponse.ok(service.detail(id));
    }

    /** 审核：approve=true 通过；false 驳回（必须写理由）。存在 BLOCK 级审方问题时不能通过。 */
    @PostMapping("/{id}/review")
    public ApiResponse<PrescriptionView> review(
            @PathVariable UUID id,
            @Valid @RequestBody ReviewReq req,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(value = AuthHeaders.ACTOR, defaultValue = "system") String actor) {
        Permissions.require(permissions, REVIEW);
        return ApiResponse.ok(service.review(id, req.approve(), req.reason(), actor));
    }

    /** 审方记录（开方/审核两次评估的规则命中，供药师端展示与追溯）。 */
    @GetMapping("/{id}/audit")
    public ApiResponse<PrescriptionAuditView> audit(
            @PathVariable UUID id,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions) {
        Permissions.require(permissions, READ);
        return ApiResponse.ok(service.auditView(id));
    }

    /** 发药（按近效期优先扣批次；麻醉/精神类必须提供核对人）。 */
    @PostMapping("/{id}/dispense")
    public ApiResponse<DispenseView> dispense(
            @PathVariable UUID id,
            @Valid @RequestBody(required = false) DispenseReq req,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(value = AuthHeaders.ACTOR, defaultValue = "system") String actor) {
        Permissions.require(permissions, DISPENSE);
        return ApiResponse.ok(service.dispense(id, req == null ? null : req.checker(), actor));
    }
}
