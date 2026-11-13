package com.medassist.clinical.web;

import com.medassist.clinical.domain.ClinicalModel.InfectionDetection;
import com.medassist.clinical.domain.ClinicalModel.InfectionDisease;
import com.medassist.clinical.domain.ClinicalModel.InfectionReport;
import com.medassist.clinical.domain.ClinicalModel.InfectionSummary;
import com.medassist.clinical.service.InfectionReportService;
import com.medassist.clinical.service.InfectionReportService.ReportDetail;
import com.medassist.clinical.web.InfectionDtos.AcceptReq;
import com.medassist.clinical.web.InfectionDtos.ManualCreateReq;
import com.medassist.clinical.web.InfectionDtos.ReasonReq;
import com.medassist.clinical.web.InfectionDtos.SubmitReq;
import com.medassist.common.api.ApiResponse;
import com.medassist.common.security.AuthHeaders;
import com.medassist.common.security.Permissions;
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
 * 传染病上报与漏报监测（§294 / 计划书 W1.9）。
 *
 * <p>权限切分：
 * <ul>
 *   <li>{@code clinical:infection:read}：报告卡、汇总、病种目录（医生/护士/质控/运营）；</li>
 *   <li>{@code clinical:infection:report}：补录、提交/重报（临床一线）；</li>
 *   <li>{@code clinical:infection:manage}：自动扫描、疾控受理/退回/作废（防保科/院感/管理员）。</li>
 * </ul>
 *
 * <p>已提交/已受理的报告卡不能作废：法定报告是既成事实，更正须走疾控流程。
 */
@RestController
@RequestMapping("/clinical/infections")
public class InfectionReportController {

    private static final String READ = "clinical:infection:read";
    private static final String REPORT = "clinical:infection:report";
    private static final String MANAGE = "clinical:infection:manage";

    private final InfectionReportService service;

    public InfectionReportController(InfectionReportService service) {
        this.service = service;
    }

    @GetMapping("/summary")
    public ApiResponse<InfectionSummary> summary(
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions) {
        Permissions.require(permissions, READ);
        return ApiResponse.ok(service.summary());
    }

    @GetMapping("/diseases")
    public ApiResponse<List<InfectionDisease>> diseases(
            @RequestParam(defaultValue = "true") boolean enabledOnly,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions) {
        Permissions.require(permissions, READ);
        return ApiResponse.ok(service.diseases(enabledOnly));
    }

    @GetMapping("/reports")
    public ApiResponse<List<InfectionReport>> reports(
            @RequestParam(defaultValue = "") String status,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            LocalDate to,
            @RequestParam(defaultValue = "") String q,
            @RequestParam(defaultValue = "false") boolean overdueOnly,
            @RequestParam(defaultValue = "100") int limit,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions) {
        Permissions.require(permissions, READ);
        return ApiResponse.ok(service.list(status, from, to, q, overdueOnly, limit));
    }

    @GetMapping("/reports/{id}")
    public ApiResponse<ReportDetail> detail(
            @PathVariable UUID id,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions) {
        Permissions.require(permissions, READ);
        return ApiResponse.ok(service.detail(id));
    }

    /** 立即扫描诊断并生成待报卡（定时任务之外的运维兜底）。 */
    @PostMapping("/detect")
    public ApiResponse<InfectionDetection> detect(
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(value = AuthHeaders.ACTOR, required = false) String actor) {
        Permissions.require(permissions, MANAGE);
        return ApiResponse.ok(service.detect(actor));
    }

    /** 手工补录（自动匹配遗漏或目录新增后的历史病例）。 */
    @PostMapping("/reports")
    public ApiResponse<ReportDetail> create(
            @Valid @RequestBody ManualCreateReq req,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(value = AuthHeaders.ACTOR, required = false) String actor) {
        Permissions.require(permissions, REPORT);
        return ApiResponse.ok(service.createManual(req.visitId(), req.diseaseCode(),
                req.onsetDate(), req.icdCode(), req.icdName(), req.reporterDept(), actor));
    }

    @PostMapping("/reports/{id}/submit")
    public ApiResponse<ReportDetail> submit(
            @PathVariable UUID id,
            @Valid @RequestBody SubmitReq req,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(value = AuthHeaders.ACTOR, required = false) String actor) {
        Permissions.require(permissions, REPORT);
        return ApiResponse.ok(service.submit(id, req.version(), req.reporter(),
                req.reporterDept(), actor));
    }

    @PostMapping("/reports/{id}/accept")
    public ApiResponse<ReportDetail> accept(
            @PathVariable UUID id,
            @Valid @RequestBody AcceptReq req,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(value = AuthHeaders.ACTOR, required = false) String actor) {
        Permissions.require(permissions, MANAGE);
        return ApiResponse.ok(service.accept(id, req.version(), req.receiptNo(), actor));
    }

    @PostMapping("/reports/{id}/return")
    public ApiResponse<ReportDetail> returnCard(
            @PathVariable UUID id,
            @Valid @RequestBody ReasonReq req,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(value = AuthHeaders.ACTOR, required = false) String actor) {
        Permissions.require(permissions, MANAGE);
        return ApiResponse.ok(service.returnCard(id, req.version(), req.reason(), actor));
    }

    @PostMapping("/reports/{id}/void")
    public ApiResponse<ReportDetail> voidCard(
            @PathVariable UUID id,
            @Valid @RequestBody ReasonReq req,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(value = AuthHeaders.ACTOR, required = false) String actor) {
        Permissions.require(permissions, MANAGE);
        return ApiResponse.ok(service.voidCard(id, req.version(), req.reason(), actor));
    }
}
