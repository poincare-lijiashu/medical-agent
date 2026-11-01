package com.medassist.clinical.web;

import com.medassist.clinical.domain.ClinicalModel.OperatingRoom;
import com.medassist.clinical.domain.ClinicalModel.SurgerySchedule;
import com.medassist.clinical.service.TheatreService;
import com.medassist.clinical.web.TheatreDtos.CancelScheduleReq;
import com.medassist.clinical.web.TheatreDtos.CreateRoomReq;
import com.medassist.clinical.web.TheatreDtos.RoomStatusReq;
import com.medassist.clinical.web.TheatreDtos.RoomUtilization;
import com.medassist.clinical.web.TheatreDtos.ScheduleReq;
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
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

/**
 * 手术间与排台 API。
 *
 * <p>权限沿用手术域：读 {@code clinical:surgery:read}，写 {@code clinical:surgery:write}。
 * 排台冲突、取消必须写原因等规则在服务层强制（接口只是入口）。
 */
@RestController
@RequestMapping("/clinical")
public class TheatreController {

    private static final String READ = "clinical:surgery:read";
    private static final String WRITE = "clinical:surgery:write";

    private final TheatreService service;

    public TheatreController(TheatreService service) {
        this.service = service;
    }

    @GetMapping("/operating-rooms")
    public ApiResponse<List<OperatingRoom>> rooms(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Integer limit,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions) {
        Permissions.require(permissions, READ);
        return ApiResponse.ok(service.rooms(status, limit));
    }

    @PostMapping("/operating-rooms")
    public ApiResponse<OperatingRoom> createRoom(
            @Valid @RequestBody CreateRoomReq req,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(value = AuthHeaders.ACTOR, defaultValue = "system") String actor) {
        Permissions.require(permissions, WRITE);
        return ApiResponse.ok(service.createRoom(req, actor));
    }

    @PostMapping("/operating-rooms/{roomId}/status")
    public ApiResponse<OperatingRoom> setRoomStatus(
            @PathVariable UUID roomId, @Valid @RequestBody RoomStatusReq req,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(value = AuthHeaders.ACTOR, defaultValue = "system") String actor) {
        Permissions.require(permissions, WRITE);
        return ApiResponse.ok(service.setRoomStatus(roomId, req, actor));
    }

    /** 手术间看板：某天该房间的排台（不传 date 取今天）。 */
    @GetMapping("/operating-rooms/{roomId}/schedules")
    public ApiResponse<List<SurgerySchedule>> board(
            @PathVariable UUID roomId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            LocalDate date,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions) {
        Permissions.require(permissions, READ);
        return ApiResponse.ok(service.board(roomId, date));
    }

    @GetMapping("/visits/{visitId}/surgery-cases/{caseId}/schedule")
    public ApiResponse<SurgerySchedule> get(
            @PathVariable UUID visitId, @PathVariable UUID caseId,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions) {
        Permissions.require(permissions, READ);
        return ApiResponse.ok(service.getByCase(visitId, caseId));
    }

    @PostMapping("/visits/{visitId}/surgery-cases/{caseId}/schedule")
    public ApiResponse<SurgerySchedule> schedule(
            @PathVariable UUID visitId, @PathVariable UUID caseId,
            @Valid @RequestBody ScheduleReq req,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(value = AuthHeaders.ACTOR, defaultValue = "system") String actor) {
        Permissions.require(permissions, WRITE);
        return ApiResponse.ok(service.schedule(visitId, caseId, req, actor));
    }

    @PostMapping("/visits/{visitId}/surgery-cases/{caseId}/schedule/cancel")
    public ApiResponse<SurgerySchedule> cancel(
            @PathVariable UUID visitId, @PathVariable UUID caseId,
            @Valid @RequestBody CancelScheduleReq req,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(value = AuthHeaders.ACTOR, defaultValue = "system") String actor) {
        Permissions.require(permissions, WRITE);
        return ApiResponse.ok(service.cancel(visitId, caseId, req, actor));
    }

    /**
     * 单手术间利用率与接台周转（按天）。
     *
     * <p>不传 from/to 默认最近 7 天；不传 openFrom/openTo 默认 08:00–18:00。
     * 区间上限 92 天，超过直接 422（避免一次拉全年把库拖垮）。
     */
    @GetMapping("/operating-rooms/{roomId}/utilization")
    public ApiResponse<RoomUtilization> utilization(
            @PathVariable UUID roomId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            LocalDate to,
            @RequestParam(required = false) @DateTimeFormat(pattern = "HH:mm") LocalTime openFrom,
            @RequestParam(required = false) @DateTimeFormat(pattern = "HH:mm") LocalTime openTo,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions) {
        Permissions.require(permissions, READ);
        return ApiResponse.ok(service.utilization(roomId, from, to, openFrom, openTo));
    }

    /** 全院手术间利用率（只统计 ACTIVE 手术间）。 */
    @GetMapping("/surgery/utilization")
    public ApiResponse<List<RoomUtilization>> hospitalUtilization(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            LocalDate to,
            @RequestParam(required = false) @DateTimeFormat(pattern = "HH:mm") LocalTime openFrom,
            @RequestParam(required = false) @DateTimeFormat(pattern = "HH:mm") LocalTime openTo,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions) {
        Permissions.require(permissions, READ);
        return ApiResponse.ok(service.hospitalUtilization(from, to, openFrom, openTo));
    }
}
