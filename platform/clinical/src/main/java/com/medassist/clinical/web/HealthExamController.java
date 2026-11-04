package com.medassist.clinical.web;

import com.medassist.clinical.domain.ClinicalModel.HealthExamPackage;
import com.medassist.clinical.service.HealthExamService;
import com.medassist.clinical.web.HealthExamDtos.BoardView;
import com.medassist.clinical.web.HealthExamDtos.CancelReq;
import com.medassist.clinical.web.HealthExamDtos.CreatePackageReq;
import com.medassist.clinical.web.HealthExamDtos.CreateRegistrationReq;
import com.medassist.clinical.web.HealthExamDtos.ItemResultReq;
import com.medassist.clinical.web.HealthExamDtos.PackageView;
import com.medassist.clinical.web.HealthExamDtos.RegistrationSummaryView;
import com.medassist.clinical.web.HealthExamDtos.RegistrationView;
import com.medassist.clinical.web.HealthExamDtos.ReportDraftReq;
import com.medassist.clinical.web.HealthExamDtos.UpdatePackageReq;
import com.medassist.common.api.ApiResponse;
import com.medassist.common.security.AuthHeaders;
import com.medassist.common.security.DataScope;
import com.medassist.common.security.Permissions;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * 体检中心 API（G37）。
 *
 * <p>权限点：
 * <ul>
 *   <li>{@code health:package:read/manage}——套餐查看与维护；</li>
 *   <li>{@code health:registration:read/write}——体检登记查看与登记/取消；</li>
 *   <li>{@code health:exam:execute}——分科检查结果录入；</li>
 *   <li>{@code health:report:manage}——总检报告草稿与出具。</li>
 * </ul>
 * 登记与报告中患者号/姓名按 {@link DataScope} 决定明文或掩码。
 */
@RestController
@RequestMapping("/clinical/health")
public class HealthExamController {

    private static final String PKG_READ = "health:package:read";
    private static final String PKG_MANAGE = "health:package:manage";
    private static final String REG_READ = "health:registration:read";
    private static final String REG_WRITE = "health:registration:write";
    private static final String EXAM_EXECUTE = "health:exam:execute";
    private static final String REPORT_MANAGE = "health:report:manage";

    private final HealthExamService service;

    public HealthExamController(HealthExamService service) {
        this.service = service;
    }

    @GetMapping("/packages")
    public ApiResponse<List<HealthExamPackage>> packages(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "50") int limit,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions) {
        Permissions.require(permissions, PKG_READ);
        return ApiResponse.ok(service.listPackages(status, keyword, limit));
    }

    @GetMapping("/packages/{id}")
    public ApiResponse<PackageView> packageDetail(
            @PathVariable UUID id,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions) {
        Permissions.require(permissions, PKG_READ);
        return ApiResponse.ok(service.detail(id));
    }

    @PostMapping("/packages")
    public ApiResponse<PackageView> createPackage(
            @Valid @RequestBody CreatePackageReq req,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(value = AuthHeaders.ACTOR, defaultValue = "system") String actor) {
        Permissions.require(permissions, PKG_MANAGE);
        return ApiResponse.ok(service.createPackage(req, actor));
    }

    @PutMapping("/packages/{id}")
    public ApiResponse<PackageView> updatePackage(
            @PathVariable UUID id,
            @Valid @RequestBody UpdatePackageReq req,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(value = AuthHeaders.ACTOR, defaultValue = "system") String actor) {
        Permissions.require(permissions, PKG_MANAGE);
        return ApiResponse.ok(service.updatePackage(id, req, actor));
    }

    @PostMapping("/packages/{id}/publish")
    public ApiResponse<PackageView> publishPackage(
            @PathVariable UUID id,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(value = AuthHeaders.ACTOR, defaultValue = "system") String actor) {
        Permissions.require(permissions, PKG_MANAGE);
        return ApiResponse.ok(service.publishPackage(id, actor));
    }

    @PostMapping("/packages/{id}/disable")
    public ApiResponse<PackageView> disablePackage(
            @PathVariable UUID id,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(value = AuthHeaders.ACTOR, defaultValue = "system") String actor) {
        Permissions.require(permissions, PKG_MANAGE);
        return ApiResponse.ok(service.disablePackage(id, actor));
    }

    @GetMapping("/registrations")
    public ApiResponse<List<RegistrationSummaryView>> registrations(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            LocalDate to,
            @RequestParam(defaultValue = "100") int limit,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            DataScope scope) {
        Permissions.require(permissions, REG_READ);
        return ApiResponse.ok(service.listRegistrations(status, from, to, limit).stream()
                .map(s -> PhiMask.healthSummary(s, scope))
                .toList());
    }

    @GetMapping("/registrations/{id}")
    public ApiResponse<RegistrationView> registrationDetail(
            @PathVariable UUID id,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            DataScope scope) {
        Permissions.require(permissions, REG_READ);
        return ApiResponse.ok(PhiMask.healthRegistration(service.registrationDetail(id), scope));
    }

    @PostMapping("/registrations")
    public ApiResponse<RegistrationView> createRegistration(
            @Valid @RequestBody CreateRegistrationReq req,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(value = AuthHeaders.ACTOR, defaultValue = "system") String actor,
            DataScope scope) {
        Permissions.require(permissions, REG_WRITE);
        return ApiResponse.ok(PhiMask.healthRegistration(
                service.createRegistration(req, actor), scope));
    }

    @PostMapping("/registrations/{id}/cancel")
    public ApiResponse<RegistrationView> cancelRegistration(
            @PathVariable UUID id,
            @Valid @RequestBody CancelReq req,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(value = AuthHeaders.ACTOR, defaultValue = "system") String actor,
            DataScope scope) {
        Permissions.require(permissions, REG_WRITE);
        return ApiResponse.ok(PhiMask.healthRegistration(
                service.cancelRegistration(id, req.reason(), actor), scope));
    }

    @PostMapping("/registrations/{id}/items/{itemId}/result")
    public ApiResponse<RegistrationView> recordItemResult(
            @PathVariable UUID id,
            @PathVariable UUID itemId,
            @Valid @RequestBody ItemResultReq req,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(value = AuthHeaders.ACTOR, defaultValue = "system") String actor,
            DataScope scope) {
        Permissions.require(permissions, EXAM_EXECUTE);
        return ApiResponse.ok(PhiMask.healthRegistration(
                service.recordItemResult(id, itemId, req, actor), scope));
    }

    @PostMapping("/registrations/{id}/report/draft")
    public ApiResponse<RegistrationView> saveReportDraft(
            @PathVariable UUID id,
            @Valid @RequestBody ReportDraftReq req,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(value = AuthHeaders.ACTOR, defaultValue = "system") String actor,
            DataScope scope) {
        Permissions.require(permissions, REPORT_MANAGE);
        return ApiResponse.ok(PhiMask.healthRegistration(
                service.saveReportDraft(id, req, actor), scope));
    }

    @PostMapping("/registrations/{id}/report/finalize")
    public ApiResponse<RegistrationView> finalizeReport(
            @PathVariable UUID id,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(value = AuthHeaders.ACTOR, defaultValue = "system") String actor,
            DataScope scope) {
        Permissions.require(permissions, REPORT_MANAGE);
        return ApiResponse.ok(PhiMask.healthRegistration(
                service.finalizeReport(id, actor), scope));
    }

    @GetMapping("/board")
    public ApiResponse<BoardView> board(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            LocalDate to,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions) {
        Permissions.require(permissions, REG_READ);
        return ApiResponse.ok(service.board(from, to));
    }
}
