package com.medassist.pharmacy.web;

import com.medassist.common.api.ApiResponse;
import com.medassist.common.security.AuthHeaders;
import com.medassist.common.security.Permissions;
import com.medassist.pharmacy.service.AntibioticService;
import com.medassist.pharmacy.web.AntibioticDtos.ApprovalReq;
import com.medassist.pharmacy.web.AntibioticDtos.ApprovalView;
import com.medassist.pharmacy.web.AntibioticDtos.PendingRow;
import com.medassist.pharmacy.web.AntibioticDtos.StatsView;
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

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * 抗菌药物专项 API（G33）。
 *
 * <p>查看统计/待审：{@code pharmacy:antibiotic:read}；
 * 特殊使用级审批：{@code pharmacy:antibiotic:approve}（审批人 ≠ 开方医师）。
 */
@RestController
@RequestMapping("/pharmacy/antibiotics")
public class AntibioticController {

    private static final String READ = "pharmacy:antibiotic:read";
    private static final String APPROVE = "pharmacy:antibiotic:approve";

    private final AntibioticService service;

    public AntibioticController(AntibioticService service) {
        this.service = service;
    }

    @GetMapping("/stats")
    public ApiResponse<StatsView> stats(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            LocalDate to,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions) {
        Permissions.require(permissions, READ);
        return ApiResponse.ok(service.stats(from, to));
    }

    @GetMapping("/pending")
    public ApiResponse<List<PendingRow>> pending(
            @RequestParam(required = false) Integer limit,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions) {
        Permissions.require(permissions, READ);
        return ApiResponse.ok(service.pending(limit));
    }

    @GetMapping("/prescriptions/{prescriptionId}/approval")
    public ApiResponse<ApprovalView> approval(
            @PathVariable UUID prescriptionId,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions) {
        Permissions.require(permissions, READ);
        return ApiResponse.ok(service.approval(prescriptionId));
    }

    /** 特殊使用级审批：approve=false 必须写理由。 */
    @PostMapping("/prescriptions/{prescriptionId}/approval")
    public ApiResponse<ApprovalView> approve(
            @PathVariable UUID prescriptionId,
            @Valid @RequestBody ApprovalReq req,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(value = AuthHeaders.ACTOR, defaultValue = "system") String actor) {
        Permissions.require(permissions, APPROVE);
        return ApiResponse.ok(service.approve(prescriptionId, req, actor));
    }
}
