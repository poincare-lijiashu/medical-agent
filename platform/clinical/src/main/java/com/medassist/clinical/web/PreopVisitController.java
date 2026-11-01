package com.medassist.clinical.web;

import com.medassist.clinical.domain.ClinicalModel.PreopVisit;
import com.medassist.clinical.service.PreopVisitService;
import com.medassist.clinical.web.PreopVisitDtos.PreopVisitReq;
import com.medassist.clinical.web.SurgeryDtos.SurgeryVersionReq;
import com.medassist.common.api.ApiResponse;
import com.medassist.common.security.AuthHeaders;
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

import java.util.UUID;

/**
 * 术前访视与麻醉评估 API。
 *
 * <p>权限沿用手术域（读 {@code clinical:surgery:read} / 写 {@code clinical:surgery:write}）：
 * 访视单由麻醉医师填写，目前与术者共用手术写权限；角色细化（麻醉医师独立角色）随账号体系一起做。
 *
 * <p>GET 在未填写时返回 200 + 空 data（未填写是正常状态，不用 404 表达）。
 */
@RestController
@RequestMapping("/clinical/surgery-cases/{caseId}/preop-visit")
public class PreopVisitController {

    private static final String READ = "clinical:surgery:read";
    private static final String WRITE = "clinical:surgery:write";

    private final PreopVisitService service;

    public PreopVisitController(PreopVisitService service) {
        this.service = service;
    }

    @GetMapping
    public ApiResponse<PreopVisit> get(
            @PathVariable UUID caseId,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions) {
        Permissions.require(permissions, READ);
        return ApiResponse.ok(service.get(caseId));
    }

    @PutMapping
    public ApiResponse<PreopVisit> save(
            @PathVariable UUID caseId, @Valid @RequestBody PreopVisitReq req,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(value = AuthHeaders.ACTOR, defaultValue = "system") String actor) {
        Permissions.require(permissions, WRITE);
        return ApiResponse.ok(service.save(caseId, req, actor));
    }

    @PostMapping("/sign")
    public ApiResponse<PreopVisit> sign(
            @PathVariable UUID caseId, @Valid @RequestBody SurgeryVersionReq req,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(value = AuthHeaders.ACTOR, defaultValue = "system") String actor) {
        Permissions.require(permissions, WRITE);
        return ApiResponse.ok(service.sign(caseId, req.version(), actor));
    }
}
