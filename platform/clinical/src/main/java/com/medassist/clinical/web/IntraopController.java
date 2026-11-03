package com.medassist.clinical.web;

import com.medassist.clinical.domain.ClinicalModel.AnesthesiaVital;
import com.medassist.clinical.domain.ClinicalModel.IntraopNursing;
import com.medassist.clinical.service.IntraopService;
import com.medassist.clinical.service.VisitAccessGuard;
import com.medassist.clinical.web.SurgeryDtos.SurgeryVersionReq;
import com.medassist.clinical.web.TheatreDtos.AnesthesiaReq;
import com.medassist.clinical.web.TheatreDtos.AnesthesiaView;
import com.medassist.clinical.web.TheatreDtos.NursingReq;
import com.medassist.clinical.web.TheatreDtos.VitalReq;
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

/** 术中记录 API：麻醉记录单、术中生命体征、术中护理。 */
@RestController
@RequestMapping("/clinical/surgery-cases")
public class IntraopController {

    private static final String READ = "clinical:surgery:read";
    private static final String WRITE = "clinical:surgery:write";

    private final IntraopService service;
    private final VisitAccessGuard guard;

    public IntraopController(IntraopService service, VisitAccessGuard guard) {
        this.service = service;
        this.guard = guard;
    }

    // ---- 麻醉

    @GetMapping("/{caseId}/anesthesia")
    public ApiResponse<AnesthesiaView> getAnesthesia(
            @PathVariable UUID caseId,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            DataScope scope) {
        Permissions.require(permissions, READ);
        guard.requireCase(scope, caseId);
        return ApiResponse.ok(service.getAnesthesia(caseId));
    }

    @PutMapping("/{caseId}/anesthesia")
    public ApiResponse<AnesthesiaView> saveAnesthesia(
            @PathVariable UUID caseId, @Valid @RequestBody AnesthesiaReq req,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(value = AuthHeaders.ACTOR, defaultValue = "system") String actor,
            DataScope scope) {
        Permissions.require(permissions, WRITE);
        guard.requireCase(scope, caseId);
        return ApiResponse.ok(service.saveAnesthesia(caseId, req, actor));
    }

    @PostMapping("/{caseId}/anesthesia/sign")
    public ApiResponse<AnesthesiaView> signAnesthesia(
            @PathVariable UUID caseId, @Valid @RequestBody SurgeryVersionReq req,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(value = AuthHeaders.ACTOR, defaultValue = "system") String actor,
            DataScope scope) {
        Permissions.require(permissions, WRITE);
        guard.requireCase(scope, caseId);
        return ApiResponse.ok(service.signAnesthesia(caseId, req.version(), actor));
    }

    @GetMapping("/{caseId}/anesthesia/vitals")
    public ApiResponse<List<AnesthesiaVital>> vitals(
            @PathVariable UUID caseId,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            DataScope scope) {
        Permissions.require(permissions, READ);
        guard.requireCase(scope, caseId);
        return ApiResponse.ok(service.vitals(caseId));
    }

    @PostMapping("/{caseId}/anesthesia/vitals")
    public ApiResponse<AnesthesiaVital> addVital(
            @PathVariable UUID caseId, @Valid @RequestBody VitalReq req,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(value = AuthHeaders.ACTOR, defaultValue = "system") String actor,
            DataScope scope) {
        Permissions.require(permissions, WRITE);
        guard.requireCase(scope, caseId);
        return ApiResponse.ok(service.addVital(caseId, req, actor));
    }

    // ---- 术中护理

    @GetMapping("/{caseId}/nursing")
    public ApiResponse<IntraopNursing> getNursing(
            @PathVariable UUID caseId,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            DataScope scope) {
        Permissions.require(permissions, READ);
        guard.requireCase(scope, caseId);
        return ApiResponse.ok(service.getNursing(caseId));
    }

    @PutMapping("/{caseId}/nursing")
    public ApiResponse<IntraopNursing> saveNursing(
            @PathVariable UUID caseId, @Valid @RequestBody NursingReq req,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(value = AuthHeaders.ACTOR, defaultValue = "system") String actor,
            DataScope scope) {
        Permissions.require(permissions, WRITE);
        guard.requireCase(scope, caseId);
        return ApiResponse.ok(service.saveNursing(caseId, req, actor));
    }

    @PostMapping("/{caseId}/nursing/sign")
    public ApiResponse<IntraopNursing> signNursing(
            @PathVariable UUID caseId, @Valid @RequestBody SurgeryVersionReq req,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(value = AuthHeaders.ACTOR, defaultValue = "system") String actor,
            DataScope scope) {
        Permissions.require(permissions, WRITE);
        guard.requireCase(scope, caseId);
        return ApiResponse.ok(service.signNursing(caseId, req.version(), actor));
    }
}
