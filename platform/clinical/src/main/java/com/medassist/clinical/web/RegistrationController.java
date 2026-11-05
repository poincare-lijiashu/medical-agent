package com.medassist.clinical.web;

import com.medassist.clinical.domain.ClinicalModel.Slot;
import com.medassist.clinical.domain.ClinicalModel.Visit;
import com.medassist.clinical.service.RegistrationService;
import com.medassist.clinical.service.VisitAccessGuard;
import com.medassist.clinical.web.ClinicalDtos.CreateSlotReq;
import com.medassist.clinical.web.ClinicalDtos.RegisterReq;
import com.medassist.common.api.ApiResponse;
import com.medassist.common.api.ErrorCode;
import com.medassist.common.exception.BusinessException;
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
    private final VisitAccessGuard guard;

    public RegistrationController(RegistrationService service, VisitAccessGuard guard) {
        this.service = service;
        this.guard = guard;
    }

    @PostMapping("/slots")
    public ApiResponse<Slot> createSlot(
            @Valid @RequestBody CreateSlotReq req,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(value = AuthHeaders.ACTOR, defaultValue = "system") String actor,
            DataScope scope) {
        Permissions.require(permissions, SLOT_MANAGE);
        if (!scope.canSeeDepartment(req.deptId())) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "只能维护本科室的号源");
        }
        return ApiResponse.ok(service.createSlot(req, actor));
    }

    @PostMapping("/registrations")
    public ApiResponse<Visit> register(
            @Valid @RequestBody RegisterReq req,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(value = AuthHeaders.ACTOR, defaultValue = "system") String actor,
            DataScope scope) {
        Permissions.require(permissions, REG_CREATE);
        if (!scope.canSeeDepartment(req.deptId())) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "只能为本科室挂号");
        }
        return ApiResponse.ok(PhiMask.visit(service.register(req, actor, scope.campusForWrite()), scope));
    }

    /**
     * 患者最近就诊列表（控制台选择就诊上下文）。
     *
     * <p>{@code patientId} 必填；只返回非退号记录，按就诊日期倒序。
     * 同时按数据范围过滤：医师只能看到本科室就诊，避免用 patientId 枚举其他科室的就诊史。
     * 科室条件作为 SQL 谓词下推（G74）：LIMIT 之后再做内存过滤会让不可见的他科就诊
     * 占掉 limit 额度，本科室更早的就诊被静默丢弃。
     */
    @GetMapping("/visits")
    public ApiResponse<java.util.List<Visit>> listByPatient(
            @RequestParam java.util.UUID patientId,
            @RequestParam(defaultValue = "20") int limit,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            DataScope scope) {
        Permissions.require(permissions, VISIT_READ);
        return ApiResponse.ok(service.listByPatient(patientId, limit, scope.campusFilter(),
                        scope.allDepartments() ? null : scope.deptCodes()).stream()
                .map(v -> PhiMask.visit(v, scope))
                .toList());
    }

    /**
     * 号源列表（挂号工作台）。
     *
     * <p>只返回本科室范围内的号源：跨科室排班属于排班室职责，挂号窗口没有必要看到。
     */
    @GetMapping("/slots")
    public ApiResponse<java.util.List<Slot>> listSlots(
            @RequestParam UUID deptId,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) java.time.LocalDate date,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            DataScope scope) {
        Permissions.require(permissions, REG_CREATE);
        if (!scope.canSeeDepartment(deptId)) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "无权查看其他科室的号源");
        }
        return ApiResponse.ok(service.listSlots(deptId, date));
    }

    /** 当日就诊列表（挂号工作台左/右栏），按挂号先后排序。 */
    @GetMapping("/registrations/daily")
    public ApiResponse<java.util.List<Visit>> dailyVisits(
            @RequestParam UUID deptId,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) java.time.LocalDate date,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            DataScope scope) {
        Permissions.require(permissions, VISIT_READ);
        if (!scope.canSeeDepartment(deptId)) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "无权查看其他科室的就诊");
        }
        return ApiResponse.ok(service.listDailyVisits(deptId, date, scope.campusFilter()).stream()
                .map(v -> PhiMask.visit(v, scope))
                .toList());
    }

    @GetMapping("/visits/{id}")
    public ApiResponse<Visit> detail(
            @PathVariable UUID id,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            DataScope scope) {
        Permissions.require(permissions, VISIT_READ);
        // 只读：本科室，或受邀会诊进行中（会诊医师需要看到患者当前就诊信息）
        guard.requireRead(scope, id);
        return ApiResponse.ok(PhiMask.visit(service.detail(id), scope));
    }

    @PostMapping("/visits/{id}/start")
    public ApiResponse<Visit> start(
            @PathVariable UUID id,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(value = AuthHeaders.ACTOR, defaultValue = "system") String actor,
            DataScope scope) {
        Permissions.require(permissions, VISIT_TREAT);
        guard.require(scope, id);
        return ApiResponse.ok(PhiMask.visit(service.start(id, actor), scope));
    }

    @PostMapping("/visits/{id}/finish")
    public ApiResponse<Visit> finish(
            @PathVariable UUID id,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(value = AuthHeaders.ACTOR, defaultValue = "system") String actor,
            DataScope scope) {
        Permissions.require(permissions, VISIT_TREAT);
        guard.require(scope, id);
        return ApiResponse.ok(PhiMask.visit(service.finish(id, actor), scope));
    }

    @PostMapping("/visits/{id}/cancel")
    public ApiResponse<Visit> cancel(
            @PathVariable UUID id,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(value = AuthHeaders.ACTOR, defaultValue = "system") String actor,
            DataScope scope) {
        Permissions.require(permissions, REG_CANCEL);
        guard.require(scope, id);
        return ApiResponse.ok(PhiMask.visit(service.cancel(id, actor), scope));
    }

    /**
     * 改约（G1）：把未接诊的就诊换到另一个号源（同科室）。
     *
     * <p>权限点用 {@code clinical:registration:create}：改约本质是"重新选号"，
     * 与挂号窗口同岗；跨科室改约不支持，需先退号（{@code registration:cancel}）。
     */
    @PostMapping("/visits/{id}/reschedule")
    public ApiResponse<Visit> reschedule(
            @PathVariable UUID id,
            @Valid @RequestBody ClinicalDtos.RescheduleReq req,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(value = AuthHeaders.ACTOR, defaultValue = "system") String actor,
            DataScope scope) {
        Permissions.require(permissions, REG_CREATE);
        guard.require(scope, id);
        return ApiResponse.ok(PhiMask.visit(service.reschedule(id, req.slotId(), actor), scope));
    }
}
