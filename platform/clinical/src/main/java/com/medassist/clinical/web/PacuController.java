package com.medassist.clinical.web;

import com.medassist.clinical.domain.ClinicalModel.PacuBoardRow;
import com.medassist.clinical.domain.ClinicalModel.PacuObservation;
import com.medassist.clinical.service.PacuService;
import com.medassist.clinical.service.VisitAccessGuard;
import com.medassist.clinical.web.PacuDtos.AdmitReq;
import com.medassist.clinical.web.PacuDtos.DischargeReq;
import com.medassist.clinical.web.PacuDtos.ObservationReq;
import com.medassist.clinical.web.PacuDtos.PacuView;
import com.medassist.clinical.web.PacuDtos.SignReq;
import com.medassist.common.api.ApiResponse;
import com.medassist.common.security.AuthHeaders;
import com.medassist.common.security.DataScope;
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
 * 麻醉复苏（PACU）API：入室 → Aldrete 观察 → 出室 → 签名，以及复苏室看板。
 *
 * <p>所有按手术病例的接口先过 {@link VisitAccessGuard#requireCase}（科室 + 院区），
 * 越权与不存在同码返回 404，避免用 caseId 枚举其他科室患者。
 */
@RestController
@RequestMapping("/clinical")
public class PacuController {

    private static final String READ = "clinical:surgery:read";
    private static final String WRITE = "clinical:surgery:write";

    private final PacuService service;
    private final VisitAccessGuard guard;

    public PacuController(PacuService service, VisitAccessGuard guard) {
        this.service = service;
        this.guard = guard;
    }

    // ---- 单台手术的复苏记录

    @GetMapping("/surgery-cases/{caseId}/pacu")
    public ApiResponse<PacuView> get(
            @PathVariable UUID caseId,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            DataScope scope) {
        Permissions.require(permissions, READ);
        guard.requireCase(scope, caseId);
        return ApiResponse.ok(service.get(caseId));
    }

    @PostMapping("/surgery-cases/{caseId}/pacu")
    public ApiResponse<PacuView> admit(
            @PathVariable UUID caseId, @Valid @RequestBody AdmitReq req,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(value = AuthHeaders.ACTOR, defaultValue = "system") String actor,
            DataScope scope) {
        Permissions.require(permissions, WRITE);
        guard.requireCase(scope, caseId);
        return ApiResponse.ok(service.admit(caseId, req, actor));
    }

    @PostMapping("/surgery-cases/{caseId}/pacu/observations")
    public ApiResponse<PacuObservation> addObservation(
            @PathVariable UUID caseId, @Valid @RequestBody ObservationReq req,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(value = AuthHeaders.ACTOR, defaultValue = "system") String actor,
            DataScope scope) {
        Permissions.require(permissions, WRITE);
        guard.requireCase(scope, caseId);
        return ApiResponse.ok(service.addObservation(caseId, req, actor));
    }

    @PostMapping("/surgery-cases/{caseId}/pacu/discharge")
    public ApiResponse<PacuView> discharge(
            @PathVariable UUID caseId, @Valid @RequestBody DischargeReq req,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(value = AuthHeaders.ACTOR, defaultValue = "system") String actor,
            DataScope scope) {
        Permissions.require(permissions, WRITE);
        guard.requireCase(scope, caseId);
        return ApiResponse.ok(service.discharge(caseId, req, actor));
    }

    @PostMapping("/surgery-cases/{caseId}/pacu/sign")
    public ApiResponse<PacuView> sign(
            @PathVariable UUID caseId, @Valid @RequestBody SignReq req,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(value = AuthHeaders.ACTOR, defaultValue = "system") String actor,
            DataScope scope) {
        Permissions.require(permissions, WRITE);
        guard.requireCase(scope, caseId);
        return ApiResponse.ok(service.sign(caseId, req.version(), actor));
    }

    // ---- 复苏室看板

    @GetMapping("/pacu/stays")
    public ApiResponse<List<PacuBoardRow>> board(
            @RequestParam(required = false) String status,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam(defaultValue = "100") int limit,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            DataScope scope) {
        Permissions.require(permissions, READ);
        return ApiResponse.ok(service.board(status, date, limit, scope));
    }
}
