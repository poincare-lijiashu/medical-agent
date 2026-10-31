package com.medassist.clinical.web;

import com.medassist.clinical.domain.ClinicalModel.Bed;
import com.medassist.clinical.domain.ClinicalModel.Ward;
import com.medassist.clinical.service.InpatientService;
import com.medassist.clinical.web.ClinicalDtos.AdmissionView;
import com.medassist.clinical.web.ClinicalDtos.AdmitReq;
import com.medassist.clinical.web.ClinicalDtos.AssignBedReq;
import com.medassist.clinical.web.ClinicalDtos.CreateBedsReq;
import com.medassist.clinical.web.ClinicalDtos.CreateWardReq;
import com.medassist.clinical.web.ClinicalDtos.DepositReq;
import com.medassist.clinical.web.ClinicalDtos.DepositView;
import com.medassist.common.api.ApiResponse;
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
 * 住院 ADT API。
 *
 * <p>权限点：
 * <ul>
 *   <li>{@code inpatient:ward:manage}——病区与床位维护（护理部/管理员）；</li>
 *   <li>{@code inpatient:admission:create}——入院登记（住院处/医师）；</li>
 *   <li>{@code inpatient:bed:assign}——分床/转床（护理部）；</li>
 *   <li>{@code inpatient:admission:discharge}——出院办理（医师/住院处）；</li>
 *   <li>{@code inpatient:read}——住院信息查询。</li>
 * </ul>
 */
@RestController
@RequestMapping("/clinical")
public class InpatientController {

    private static final String WARD_MANAGE = "inpatient:ward:manage";
    private static final String ADMIT = "inpatient:admission:create";
    private static final String BED_ASSIGN = "inpatient:bed:assign";
    private static final String DISCHARGE = "inpatient:admission:discharge";
    private static final String DEPOSIT_MANAGE = "inpatient:deposit:manage";
    private static final String READ = "inpatient:read";

    private final InpatientService service;

    public InpatientController(InpatientService service) {
        this.service = service;
    }

    @PostMapping("/wards")
    public ApiResponse<Ward> createWard(
            @Valid @RequestBody CreateWardReq req,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(value = AuthHeaders.ACTOR, defaultValue = "system") String actor) {
        Permissions.require(permissions, WARD_MANAGE);
        return ApiResponse.ok(service.createWard(req, actor));
    }

    @PostMapping("/wards/{wardId}/beds")
    public ApiResponse<List<Bed>> createBeds(
            @PathVariable UUID wardId,
            @Valid @RequestBody CreateBedsReq req,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(value = AuthHeaders.ACTOR, defaultValue = "system") String actor) {
        Permissions.require(permissions, WARD_MANAGE);
        return ApiResponse.ok(service.createBeds(wardId, req, actor));
    }

    @GetMapping("/wards/{wardId}/beds")
    public ApiResponse<List<Bed>> listBeds(
            @PathVariable UUID wardId,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions) {
        Permissions.require(permissions, READ);
        return ApiResponse.ok(service.listBeds(wardId));
    }

    @GetMapping("/wards/{wardId}/admissions")
    public ApiResponse<List<AdmissionView>> listAdmissions(
            @PathVariable UUID wardId,
            @RequestParam(required = false) String status,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions) {
        Permissions.require(permissions, READ);
        return ApiResponse.ok(service.listByWard(wardId, status));
    }

    @PostMapping("/inpatient/admissions")
    public ApiResponse<AdmissionView> admit(
            @Valid @RequestBody AdmitReq req,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(value = AuthHeaders.ACTOR, defaultValue = "system") String actor) {
        Permissions.require(permissions, ADMIT);
        return ApiResponse.ok(service.admit(req, actor));
    }

    @GetMapping("/inpatient/admissions/{id}")
    public ApiResponse<AdmissionView> detail(
            @PathVariable UUID id,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions) {
        Permissions.require(permissions, READ);
        return ApiResponse.ok(service.detail(id));
    }

    @PostMapping("/inpatient/admissions/{id}/bed")
    public ApiResponse<AdmissionView> assignBed(
            @PathVariable UUID id,
            @Valid @RequestBody AssignBedReq req,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(value = AuthHeaders.ACTOR, defaultValue = "system") String actor) {
        Permissions.require(permissions, BED_ASSIGN);
        return ApiResponse.ok(service.assignBed(id, req, actor));
    }

    @PostMapping("/inpatient/admissions/{id}/discharge")
    public ApiResponse<AdmissionView> discharge(
            @PathVariable UUID id,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(value = AuthHeaders.ACTOR, defaultValue = "system") String actor) {
        Permissions.require(permissions, DISCHARGE);
        return ApiResponse.ok(service.discharge(id, actor));
    }

    /** 缴纳预交金。 */
    @PostMapping("/inpatient/admissions/{id}/deposits")
    public ApiResponse<DepositView> deposit(
            @PathVariable UUID id,
            @Valid @RequestBody DepositReq req,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(value = AuthHeaders.ACTOR, defaultValue = "system") String actor) {
        Permissions.require(permissions, DEPOSIT_MANAGE);
        return ApiResponse.ok(service.deposit(id, req.amount(), req.method(), req.remark(), actor));
    }

    /** 退还预交金（不得超过余额）。 */
    @PostMapping("/inpatient/admissions/{id}/refunds")
    public ApiResponse<DepositView> refund(
            @PathVariable UUID id,
            @Valid @RequestBody DepositReq req,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(value = AuthHeaders.ACTOR, defaultValue = "system") String actor) {
        Permissions.require(permissions, DEPOSIT_MANAGE);
        return ApiResponse.ok(service.refund(id, req.amount(), req.method(), req.remark(), actor));
    }

    /** 预交金余额与流水。 */
    @GetMapping("/inpatient/admissions/{id}/deposits")
    public ApiResponse<DepositView> deposits(
            @PathVariable UUID id,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions) {
        Permissions.require(permissions, READ);
        return ApiResponse.ok(service.balance(id));
    }
}
