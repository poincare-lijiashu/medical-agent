package com.medassist.insurance.web;

import com.medassist.common.api.ApiResponse;
import com.medassist.common.security.AuthHeaders;
import com.medassist.common.security.Permissions;
import com.medassist.insurance.domain.InsuranceModel.GrouperResult;
import com.medassist.insurance.domain.InsuranceModel.GrouperRuleSet;
import com.medassist.insurance.service.GrouperService;
import com.medassist.insurance.web.GrouperDtos.ImportRuleSetReq;
import com.medassist.insurance.web.GrouperDtos.ImportRuleSetResult;
import com.medassist.insurance.web.GrouperDtos.OfficialReq;
import com.medassist.insurance.web.GrouperDtos.PreviewReq;
import com.medassist.insurance.web.GrouperDtos.SummaryView;
import com.medassist.insurance.web.GrouperDtos.VarianceView;
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
 * DRG/DIP 2.0 可配置分组引擎 API（§263）。
 *
 * <p>权限：规则包导入/激活需 {@code insurance:grouping:manage}；
 * 预分组、结果查询与差异分析需 {@code insurance:settlement:read}。
 *
 * <p>边界：预分组是院内测算/质控口径，不替代省局正式分组与结算。
 */
@RestController
@RequestMapping("/insurance/grouper")
public class GrouperController {

    private static final String MANAGE = "insurance:grouping:manage";
    private static final String READ = "insurance:settlement:read";

    private final GrouperService service;

    public GrouperController(GrouperService service) {
        this.service = service;
    }

    @GetMapping("/rule-sets")
    public ApiResponse<List<GrouperRuleSet>> ruleSets(
            @RequestParam(required = false) String type,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions) {
        Permissions.require(permissions, READ);
        return ApiResponse.ok(service.listRuleSets(type));
    }

    /** 导入规则包（整包校验，可同时激活）。 */
    @PostMapping("/rule-sets")
    public ApiResponse<ImportRuleSetResult> importRuleSet(
            @Valid @RequestBody ImportRuleSetReq req,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(AuthHeaders.ACTOR) String actor) {
        Permissions.require(permissions, MANAGE);
        return ApiResponse.ok(service.importRuleSet(req, actor));
    }

    @PostMapping("/rule-sets/{id}/activate")
    public ApiResponse<GrouperRuleSet> activate(
            @PathVariable UUID id,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(AuthHeaders.ACTOR) String actor) {
        Permissions.require(permissions, MANAGE);
        return ApiResponse.ok(service.activate(id, actor));
    }

    /** 预分组（落可追溯快照）。 */
    @PostMapping("/preview")
    public ApiResponse<GrouperResult> preview(
            @Valid @RequestBody PreviewReq req,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(AuthHeaders.ACTOR) String actor) {
        Permissions.require(permissions, READ);
        return ApiResponse.ok(service.preview(req, actor));
    }

    /** 登记省局正式分组结果（差异分析基准）。 */
    @PostMapping("/official")
    public ApiResponse<GrouperResult> official(
            @Valid @RequestBody OfficialReq req,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(AuthHeaders.ACTOR) String actor) {
        Permissions.require(permissions, MANAGE);
        return ApiResponse.ok(service.recordOfficial(req, actor));
    }

    @GetMapping("/by-visit/{visitId}")
    public ApiResponse<GrouperResult> latest(
            @PathVariable UUID visitId,
            @RequestParam(required = false) String engine,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(AuthHeaders.ACTOR) String actor) {
        Permissions.require(permissions, READ);
        return ApiResponse.ok(service.latest(visitId, engine, actor));
    }

    @GetMapping("/by-visit/{visitId}/history")
    public ApiResponse<List<GrouperResult>> history(
            @PathVariable UUID visitId,
            @RequestParam(required = false) Integer limit,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(AuthHeaders.ACTOR) String actor) {
        Permissions.require(permissions, READ);
        return ApiResponse.ok(service.history(visitId, limit, actor));
    }

    /** 分组报表：最近 N 天引擎统计、入组 Top 病组、未入组原因 Top。 */
    @GetMapping("/reports/summary")
    public ApiResponse<SummaryView> summary(
            @RequestParam(defaultValue = "30") int days,
            @RequestParam(defaultValue = "10") int top,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(AuthHeaders.ACTOR) String actor) {
        Permissions.require(permissions, READ);
        return ApiResponse.ok(service.summary(days, top, actor));
    }

    /** 预分组 vs 正式分组差异分析。 */
    @GetMapping("/by-visit/{visitId}/variance")
    public ApiResponse<VarianceView> variance(
            @PathVariable UUID visitId,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(AuthHeaders.ACTOR) String actor) {
        Permissions.require(permissions, READ);
        return ApiResponse.ok(service.variance(visitId, actor));
    }
}
