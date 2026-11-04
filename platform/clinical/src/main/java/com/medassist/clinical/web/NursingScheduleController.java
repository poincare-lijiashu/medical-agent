package com.medassist.clinical.web;

import com.medassist.clinical.service.NursingScheduleService;
import com.medassist.clinical.web.NursingScheduleDtos.BatchResult;
import com.medassist.clinical.web.NursingScheduleDtos.BatchScheduleReq;
import com.medassist.clinical.web.NursingScheduleDtos.CancelReq;
import com.medassist.clinical.web.NursingScheduleDtos.PublishResult;
import com.medassist.clinical.web.NursingScheduleDtos.ScheduleView;
import com.medassist.clinical.web.NursingScheduleDtos.ShiftView;
import com.medassist.clinical.web.NursingScheduleDtos.WorkloadView;
import com.medassist.common.api.ApiResponse;
import com.medassist.common.security.AuthHeaders;
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
 * 护理排班 API（G26）。
 *
 * <p>查询沿用 {@code inpatient:nursing:read}；录入/发布/取消需要
 * {@code inpatient:nursing:schedule}（护士长/管理员），避免执行护士自行改班。
 */
@RestController
@RequestMapping("/clinical/nursing")
public class NursingScheduleController {

    private static final String READ = "inpatient:nursing:read";
    private static final String MANAGE = "inpatient:nursing:schedule";

    private final NursingScheduleService service;

    public NursingScheduleController(NursingScheduleService service) {
        this.service = service;
    }

    @GetMapping("/shifts")
    public ApiResponse<List<ShiftView>> shifts(
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions) {
        Permissions.require(permissions, READ);
        return ApiResponse.ok(service.listShifts());
    }

    @GetMapping("/schedules")
    public ApiResponse<List<ScheduleView>> schedules(
            @RequestParam(required = false) UUID deptId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            LocalDate to,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions) {
        Permissions.require(permissions, READ);
        return ApiResponse.ok(service.list(deptId, from, to));
    }

    /** 批量录入（重复排班跳过，返回 created/skipped）。 */
    @PostMapping("/schedules")
    public ApiResponse<BatchResult> create(
            @Valid @RequestBody BatchScheduleReq req,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(value = AuthHeaders.ACTOR, defaultValue = "system") String actor) {
        Permissions.require(permissions, MANAGE);
        return ApiResponse.ok(service.create(req, actor));
    }

    @PostMapping("/schedules/{id}/cancel")
    public ApiResponse<ScheduleView> cancel(
            @PathVariable UUID id,
            @Valid @RequestBody CancelReq req,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(value = AuthHeaders.ACTOR, defaultValue = "system") String actor) {
        Permissions.require(permissions, MANAGE);
        return ApiResponse.ok(service.cancel(id, req.reason(), actor));
    }

    /** 发布某科室某日全部草稿排班。 */
    @PostMapping("/schedules/publish")
    public ApiResponse<PublishResult> publish(
            @RequestParam UUID deptId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(value = AuthHeaders.ACTOR, defaultValue = "system") String actor) {
        Permissions.require(permissions, MANAGE);
        return ApiResponse.ok(service.publish(deptId, date, actor));
    }

    @GetMapping("/workload")
    public ApiResponse<WorkloadView> workload(
            @RequestParam(required = false) UUID deptId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            LocalDate to,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions) {
        Permissions.require(permissions, READ);
        return ApiResponse.ok(service.workload(deptId, from, to));
    }
}
