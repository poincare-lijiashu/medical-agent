package com.medassist.clinical.web;

import com.medassist.clinical.service.NursingIndicatorService;
import com.medassist.clinical.web.NursingIndicatorDtos.DenominatorBatchReq;
import com.medassist.clinical.web.NursingIndicatorDtos.DenominatorBatchResult;
import com.medassist.clinical.web.NursingIndicatorDtos.DenominatorView;
import com.medassist.clinical.web.NursingIndicatorDtos.EventReq;
import com.medassist.clinical.web.NursingIndicatorDtos.EventView;
import com.medassist.clinical.web.NursingIndicatorDtos.IndicatorBoardView;
import com.medassist.clinical.web.NursingIndicatorDtos.IndicatorDefView;
import com.medassist.clinical.web.NursingIndicatorDtos.ReportGenerateReq;
import com.medassist.clinical.web.NursingIndicatorDtos.ReportSummaryView;
import com.medassist.clinical.web.NursingIndicatorDtos.ReportView;
import com.medassist.clinical.web.NursingIndicatorDtos.ReviewReq;
import com.medassist.clinical.web.NursingIndicatorDtos.ShiftDayView;
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
 * 护理敏感质量指标 API（§266）。
 *
 * <p>权限切分：
 * <ul>
 *   <li>查询沿用 {@code inpatient:nursing:read}；</li>
 *   <li>事件/设备日录入需要 {@code inpatient:nursing:indicator}（责任护士）；</li>
 *   <li>周期报表生成与提交需要 {@code inpatient:nursing:indicator:manage}（护士长/管理员）；</li>
 *   <li>报表审核需要 {@code inpatient:nursing:indicator:review}（护理质量管理/质控），
 *       且服务层强制"提交人不能审自己"。</li>
 * </ul>
 */
@RestController
@RequestMapping("/clinical/nursing")
public class NursingIndicatorController {

    private static final String READ = "inpatient:nursing:read";
    private static final String ENTRY = "inpatient:nursing:indicator";
    private static final String MANAGE = "inpatient:nursing:indicator:manage";
    private static final String REVIEW = "inpatient:nursing:indicator:review";

    private final NursingIndicatorService service;

    public NursingIndicatorController(NursingIndicatorService service) {
        this.service = service;
    }

    @GetMapping("/indicators")
    public ApiResponse<List<IndicatorDefView>> definitions(
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions) {
        Permissions.require(permissions, READ);
        return ApiResponse.ok(service.listDefinitions());
    }

    @PostMapping("/indicator-events")
    public ApiResponse<EventView> reportEvent(
            @Valid @RequestBody EventReq req,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(value = AuthHeaders.ACTOR, defaultValue = "system") String actor) {
        Permissions.require(permissions, ENTRY);
        return ApiResponse.ok(service.reportEvent(req, actor));
    }

    @GetMapping("/indicator-events")
    public ApiResponse<List<EventView>> events(
            @RequestParam UUID deptId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            LocalDate to,
            @RequestParam(required = false) String indicatorCode,
            @RequestParam(required = false) String shiftCode,
            @RequestParam(required = false) Integer limit,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions) {
        Permissions.require(permissions, READ);
        return ApiResponse.ok(service.listEvents(deptId, from, to, indicatorCode, shiftCode, limit));
    }

    @PostMapping("/indicator-denominators")
    public ApiResponse<DenominatorBatchResult> recordDenominators(
            @Valid @RequestBody DenominatorBatchReq req,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(value = AuthHeaders.ACTOR, defaultValue = "system") String actor) {
        Permissions.require(permissions, ENTRY);
        return ApiResponse.ok(service.recordDenominators(req, actor));
    }

    @GetMapping("/indicator-denominators")
    public ApiResponse<List<DenominatorView>> denominators(
            @RequestParam UUID deptId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            LocalDate to,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions) {
        Permissions.require(permissions, READ);
        return ApiResponse.ok(service.listDenominators(deptId, from, to));
    }

    @GetMapping("/indicator-board")
    public ApiResponse<IndicatorBoardView> board(
            @RequestParam UUID deptId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            LocalDate to,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions) {
        Permissions.require(permissions, READ);
        return ApiResponse.ok(service.board(deptId, from, to));
    }

    @GetMapping("/indicator-shifts")
    public ApiResponse<ShiftDayView> shifts(
            @RequestParam UUID deptId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            LocalDate date,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions) {
        Permissions.require(permissions, READ);
        return ApiResponse.ok(service.shiftSummary(deptId, date));
    }

    @PostMapping("/indicator-reports")
    public ApiResponse<ReportView> generate(
            @Valid @RequestBody ReportGenerateReq req,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(value = AuthHeaders.ACTOR, defaultValue = "system") String actor) {
        Permissions.require(permissions, MANAGE);
        return ApiResponse.ok(service.generateReport(req, actor));
    }

    @GetMapping("/indicator-reports")
    public ApiResponse<List<ReportSummaryView>> reports(
            @RequestParam(required = false) UUID deptId,
            @RequestParam(required = false) String periodType,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Integer limit,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions) {
        Permissions.require(permissions, READ);
        return ApiResponse.ok(service.listReports(deptId, periodType, status, limit));
    }

    @GetMapping("/indicator-reports/{id}")
    public ApiResponse<ReportView> report(
            @PathVariable UUID id,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions) {
        Permissions.require(permissions, READ);
        return ApiResponse.ok(service.getReport(id));
    }

    @PostMapping("/indicator-reports/{id}/submit")
    public ApiResponse<ReportView> submit(
            @PathVariable UUID id,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(value = AuthHeaders.ACTOR, defaultValue = "system") String actor) {
        Permissions.require(permissions, MANAGE);
        return ApiResponse.ok(service.submit(id, actor));
    }

    @PostMapping("/indicator-reports/{id}/review")
    public ApiResponse<ReportView> review(
            @PathVariable UUID id,
            @Valid @RequestBody ReviewReq req,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(value = AuthHeaders.ACTOR, defaultValue = "system") String actor) {
        Permissions.require(permissions, REVIEW);
        return ApiResponse.ok(service.review(id, req, actor));
    }
}
