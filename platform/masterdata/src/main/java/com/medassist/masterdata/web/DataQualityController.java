package com.medassist.masterdata.web;

import com.medassist.common.api.ApiResponse;
import com.medassist.common.api.PageResult;
import com.medassist.common.security.AuthHeaders;
import com.medassist.common.security.Permissions;
import com.medassist.masterdata.domain.DataQualityModel.Dashboard;
import com.medassist.masterdata.domain.DataQualityModel.Issue;
import com.medassist.masterdata.domain.DataQualityModel.Rule;
import com.medassist.masterdata.domain.DataQualityModel.Run;
import com.medassist.masterdata.service.DataQualityService;
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
import java.util.Map;
import java.util.UUID;

/**
 * 数据质量与指标稽核 API（§299）。
 *
 * <p>权限：
 * <ul>
 *   <li>{@code mdm:quality:read}：规则、执行记录、问题与看板查询；</li>
 *   <li>{@code mdm:quality:run}：手动执行单条/全部规则；</li>
 *   <li>{@code mdm:quality:manage}：维护规则、确认与解决质量问题；</li>
 *   <li>{@code mdm:approve}：豁免质量问题（数据治理负责人）。</li>
 * </ul>
 */
@RestController
@RequestMapping("/mdm/quality")
public class DataQualityController {

    private static final String READ = "mdm:quality:read";
    private static final String RUN = "mdm:quality:run";
    private static final String MANAGE = "mdm:quality:manage";
    private static final String APPROVE = "mdm:approve";

    private final DataQualityService service;

    public DataQualityController(DataQualityService service) {
        this.service = service;
    }

    @GetMapping("/dashboard")
    public ApiResponse<Dashboard> dashboard(
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions) {
        Permissions.require(permissions, READ);
        return ApiResponse.ok(service.dashboard());
    }

    // ------------------------------------------------------------------ 规则

