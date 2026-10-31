package com.medassist.clinical.web;

import com.medassist.clinical.domain.ClinicalModel.Slot;
import com.medassist.clinical.domain.ClinicalModel.Visit;
import com.medassist.clinical.service.RegistrationService;
import com.medassist.clinical.web.ClinicalDtos.CreateSlotReq;
import com.medassist.clinical.web.ClinicalDtos.RegisterReq;
import com.medassist.common.api.ApiResponse;
import com.medassist.common.security.AuthHeaders;
import com.medassist.common.security.Permissions;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * 挂号 / 就诊 API。
 *
 * <p>权限点（由网关按 JWT 声明注入，客户端不可伪造）：
 * <ul>
 *   <li>{@code clinical:slot:manage}——号源维护（排班室/管理员）；</li>
 *   <li>{@code clinical:registration:create}——挂号（窗口）；</li>
 *   <li>{@code clinical:registration:cancel}——退号（窗口）；</li>
 *   <li>{@code clinical:visit:treat}——接诊/结束（医师）；</li>
 *   <li>{@code clinical:visit:read}——就诊查询（医师/护士/质控）。</li>
 * </ul>
 */
@RestController
@RequestMapping("/clinical")
public class RegistrationController {

    private static final String VISIT_READ = "clinical:visit:read";
    private static final String REG_CREATE = "clinical:registration:create";
    private static final String REG_CANCEL = "clinical:registration:cancel";
    private static final String VISIT_TREAT = "clinical:visit:treat";
    private static final String SLOT_MANAGE = "clinical:slot:manage";

    private final RegistrationService service;

    public RegistrationController(RegistrationService service) {
        this.service = service;
    }

    @PostMapping("/slots")
    public ApiResponse<Slot> createSlot(
            @Valid @RequestBody CreateSlotReq req,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(value = AuthHeaders.ACTOR, defaultValue = "system") String actor) {
        Permissions.require(permissions, SLOT_MANAGE);
        return ApiResponse.ok(service.createSlot(req, actor));
    }

    @PostMapping("/registrations")
    public ApiResponse<Visit> register(
            @Valid @RequestBody RegisterReq req,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(value = AuthHeaders.ACTOR, defaultValue = "system") String actor) {
        Permissions.require(permissions, REG_CREATE);
        return ApiResponse.ok(service.register(req, actor));
    }

    @GetMapping("/visits/{id}")
    public ApiResponse<Visit> detail(
            @PathVariable UUID id,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions) {
        Permissions.require(permissions, VISIT_READ);
        return ApiResponse.ok(service.detail(id));
    }

    @PostMapping("/visits/{id}/start")
    public ApiResponse<Visit> start(
            @PathVariable UUID id,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(value = AuthHeaders.ACTOR, defaultValue = "system") String actor) {
        Permissions.require(permissions, VISIT_TREAT);
        return ApiResponse.ok(service.start(id, actor));
    }

    @PostMapping("/visits/{id}/finish")
    public ApiResponse<Visit> finish(
            @PathVariable UUID id,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(value = AuthHeaders.ACTOR, defaultValue = "system") String actor) {
        Permissions.require(permissions, VISIT_TREAT);
        return ApiResponse.ok(service.finish(id, actor));
    }

    @PostMapping("/visits/{id}/cancel")
    public ApiResponse<Visit> cancel(
            @PathVariable UUID id,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(value = AuthHeaders.ACTOR, defaultValue = "system") String actor) {
        Permissions.require(permissions, REG_CANCEL);
        return ApiResponse.ok(service.cancel(id, actor));
    }
}
