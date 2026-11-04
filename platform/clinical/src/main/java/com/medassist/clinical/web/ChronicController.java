package com.medassist.clinical.web;

import com.medassist.clinical.domain.ClinicalModel.ChronicDisease;
import com.medassist.clinical.service.ChronicService;
import com.medassist.clinical.web.ChronicDtos.BoardView;
import com.medassist.clinical.web.ChronicDtos.CloseRecordReq;
import com.medassist.clinical.web.ChronicDtos.CompleteTaskReq;
import com.medassist.clinical.web.ChronicDtos.CreatePlanReq;
import com.medassist.clinical.web.ChronicDtos.CreateRecordReq;
import com.medassist.clinical.web.ChronicDtos.FollowupView;
import com.medassist.clinical.web.ChronicDtos.RecordSummaryView;
import com.medassist.clinical.web.ChronicDtos.RecordView;
import com.medassist.clinical.web.ChronicDtos.StopPlanReq;
import com.medassist.clinical.web.ChronicDtos.TaskView;
import com.medassist.clinical.web.ChronicDtos.UpdateRiskReq;
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
 * 随访与慢病 API（G38）。
 *
 * <p>权限点：
 * <ul>
 *   <li>{@code chronic:record:read/manage}——档案/趋势查看、建档/风险/结案；</li>
 *   <li>{@code chronic:plan:manage}——随访计划创建与停止；</li>
 *   <li>{@code chronic:followup:read/execute}——随访任务查看与完成。</li>
 * </ul>
 * 档案、任务与随访记录中的患者号/姓名按 {@link DataScope} 决定明文或掩码。
 */
@RestController
@RequestMapping("/clinical/chronic")
public class ChronicController {

    private static final String RECORD_READ = "chronic:record:read";
    private static final String RECORD_MANAGE = "chronic:record:manage";
    private static final String PLAN_MANAGE = "chronic:plan:manage";
    private static final String FOLLOWUP_READ = "chronic:followup:read";
    private static final String FOLLOWUP_EXECUTE = "chronic:followup:execute";

    private final ChronicService service;

    public ChronicController(ChronicService service) {
        this.service = service;
    }

    @GetMapping("/diseases")
    public ApiResponse<List<ChronicDisease>> diseases(
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions) {
        Permissions.require(permissions, RECORD_READ);
        return ApiResponse.ok(service.listDiseases());
    }

    @GetMapping("/records")
    public ApiResponse<List<RecordSummaryView>> records(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) UUID diseaseId,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "100") int limit,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            DataScope scope) {
        Permissions.require(permissions, RECORD_READ);
        return ApiResponse.ok(service.listRecords(status, diseaseId, keyword, limit).stream()
                .map(s -> PhiMask.chronicSummary(s, scope))
                .toList());
    }

    @GetMapping("/records/{id}")
    public ApiResponse<RecordView> recordDetail(
            @PathVariable UUID id,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            DataScope scope) {
        Permissions.require(permissions, RECORD_READ);
        return ApiResponse.ok(PhiMask.chronicRecord(service.detail(id), scope));
    }

    @PostMapping("/records")
    public ApiResponse<RecordView> createRecord(
            @Valid @RequestBody CreateRecordReq req,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(value = AuthHeaders.ACTOR, defaultValue = "system") String actor,
            DataScope scope) {
        Permissions.require(permissions, RECORD_MANAGE);
        return ApiResponse.ok(PhiMask.chronicRecord(service.createRecord(req, actor), scope));
    }

    @PostMapping("/records/{id}/risk")
    public ApiResponse<RecordView> updateRisk(
            @PathVariable UUID id,
            @Valid @RequestBody UpdateRiskReq req,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(value = AuthHeaders.ACTOR, defaultValue = "system") String actor,
            DataScope scope) {
        Permissions.require(permissions, RECORD_MANAGE);
        return ApiResponse.ok(PhiMask.chronicRecord(service.updateRisk(id, req, actor), scope));
    }

    @PostMapping("/records/{id}/close")
    public ApiResponse<RecordView> closeRecord(
            @PathVariable UUID id,
            @Valid @RequestBody CloseRecordReq req,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(value = AuthHeaders.ACTOR, defaultValue = "system") String actor,
            DataScope scope) {
        Permissions.require(permissions, RECORD_MANAGE);
        return ApiResponse.ok(PhiMask.chronicRecord(
                service.closeRecord(id, req.reason(), actor), scope));
    }

    @PostMapping("/records/{id}/plan")
    public ApiResponse<RecordView> createPlan(
            @PathVariable UUID id,
            @Valid @RequestBody CreatePlanReq req,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(value = AuthHeaders.ACTOR, defaultValue = "system") String actor,
            DataScope scope) {
        Permissions.require(permissions, PLAN_MANAGE);
        return ApiResponse.ok(PhiMask.chronicRecord(service.createPlan(id, req, actor), scope));
    }

    @PostMapping("/plans/{id}/stop")
    public ApiResponse<RecordView> stopPlan(
            @PathVariable UUID id,
            @Valid @RequestBody StopPlanReq req,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(value = AuthHeaders.ACTOR, defaultValue = "system") String actor,
            DataScope scope) {
        Permissions.require(permissions, PLAN_MANAGE);
        return ApiResponse.ok(PhiMask.chronicRecord(
                service.stopPlan(id, req.reason(), actor), scope));
    }

    @GetMapping("/tasks")
    public ApiResponse<List<TaskView>> tasks(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            LocalDate dueBefore,
            @RequestParam(defaultValue = "100") int limit,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            DataScope scope) {
        Permissions.require(permissions, FOLLOWUP_READ);
        return ApiResponse.ok(service.listTasks(status, dueBefore, limit).stream()
                .map(t -> PhiMask.chronicTask(t, scope))
                .toList());
    }

    @PostMapping("/tasks/{id}/complete")
    public ApiResponse<RecordView> completeTask(
            @PathVariable UUID id,
            @Valid @RequestBody CompleteTaskReq req,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(value = AuthHeaders.ACTOR, defaultValue = "system") String actor,
            DataScope scope) {
        Permissions.require(permissions, FOLLOWUP_EXECUTE);
        return ApiResponse.ok(PhiMask.chronicRecord(
                service.completeTask(id, req, actor), scope));
    }

    @GetMapping("/records/{id}/followups")
    public ApiResponse<List<FollowupView>> followups(
            @PathVariable UUID id,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            LocalDate to,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            DataScope scope) {
        Permissions.require(permissions, FOLLOWUP_READ);
        return ApiResponse.ok(service.listFollowups(id, from, to).stream()
                .map(f -> PhiMask.chronicFollowup(f, scope))
                .toList());
    }

    @GetMapping("/board")
    public ApiResponse<BoardView> board(
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions) {
        Permissions.require(permissions, RECORD_READ);
        return ApiResponse.ok(service.board());
    }
}
