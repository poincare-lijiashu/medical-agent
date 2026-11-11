package com.medassist.clinical.web;

import com.medassist.clinical.domain.ClinicalModel.OperationCatalog;
import com.medassist.clinical.domain.ClinicalModel.SurgeryCase;
import com.medassist.clinical.service.SurgeryService;
import com.medassist.clinical.service.VisitAccessGuard;
import com.medassist.clinical.web.SurgeryDtos.CancelSurgeryReq;
import com.medassist.clinical.web.SurgeryDtos.PlanSurgeryReq;
import com.medassist.clinical.web.SurgeryDtos.SurgeryVersionReq;
import com.medassist.clinical.web.SurgerySafetyDtos.SafetyCheckReq;
import com.medassist.clinical.web.SurgerySafetyDtos.SafetyChecklistView;
import com.medassist.common.api.ApiResponse;
import com.medassist.common.security.AuthHeaders;
import com.medassist.common.security.DataScope;
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
 * 手术麻醉域 API（第一批）：ICD-9-CM-3 字典 + 手术记录状态机。
 *
 * <p>权限：读用 {@code clinical:surgery:read}，写用 {@code clinical:surgery:write}；
 * 与病案首页/DRG 的联动只发生在"完成手术"之后（未完成不进病案）。
 */
@RestController
@RequestMapping("/clinical")
public class SurgeryController {

    private static final String READ = "clinical:surgery:read";
    private static final String WRITE = "clinical:surgery:write";

    private final SurgeryService service;
    private final VisitAccessGuard guard;

    public SurgeryController(SurgeryService service, VisitAccessGuard guard) {
        this.service = service;
        this.guard = guard;
    }

    /** 手术编码搜索（供开单/病案选择，只返回启用项）。 */
    @GetMapping("/operation-catalog")
    public ApiResponse<List<OperationCatalog>> catalog(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) Integer limit,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions) {
        Permissions.require(permissions, READ);
        return ApiResponse.ok(service.catalog(keyword, limit));
    }

    @GetMapping("/visits/{visitId}/surgery-cases")
    public ApiResponse<List<SurgeryCase>> list(
            @PathVariable UUID visitId,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            DataScope scope) {
        Permissions.require(permissions, READ);
        // 只读：本科室或受邀会诊进行中（会诊医师需要看患者手术史）
        guard.requireRead(scope, visitId);
        return ApiResponse.ok(service.listForVisit(visitId));
    }

    @PostMapping("/visits/{visitId}/surgery-cases")
    public ApiResponse<SurgeryCase> plan(
            @PathVariable UUID visitId,
            @Valid @RequestBody PlanSurgeryReq req,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(value = AuthHeaders.ACTOR, defaultValue = "system") String actor,
            DataScope scope) {
        Permissions.require(permissions, WRITE);
        guard.require(scope, visitId);
        return ApiResponse.ok(service.plan(visitId, req, actor));
    }

    @PostMapping("/visits/{visitId}/surgery-cases/{id}/start")
    public ApiResponse<SurgeryCase> start(
            @PathVariable UUID visitId, @PathVariable UUID id,
            @Valid @RequestBody SurgeryVersionReq req,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(value = AuthHeaders.ACTOR, defaultValue = "system") String actor,
            DataScope scope) {
        Permissions.require(permissions, WRITE);
        guard.require(scope, visitId);
        return ApiResponse.ok(service.start(visitId, id, req.version(), actor));
    }

    @PostMapping("/visits/{visitId}/surgery-cases/{id}/complete")
    public ApiResponse<SurgeryCase> complete(
            @PathVariable UUID visitId, @PathVariable UUID id,
            @Valid @RequestBody SurgeryVersionReq req,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(value = AuthHeaders.ACTOR, defaultValue = "system") String actor,
            DataScope scope) {
        Permissions.require(permissions, WRITE);
        guard.require(scope, visitId);
        return ApiResponse.ok(service.complete(visitId, id, req.version(), actor));
    }

    /** 三时点安全核查汇总（麻醉前 / 手术开始前 / 离室前）。 */
    @GetMapping("/visits/{visitId}/surgery-cases/{id}/safety-checks")
    public ApiResponse<SafetyChecklistView> safetyChecklist(
            @PathVariable UUID visitId, @PathVariable UUID id,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            DataScope scope) {
        Permissions.require(permissions, READ);
        guard.requireRead(scope, visitId);
        return ApiResponse.ok(service.checklist(visitId, id));
    }

    /** 提交某一个时点的安全核查（每次新增一条证据，最新一条为当前结论）。 */
    @PostMapping("/visits/{visitId}/surgery-cases/{id}/safety-checks")
    public ApiResponse<SafetyChecklistView> submitSafetyCheck(
            @PathVariable UUID visitId, @PathVariable UUID id,
            @Valid @RequestBody SafetyCheckReq req,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(value = AuthHeaders.ACTOR, defaultValue = "system") String actor,
            DataScope scope) {
        Permissions.require(permissions, WRITE);
        guard.require(scope, visitId);
        return ApiResponse.ok(service.submitSafetyCheck(visitId, id, req, actor));
    }

    @PostMapping("/visits/{visitId}/surgery-cases/{id}/cancel")
    public ApiResponse<SurgeryCase> cancel(
            @PathVariable UUID visitId, @PathVariable UUID id,
            @Valid @RequestBody CancelSurgeryReq req,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(value = AuthHeaders.ACTOR, defaultValue = "system") String actor,
            DataScope scope) {
        Permissions.require(permissions, WRITE);
        guard.require(scope, visitId);
        return ApiResponse.ok(service.cancel(visitId, id, req, actor));
    }
}
