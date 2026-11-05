package com.medassist.tech.web;

import com.medassist.common.api.ApiResponse;
import com.medassist.common.security.AuthHeaders;
import com.medassist.common.security.DataScope;
import com.medassist.common.security.Permissions;
import com.medassist.tech.service.ReportService;
import com.medassist.tech.service.TechAccessGuard;
import com.medassist.tech.web.TechDtos.AckCriticalReq;
import com.medassist.tech.web.TechDtos.AmendReportReq;
import com.medassist.tech.web.TechDtos.CloseCriticalReq;
import com.medassist.tech.web.TechDtos.CriticalView;
import com.medassist.tech.web.TechDtos.ReportView;
import com.medassist.tech.web.TechDtos.ReviewReportReq;
import com.medassist.tech.web.TechDtos.WriteReportReq;
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
 * 报告与危急值 API。
 *
 * <p>权限划分：技师书写/修订报告与关闭危急值；上级技师或医师审核签发；
 * 临床（医师/护士）确认危急值；全员可查询。
 */
@RestController
@RequestMapping("/tech")
public class ReportController {

    private static final String WRITE = "tech:report:write";
    private static final String REVIEW = "tech:report:review";
    private static final String ACK = "tech:critical:acknowledge";
    private static final String READ = "tech:read";

    private final ReportService service;
    private final TechAccessGuard guard;

    public ReportController(ReportService service, TechAccessGuard guard) {
        this.service = service;
        this.guard = guard;
    }

    // ---------------------------------------------------------------- 报告

    @PostMapping("/applications/{applicationId}/reports")
    public ApiResponse<ReportView> write(
            @PathVariable UUID applicationId,
            @Valid @RequestBody WriteReportReq req,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(value = AuthHeaders.ACTOR, defaultValue = "system") String actor,
            DataScope scope) {
        Permissions.require(permissions, WRITE);
        guard.requireApplication(scope, applicationId);
        return ApiResponse.ok(service.write(applicationId, req, actor));
    }

    @GetMapping("/applications/{applicationId}/reports/current")
    public ApiResponse<ReportView> current(
            @PathVariable UUID applicationId,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            DataScope scope) {
        Permissions.require(permissions, READ);
        guard.requireApplication(scope, applicationId);
        return ApiResponse.ok(service.currentOf(applicationId));
    }

    @GetMapping("/reports/{id}")
    public ApiResponse<ReportView> detail(
            @PathVariable UUID id,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            DataScope scope) {
        Permissions.require(permissions, READ);
        guard.requireReport(scope, id);
        return ApiResponse.ok(service.detail(id));
    }

    /** 重写草稿（仅 DRAFT）。 */
    @PostMapping("/reports/{id}/rewrite")
    public ApiResponse<ReportView> rewrite(
            @PathVariable UUID id,
            @Valid @RequestBody WriteReportReq req,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(value = AuthHeaders.ACTOR, defaultValue = "system") String actor,
            DataScope scope) {
        Permissions.require(permissions, WRITE);
        guard.requireReport(scope, id);
        return ApiResponse.ok(service.rewrite(id, req, actor));
    }

    /** 审核：approve=true 签发；false 驳回（报告保持草稿，原因入审计）。 */
    @PostMapping("/reports/{id}/review")
    public ApiResponse<ReportView> review(
            @PathVariable UUID id,
            @Valid @RequestBody ReviewReportReq req,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(value = AuthHeaders.ACTOR, defaultValue = "system") String actor,
            DataScope scope) {
        Permissions.require(permissions, REVIEW);
        guard.requireReport(scope, id);
        return ApiResponse.ok(service.review(id, req.approve(), req.reason(), actor));
    }

    /** 修订：追加新版本，旧版本保留为历史。 */
    @PostMapping("/reports/{id}/amend")
    public ApiResponse<ReportView> amend(
            @PathVariable UUID id,
            @Valid @RequestBody AmendReportReq req,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(value = AuthHeaders.ACTOR, defaultValue = "system") String actor,
            DataScope scope) {
        Permissions.require(permissions, REVIEW);
        guard.requireReport(scope, id);
        return ApiResponse.ok(service.amend(id, req, actor));
    }

    // ---------------------------------------------------------------- 危急值

    @GetMapping("/criticals")
    public ApiResponse<List<CriticalView>> criticals(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) UUID applicationId,
            @RequestParam(required = false) Integer limit,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            DataScope scope) {
        Permissions.require(permissions, READ);
        return ApiResponse.ok(service.criticals(status, applicationId,
                scope.campusFilter(), limit));
    }

    /** 临床确认危急值（护士/医师）。 */
    @PostMapping("/criticals/{id}/acknowledge")
    public ApiResponse<CriticalView> acknowledge(
            @PathVariable UUID id,
            @Valid @RequestBody(required = false) AckCriticalReq req,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(value = AuthHeaders.ACTOR, defaultValue = "system") String actor,
            DataScope scope) {
        Permissions.require(permissions, ACK);
        guard.requireCritical(scope, id);
        return ApiResponse.ok(service.acknowledge(id, req == null ? null : req.note(), actor));
    }

    /** 关闭危急值（必须已确认）。 */
    @PostMapping("/criticals/{id}/close")
    public ApiResponse<CriticalView> close(
            @PathVariable UUID id,
            @Valid @RequestBody(required = false) CloseCriticalReq req,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(value = AuthHeaders.ACTOR, defaultValue = "system") String actor,
            DataScope scope) {
        Permissions.require(permissions, ACK);
        guard.requireCritical(scope, id);
        return ApiResponse.ok(service.closeCritical(id, req == null ? null : req.note(), actor));
    }
}
