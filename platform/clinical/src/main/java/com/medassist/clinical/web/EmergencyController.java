package com.medassist.clinical.web;

import com.medassist.clinical.service.EmergencyService;
import com.medassist.clinical.web.EmergencyDtos.BedView;
import com.medassist.clinical.web.EmergencyDtos.BoardView;
import com.medassist.clinical.web.EmergencyDtos.CreateTriageReq;
import com.medassist.clinical.web.EmergencyDtos.LeaveReq;
import com.medassist.clinical.web.EmergencyDtos.ObservationView;
import com.medassist.clinical.web.EmergencyDtos.ObserveReq;
import com.medassist.clinical.web.EmergencyDtos.OutcomeReq;
import com.medassist.clinical.web.EmergencyDtos.RegisterTriageReq;
import com.medassist.clinical.web.EmergencyDtos.TriageView;
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
 * 急诊分诊与留观 API（G36）。
 *
 * <p>权限点：
 * <ul>
 *   <li>{@code emergency:triage:read}——分诊队列、看板、床位与留观查询；</li>
 *   <li>{@code emergency:triage:create}——预检分诊录入与未挂号离院；</li>
 *   <li>{@code emergency:triage:register}——急诊挂号（生成 EMERGENCY 就诊）；</li>
 *   <li>{@code emergency:observation:manage}——留观入床与转归办理。</li>
 * </ul>
 *
 * <p>患者号/姓名按 {@link DataScope} 决定明文或掩码，掩码在服务端出口完成。
 */
@RestController
@RequestMapping("/clinical/emergency")
public class EmergencyController {

    private static final String READ = "emergency:triage:read";
    private static final String CREATE = "emergency:triage:create";
    private static final String REGISTER = "emergency:triage:register";
    private static final String OBSERVE = "emergency:observation:manage";

    private final EmergencyService service;

    public EmergencyController(EmergencyService service) {
        this.service = service;
    }

    @GetMapping("/board")
    public ApiResponse<BoardView> board(
            @RequestParam(required = false) UUID deptId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            LocalDate to,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions) {
        Permissions.require(permissions, READ);
        return ApiResponse.ok(service.board(deptId, from, to));
    }

    @GetMapping("/triage")
    public ApiResponse<List<TriageView>> triages(
            @RequestParam(required = false) UUID deptId,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            LocalDate to,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            DataScope scope) {
        Permissions.require(permissions, READ);
        return ApiResponse.ok(service.listTriages(deptId, status, from, to).stream()
                .map(t -> PhiMask.triage(t, scope))
                .toList());
    }

    @PostMapping("/triage")
    public ApiResponse<TriageView> createTriage(
            @Valid @RequestBody CreateTriageReq req,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(value = AuthHeaders.ACTOR, defaultValue = "system") String actor,
            DataScope scope) {
        Permissions.require(permissions, CREATE);
        return ApiResponse.ok(PhiMask.triage(service.createTriage(req, actor), scope));
    }

    @PostMapping("/triage/{id}/register")
    public ApiResponse<TriageView> register(
            @PathVariable UUID id,
            @Valid @RequestBody RegisterTriageReq req,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(value = AuthHeaders.ACTOR, defaultValue = "system") String actor,
            DataScope scope) {
        Permissions.require(permissions, REGISTER);
        return ApiResponse.ok(PhiMask.triage(service.register(id, req, actor), scope));
    }

    @PostMapping("/triage/{id}/leave")
    public ApiResponse<TriageView> leave(
            @PathVariable UUID id,
            @Valid @RequestBody LeaveReq req,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(value = AuthHeaders.ACTOR, defaultValue = "system") String actor,
            DataScope scope) {
        Permissions.require(permissions, CREATE);
        return ApiResponse.ok(PhiMask.triage(service.leave(id, req.reason(), actor), scope));
    }

    @GetMapping("/beds")
    public ApiResponse<List<BedView>> beds(
            @RequestParam(required = false) UUID deptId,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions) {
        Permissions.require(permissions, READ);
        return ApiResponse.ok(service.listBeds(deptId));
    }

    @GetMapping("/observations")
    public ApiResponse<List<ObservationView>> observations(
            @RequestParam(required = false) UUID deptId,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            LocalDate to,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            DataScope scope) {
        Permissions.require(permissions, READ);
        return ApiResponse.ok(service.listObservations(deptId, status, from, to).stream()
                .map(o -> PhiMask.observation(o, scope))
                .toList());
    }

    /** 进入留观：body 可为空，服务端自动选床。 */
    @PostMapping("/triage/{id}/observe")
    public ApiResponse<ObservationView> observe(
            @PathVariable UUID id,
            @RequestBody(required = false) ObserveReq req,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(value = AuthHeaders.ACTOR, defaultValue = "system") String actor,
            DataScope scope) {
        Permissions.require(permissions, OBSERVE);
        return ApiResponse.ok(PhiMask.observation(service.observe(id, req, actor), scope));
    }

    @PostMapping("/observations/{id}/outcome")
    public ApiResponse<ObservationView> outcome(
            @PathVariable UUID id,
            @Valid @RequestBody OutcomeReq req,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(value = AuthHeaders.ACTOR, defaultValue = "system") String actor,
            DataScope scope) {
        Permissions.require(permissions, OBSERVE);
        return ApiResponse.ok(PhiMask.observation(service.completeObservation(id, req, actor), scope));
    }
}
