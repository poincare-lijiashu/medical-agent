package com.medassist.clinical.web;

import com.medassist.clinical.domain.ClinicalModel.DiagnosisDef;
import com.medassist.clinical.domain.ClinicalModel.VisitDiagnosis;
import com.medassist.clinical.service.DiagnosisService;
import com.medassist.clinical.service.VisitAccessGuard;
import com.medassist.clinical.web.ClinicalDtos.AddDiagnosisReq;
import com.medassist.common.api.ApiResponse;
import com.medassist.common.security.AuthHeaders;
import com.medassist.common.security.DataScope;
import com.medassist.common.security.Permissions;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
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
 * 诊断（ICD-10）API。
 *
 * <p>分两类端点：
 * <ul>
 *   <li>{@code GET /clinical/diagnoses}——诊断字典检索（医师下达时选择）；</li>
 *   <li>{@code /clinical/visits/{visitId}/diagnoses}——就诊诊断的增删与主诊断调整。</li>
 * </ul>
 *
 * <p>权限：下达/调整需要 {@code clinical:diagnosis:write}；查询 {@code clinical:diagnosis:read}。
 */
@RestController
@RequestMapping("/clinical")
public class DiagnosisController {

    private static final String READ = "clinical:diagnosis:read";
    private static final String WRITE = "clinical:diagnosis:write";

    private final DiagnosisService service;
    private final VisitAccessGuard guard;

    public DiagnosisController(DiagnosisService service, VisitAccessGuard guard) {
        this.service = service;
        this.guard = guard;
    }

    @GetMapping("/diagnoses")
    public ApiResponse<List<DiagnosisDef>> catalog(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) Integer limit,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions) {
        Permissions.require(permissions, READ);
        return ApiResponse.ok(service.catalog(keyword, limit));
    }

    @GetMapping("/visits/{visitId}/diagnoses")
    public ApiResponse<List<VisitDiagnosis>> list(
            @PathVariable UUID visitId,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            DataScope scope) {
        Permissions.require(permissions, READ);
        // 只读：本科室，或受邀会诊进行中（会诊需要看主诊科室下的诊断）
        guard.requireRead(scope, visitId);
        return ApiResponse.ok(service.list(visitId));
    }

    @PostMapping("/visits/{visitId}/diagnoses")
    public ApiResponse<List<VisitDiagnosis>> add(
            @PathVariable UUID visitId,
            @Valid @RequestBody AddDiagnosisReq req,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(value = AuthHeaders.ACTOR, defaultValue = "system") String actor,
            DataScope scope) {
        Permissions.require(permissions, WRITE);
        guard.require(scope, visitId);
        return ApiResponse.ok(service.add(visitId, req, actor));
    }

    /** 设为主诊断（系统保证只有一个，重复设置幂等）。 */
    @PostMapping("/visits/{visitId}/diagnoses/{diagnosisId}/primary")
    public ApiResponse<List<VisitDiagnosis>> setPrimary(
            @PathVariable UUID visitId,
            @PathVariable UUID diagnosisId,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(value = AuthHeaders.ACTOR, defaultValue = "system") String actor,
            DataScope scope) {
        Permissions.require(permissions, WRITE);
        guard.require(scope, visitId);
        return ApiResponse.ok(service.setPrimary(visitId, diagnosisId, actor));
    }

    @DeleteMapping("/visits/{visitId}/diagnoses/{diagnosisId}")
    public ApiResponse<List<VisitDiagnosis>> remove(
            @PathVariable UUID visitId,
            @PathVariable UUID diagnosisId,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(value = AuthHeaders.ACTOR, defaultValue = "system") String actor,
            DataScope scope) {
        Permissions.require(permissions, WRITE);
        guard.require(scope, visitId);
        return ApiResponse.ok(service.remove(visitId, diagnosisId, actor));
    }
}
