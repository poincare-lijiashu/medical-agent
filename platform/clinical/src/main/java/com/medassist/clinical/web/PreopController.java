package com.medassist.clinical.web;

import com.medassist.clinical.domain.ClinicalModel.PreopDiscussion;
import com.medassist.clinical.domain.ClinicalModel.SurgeryConsent;
import com.medassist.clinical.service.PreopService;
import com.medassist.clinical.service.VisitAccessGuard;
import com.medassist.clinical.web.PreopDtos.ConsentReq;
import com.medassist.clinical.web.PreopDtos.PreopReq;
import com.medassist.clinical.web.PreopDtos.PreopSummary;
import com.medassist.clinical.web.PreopDtos.RevokeConsentReq;
import com.medassist.clinical.web.SurgeryDtos.SurgeryVersionReq;
import com.medassist.common.api.ApiResponse;
import com.medassist.common.security.AuthHeaders;
import com.medassist.common.security.DataScope;
import com.medassist.common.security.Permissions;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/** 术前管理 API：术前讨论、知情同意、术前核查清单。 */
@RestController
@RequestMapping("/clinical/surgery-cases/{caseId}")
public class PreopController {

    private static final String READ = "clinical:surgery:read";
    private static final String WRITE = "clinical:surgery:write";

    private final PreopService service;
    private final VisitAccessGuard guard;

    public PreopController(PreopService service, VisitAccessGuard guard) {
        this.service = service;
        this.guard = guard;
    }

    @GetMapping("/preop-discussion")
    public ApiResponse<PreopDiscussion> getDiscussion(
            @PathVariable UUID caseId,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            DataScope scope) {
        Permissions.require(permissions, READ);
        guard.requireCase(scope, caseId);
        return ApiResponse.ok(service.getDiscussion(caseId));
    }

    @PutMapping("/preop-discussion")
    public ApiResponse<PreopDiscussion> saveDiscussion(
            @PathVariable UUID caseId, @Valid @RequestBody PreopReq req,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(value = AuthHeaders.ACTOR, defaultValue = "system") String actor,
            DataScope scope) {
        Permissions.require(permissions, WRITE);
        guard.requireCase(scope, caseId);
        return ApiResponse.ok(service.saveDiscussion(caseId, req, actor));
    }

    @PostMapping("/preop-discussion/sign")
    public ApiResponse<PreopDiscussion> signDiscussion(
            @PathVariable UUID caseId, @Valid @RequestBody SurgeryVersionReq req,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(value = AuthHeaders.ACTOR, defaultValue = "system") String actor,
            DataScope scope) {
        Permissions.require(permissions, WRITE);
        guard.requireCase(scope, caseId);
        return ApiResponse.ok(service.signDiscussion(caseId, req.version(), actor));
    }

    @GetMapping("/consents")
    public ApiResponse<List<SurgeryConsent>> consents(
            @PathVariable UUID caseId,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            DataScope scope) {
        Permissions.require(permissions, READ);
        guard.requireCase(scope, caseId);
        return ApiResponse.ok(service.consents(caseId));
    }

    @PostMapping("/consents")
    public ApiResponse<SurgeryConsent> addConsent(
            @PathVariable UUID caseId, @Valid @RequestBody ConsentReq req,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(value = AuthHeaders.ACTOR, defaultValue = "system") String actor,
            DataScope scope) {
        Permissions.require(permissions, WRITE);
        guard.requireCase(scope, caseId);
        return ApiResponse.ok(service.addConsent(caseId, req, actor));
    }

    @PostMapping("/consents/{consentId}/revoke")
    public ApiResponse<SurgeryConsent> revokeConsent(
            @PathVariable UUID caseId, @PathVariable UUID consentId,
            @Valid @RequestBody RevokeConsentReq req,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(value = AuthHeaders.ACTOR, defaultValue = "system") String actor,
            DataScope scope) {
        Permissions.require(permissions, WRITE);
        guard.requireCase(scope, caseId);
        return ApiResponse.ok(service.revokeConsent(caseId, consentId, req, actor));
    }

    @GetMapping("/preop-summary")
    public ApiResponse<PreopSummary> summary(
            @PathVariable UUID caseId,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            DataScope scope) {
        Permissions.require(permissions, READ);
        guard.requireCase(scope, caseId);
        return ApiResponse.ok(service.summary(caseId));
    }
}
