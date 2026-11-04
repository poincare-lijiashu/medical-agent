package com.medassist.clinical.web;

import com.medassist.clinical.domain.ClinicalModel.MobileBoardRow;
import com.medassist.clinical.domain.ClinicalModel.MobileNursingTask;
import com.medassist.clinical.domain.ClinicalModel.MobileScanEvent;
import com.medassist.clinical.service.MobileNursingService;
import com.medassist.clinical.web.MobileNursingDtos.BoardView;
import com.medassist.clinical.web.MobileNursingDtos.ExceptionReq;
import com.medassist.clinical.web.MobileNursingDtos.GenerateFromOrdersReq;
import com.medassist.clinical.web.MobileNursingDtos.GenerateResult;
import com.medassist.clinical.web.MobileNursingDtos.ManualTaskReq;
import com.medassist.clinical.web.MobileNursingDtos.ScanView;
import com.medassist.clinical.web.MobileNursingDtos.TaskView;
import com.medassist.clinical.web.MobileNursingDtos.VerificationResult;
import com.medassist.clinical.web.MobileNursingDtos.VerifyReq;
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
 * 移动护理与条码闭环 API（G39）。
 *
 * <p>权限点：{@code mobile:nursing:read} 查看任务与扫码记录，
 * {@code mobile:nursing:execute} 扫码核对/异常登记，
 * {@code mobile:nursing:manage} 从医嘱生成任务与手工建单。
 *
 * <p>任务视图不返回期望条码；扫码比对在服务端完成。
 */
@RestController
@RequestMapping("/clinical/mobile")
public class MobileNursingController {

    private static final String READ = "mobile:nursing:read";
    private static final String EXECUTE = "mobile:nursing:execute";
    private static final String MANAGE = "mobile:nursing:manage";

    private final MobileNursingService service;

    public MobileNursingController(MobileNursingService service) {
        this.service = service;
    }

    @GetMapping("/tasks")
    public ApiResponse<List<TaskView>> tasks(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            LocalDate date,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String taskType,
            @RequestParam(required = false) UUID wardId,
            @RequestParam(defaultValue = "100") int limit,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            DataScope scope) {
        Permissions.require(permissions, READ);
        return ApiResponse.ok(service.listTasks(date, status, taskType, wardId, limit).stream()
                .map(t -> view(t, scope))
                .toList());
    }

    @PostMapping("/tasks/from-orders")
    public ApiResponse<GenerateResult> generate(
            @Valid @RequestBody GenerateFromOrdersReq req,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(value = AuthHeaders.ACTOR, defaultValue = "system") String actor,
            DataScope scope) {
        Permissions.require(permissions, MANAGE);
        var outcome = service.generateFromOrders(req, actor);
        return ApiResponse.ok(new GenerateResult(outcome.created(), outcome.skipped(),
                outcome.tasks().stream().map(t -> view(t, scope)).toList()));
    }

    @PostMapping("/tasks")
    public ApiResponse<TaskView> createManual(
            @Valid @RequestBody ManualTaskReq req,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(value = AuthHeaders.ACTOR, defaultValue = "system") String actor,
            DataScope scope) {
        Permissions.require(permissions, MANAGE);
        return ApiResponse.ok(view(service.createManualTask(req, actor), scope));
    }

    @PostMapping("/tasks/{id}/verify")
    public ApiResponse<VerificationResult> verify(
            @PathVariable UUID id,
            @Valid @RequestBody VerifyReq req,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(value = AuthHeaders.ACTOR, defaultValue = "system") String actor,
            DataScope scope) {
        Permissions.require(permissions, EXECUTE);
        var outcome = service.verify(id, req.patientBarcode(), req.itemBarcode(), actor);
        return ApiResponse.ok(new VerificationResult(outcome.matched(),
                outcome.patientMatched(), outcome.itemMatched(), outcome.message(),
                view(outcome.task(), scope)));
    }

    @PostMapping("/tasks/{id}/exception")
    public ApiResponse<TaskView> exception(
            @PathVariable UUID id,
            @Valid @RequestBody ExceptionReq req,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(value = AuthHeaders.ACTOR, defaultValue = "system") String actor,
            DataScope scope) {
        Permissions.require(permissions, EXECUTE);
        return ApiResponse.ok(view(service.markException(id, req.reason(), actor), scope));
    }

    @GetMapping("/tasks/{id}/scans")
    public ApiResponse<List<ScanView>> scans(
            @PathVariable UUID id,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            DataScope scope) {
        Permissions.require(permissions, READ);
        return ApiResponse.ok(service.listScans(id).stream()
                .map(e -> scan(e, scope))
                .toList());
    }

    @GetMapping("/board")
    public ApiResponse<BoardView> board(
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions) {
        Permissions.require(permissions, READ);
        MobileBoardRow row = service.board();
        return ApiResponse.ok(new BoardView(row.pending(), row.verifiedToday(),
                row.exceptionsToday(), row.totalToday()));
    }

    // ---------------------------------------------------------------- 视图与脱敏

    private static TaskView view(MobileNursingTask t, DataScope scope) {
        String patientNo = scope.phiReadable() ? t.patientNo()
                : scope.maskPatientNo(t.patientNo());
        String patientName = scope.phiReadable() ? t.patientName()
                : scope.maskPatientName(t.patientName());
        return new TaskView(t.id(), t.taskNo(), t.taskDate(), t.taskType(), t.patientId(),
                patientNo, patientName, t.wardName(), t.bedNo(), t.itemCode(), t.itemName(),
                t.sourceType(), t.sourceRef(), t.status(), t.attempts(), t.lastScannedAt(),
                t.verifiedBy(), t.verifiedAt(), t.exceptionReason(), t.exceptionBy(),
                t.exceptionAt(), t.note(), t.createdAt(), t.version());
    }

    private static ScanView scan(MobileScanEvent e, DataScope scope) {
        String value = "PATIENT".equals(e.scanType()) && !scope.phiReadable()
                ? scope.maskPatientNo(e.scannedValue()) : e.scannedValue();
        return new ScanView(e.id(), e.taskId(), e.scanType(), value, e.matched(),
                e.scannedBy(), e.scannedAt(), e.note());
    }
}
