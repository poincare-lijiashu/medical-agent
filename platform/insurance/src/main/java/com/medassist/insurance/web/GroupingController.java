package com.medassist.insurance.web;

import com.medassist.common.api.ApiResponse;
import com.medassist.common.security.AuthHeaders;
import com.medassist.common.security.Permissions;
import com.medassist.insurance.domain.InsuranceModel.GroupSimulation;
import com.medassist.insurance.domain.InsuranceModel.PaymentGroup;
import com.medassist.insurance.domain.InsuranceModel.PaymentGroupRule;
import com.medassist.insurance.domain.InsuranceModel.PaymentScheme;
import com.medassist.insurance.service.PaymentGroupingService;
import com.medassist.insurance.web.InsuranceDtos.CreateSchemeReq;
import com.medassist.insurance.web.InsuranceDtos.GroupImportResult;
import com.medassist.insurance.web.InsuranceDtos.ImportGroupsReq;
import com.medassist.insurance.web.InsuranceDtos.ImportRulesReq;
import com.medassist.insurance.web.InsuranceDtos.RuleImportResult;
import com.medassist.insurance.web.InsuranceDtos.SimulateReq;
import com.medassist.insurance.web.InsuranceDtos.UpdateSchemeReq;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * DRG/DIP 支付方案、分组权重与院内模拟预分组（N5 第二批 C）。
 *
 * <p>权限：方案/分组/规则维护需 {@code insurance:grouping:manage}（医保办/管理员）；
 * 查询与模拟测算需 {@code insurance:settlement:read}。
 *
 * <p>**边界**：本接口是院内模拟预分组，不替代省局正式分组与结算。
 */
@RestController
@RequestMapping("/insurance/grouping")
public class GroupingController {

    private static final String MANAGE = "insurance:grouping:manage";
    private static final String READ = "insurance:settlement:read";

    private final PaymentGroupingService service;

    public GroupingController(PaymentGroupingService service) {
        this.service = service;
    }

    @GetMapping("/schemes")
    public ApiResponse<List<PaymentScheme>> schemes(
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions) {
        Permissions.require(permissions, READ);
        return ApiResponse.ok(service.listSchemes());
    }

    @PostMapping("/schemes")
    public ApiResponse<PaymentScheme> createScheme(
            @Valid @RequestBody CreateSchemeReq req,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(AuthHeaders.ACTOR) String actor) {
        Permissions.require(permissions, MANAGE);
        return ApiResponse.ok(service.createScheme(req, actor));
    }

    @PutMapping("/schemes/{code}")
    public ApiResponse<PaymentScheme> updateScheme(
            @PathVariable String code,
            @Valid @RequestBody UpdateSchemeReq req,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(AuthHeaders.ACTOR) String actor) {
        Permissions.require(permissions, MANAGE);
        return ApiResponse.ok(service.updateScheme(code, req, actor));
    }

    @GetMapping("/schemes/{code}/groups")
    public ApiResponse<List<PaymentGroup>> groups(
            @PathVariable String code,
            @RequestParam(required = false) Boolean enabled,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions) {
        Permissions.require(permissions, READ);
        return ApiResponse.ok(service.listGroups(code, enabled));
    }

    @PostMapping("/schemes/{code}/groups/import")
    public ApiResponse<GroupImportResult> importGroups(
            @PathVariable String code,
            @Valid @RequestBody ImportGroupsReq req,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(AuthHeaders.ACTOR) String actor) {
        Permissions.require(permissions, MANAGE);
        return ApiResponse.ok(service.importGroups(code, req.groups(), actor));
    }

    @GetMapping("/schemes/{code}/rules")
    public ApiResponse<List<PaymentGroupRule>> rules(
            @PathVariable String code,
            @RequestParam(required = false) Boolean enabled,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions) {
        Permissions.require(permissions, READ);
        return ApiResponse.ok(service.listRules(code, enabled));
    }

    @PostMapping("/schemes/{code}/rules/import")
    public ApiResponse<RuleImportResult> importRules(
            @PathVariable String code,
            @Valid @RequestBody ImportRulesReq req,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(AuthHeaders.ACTOR) String actor) {
        Permissions.require(permissions, MANAGE);
        return ApiResponse.ok(service.importRules(code, req.rules(), actor));
    }

    /** 模拟预分组（落一条可追溯快照）。 */
    @PostMapping("/simulate")
    public ApiResponse<GroupSimulation> simulate(
            @Valid @RequestBody SimulateReq req,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(AuthHeaders.ACTOR) String actor) {
        Permissions.require(permissions, READ);
        return ApiResponse.ok(service.simulate(req, actor));
    }

    @GetMapping("/by-visit/{visitId}")
    public ApiResponse<GroupSimulation> latest(
            @PathVariable UUID visitId,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(AuthHeaders.ACTOR) String actor) {
        Permissions.require(permissions, READ);
        return ApiResponse.ok(service.latestSimulation(visitId, actor));
    }

    @GetMapping("/by-visit/{visitId}/history")
    public ApiResponse<List<GroupSimulation>> history(
            @PathVariable UUID visitId,
            @RequestParam(required = false) Integer limit,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(AuthHeaders.ACTOR) String actor) {
        Permissions.require(permissions, READ);
        return ApiResponse.ok(service.simulations(visitId, limit, actor));
    }
}