    @GetMapping("/rules")
    public ApiResponse<List<Rule>> rules(
            @RequestParam(required = false) String domain,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "200") int limit,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions) {
        Permissions.require(permissions, READ);
        return ApiResponse.ok(service.listRules(domain, status, limit));
    }

    @PostMapping("/rules")
    public ApiResponse<Rule> upsertRule(
            @Valid @RequestBody DataQualityDtos.RuleReq req,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(value = AuthHeaders.ACTOR, required = false) String actor,
            @RequestHeader(value = AuthHeaders.CLIENT_IP, required = false) String clientIp,
            @RequestHeader(value = AuthHeaders.TRACE_ID, required = false) String traceId) {
        Permissions.require(permissions, MANAGE);
        return ApiResponse.ok(service.upsertRule(req, actor, clientIp, traceId));
    }

    @GetMapping("/rules/{id}")
    public ApiResponse<Rule> rule(
            @PathVariable UUID id,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions) {
        Permissions.require(permissions, READ);
        return ApiResponse.ok(service.rule(id));
    }

    @PostMapping("/rules/{id}/run")
    public ApiResponse<Run> runRule(
            @PathVariable UUID id,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(value = AuthHeaders.ACTOR, required = false) String actor,
            @RequestHeader(value = AuthHeaders.CLIENT_IP, required = false) String clientIp,
            @RequestHeader(value = AuthHeaders.TRACE_ID, required = false) String traceId) {
        Permissions.require(permissions, RUN);
        return ApiResponse.ok(service.runRule(id, actor, clientIp, traceId));
    }

    @PostMapping("/rules/run-all")
    public ApiResponse<Map<String, Object>> runAll(
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(value = AuthHeaders.ACTOR, required = false) String actor,
            @RequestHeader(value = AuthHeaders.CLIENT_IP, required = false) String clientIp,
            @RequestHeader(value = AuthHeaders.TRACE_ID, required = false) String traceId) {
        Permissions.require(permissions, RUN);
        List<Run> runs = service.runAll(actor);
        long passed = runs.stream().filter(r -> "PASSED".equals(r.status())).count();
        long failed = runs.stream().filter(r -> "FAILED".equals(r.status())).count();
        long errors = runs.stream().filter(r -> "ERROR".equals(r.status())).count();
        return ApiResponse.ok(Map.of("total", runs.size(), "passed", passed,
                "failed", failed, "errors", errors));
    }

    // ------------------------------------------------------------------ 执行记录

    @GetMapping("/runs")
    public ApiResponse<PageResult<Run>> runs(
            @RequestParam(required = false) String ruleCode,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions) {
        Permissions.require(permissions, READ);
        int safePage = Math.max(page, 1);
        int safeSize = Math.min(Math.max(size, 1), PageResult.MAX_PAGE_SIZE);
        long total = service.countRuns(ruleCode, status);
        List<Run> items = service.listRuns(ruleCode, status, safeSize, (safePage - 1) * safeSize);
        return ApiResponse.ok(PageResult.of(safePage, safeSize, total, items));
    }

    @GetMapping("/runs/{id}")
    public ApiResponse<Run> run(
            @PathVariable UUID id,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions) {
        Permissions.require(permissions, READ);
        return ApiResponse.ok(service.run(id));
    }

    // ------------------------------------------------------------------ 问题闭环

    @GetMapping("/issues")
    public ApiResponse<PageResult<Issue>> issues(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String severity,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions) {
        Permissions.require(permissions, READ);
        int safePage = Math.max(page, 1);
        int safeSize = Math.min(Math.max(size, 1), PageResult.MAX_PAGE_SIZE);
        long total = service.countIssues(status);
        List<Issue> items = service.listIssues(status, severity, safeSize,
                (safePage - 1) * safeSize);
        return ApiResponse.ok(PageResult.of(safePage, safeSize, total, items));
    }

    @GetMapping("/issues/{id}")
    public ApiResponse<Issue> issue(
            @PathVariable UUID id,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions) {
        Permissions.require(permissions, READ);
        return ApiResponse.ok(service.issue(id));
    }

    @PostMapping("/issues/{id}/acknowledge")
    public ApiResponse<Issue> acknowledge(
            @PathVariable UUID id,
            @RequestBody(required = false) DataQualityDtos.IssueAckReq req,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(value = AuthHeaders.ACTOR, required = false) String actor,
            @RequestHeader(value = AuthHeaders.CLIENT_IP, required = false) String clientIp,
            @RequestHeader(value = AuthHeaders.TRACE_ID, required = false) String traceId) {
        Permissions.require(permissions, MANAGE);
        return ApiResponse.ok(service.acknowledge(id, req, actor, clientIp, traceId));
    }

    @PostMapping("/issues/{id}/resolve")
    public ApiResponse<Issue> resolve(
            @PathVariable UUID id,
            @Valid @RequestBody DataQualityDtos.IssueResolveReq req,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(value = AuthHeaders.ACTOR, required = false) String actor,
            @RequestHeader(value = AuthHeaders.CLIENT_IP, required = false) String clientIp,
            @RequestHeader(value = AuthHeaders.TRACE_ID, required = false) String traceId) {
        Permissions.require(permissions, MANAGE);
        return ApiResponse.ok(service.resolve(id, req, actor, clientIp, traceId));
    }

    @PostMapping("/issues/{id}/waive")
    public ApiResponse<Issue> waive(
            @PathVariable UUID id,
            @Valid @RequestBody DataQualityDtos.IssueWaiveReq req,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(value = AuthHeaders.ACTOR, required = false) String actor,
            @RequestHeader(value = AuthHeaders.CLIENT_IP, required = false) String clientIp,
            @RequestHeader(value = AuthHeaders.TRACE_ID, required = false) String traceId) {
        Permissions.require(permissions, APPROVE);
        return ApiResponse.ok(service.waive(id, req, actor, clientIp, traceId));
    }
}
