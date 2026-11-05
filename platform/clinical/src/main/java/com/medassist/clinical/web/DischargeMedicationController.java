package com.medassist.clinical.web;

import com.medassist.clinical.domain.ClinicalModel.DischargeMedication;
import com.medassist.clinical.service.DischargeMedicationService;
import com.medassist.clinical.service.VisitAccessGuard;
import com.medassist.clinical.web.DischargeMedicationDtos.CancelDischargeMedicationReq;
import com.medassist.clinical.web.DischargeMedicationDtos.CreateDischargeMedicationReq;
import com.medassist.clinical.web.DischargeMedicationDtos.DischargeMedicationView;
import com.medassist.common.api.ApiResponse;
import com.medassist.common.security.AuthHeaders;
import com.medassist.common.security.DataScope;
import com.medassist.common.security.Permissions;
import jakarta.validation.Valid;
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
 * 出院带药 API（G30）。
 *
 * <p>权限：查看 {@code inpatient:discharge-med:read}；
 * 开立/提交/同步/取消 {@code inpatient:discharge-med:write}。
 *
 * <p>G65：列表按就诊院区收敛；详情/提交/同步/取消先反查所属就诊过
 * {@link VisitAccessGuard}（科室 + 院区，越权与不存在同码）。
 */
@RestController
@RequestMapping("/clinical/discharge-medications")
public class DischargeMedicationController {

    private static final String READ = "inpatient:discharge-med:read";
    private static final String WRITE = "inpatient:discharge-med:write";

    private final DischargeMedicationService service;
    private final VisitAccessGuard guard;

    public DischargeMedicationController(DischargeMedicationService service,
                                         VisitAccessGuard guard) {
        this.service = service;
        this.guard = guard;
    }

    @GetMapping
    public ApiResponse<List<DischargeMedication>> list(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) UUID deptId,
            @RequestParam(required = false) Integer limit,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            DataScope scope) {
        Permissions.require(permissions, READ);
        if (deptId != null && !scope.canSeeDepartment(deptId)) {
            throw new com.medassist.common.exception.BusinessException(
                    com.medassist.common.api.ErrorCode.NOT_FOUND, "未找到该科室的出院带药");
        }
        return ApiResponse.ok(service.list(status, deptId, limit, scope.campusFilter()));
    }

    @GetMapping("/{id}")
    public ApiResponse<DischargeMedicationView> detail(
            @PathVariable UUID id,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            DataScope scope) {
        Permissions.require(permissions, READ);
        guard.requireRead(scope, service.visitIdOf(id));
        return ApiResponse.ok(service.detail(id));
    }

    @PostMapping
    public ApiResponse<DischargeMedicationView> create(
            @Valid @RequestBody CreateDischargeMedicationReq req,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(value = AuthHeaders.ACTOR, defaultValue = "system") String actor,
            DataScope scope) {
        Permissions.require(permissions, WRITE);
        guard.require(scope, req.visitId());
        return ApiResponse.ok(service.create(req, actor));
    }

    /** 提交药房：生成发药时计价处方（失败草稿保持可重试）。 */
    @PostMapping("/{id}/submit")
    public ApiResponse<DischargeMedicationView> submit(
            @PathVariable UUID id,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(value = AuthHeaders.ACTOR, defaultValue = "system") String actor,
            DataScope scope) {
        Permissions.require(permissions, WRITE);
        guard.require(scope, service.visitIdOf(id));
        return ApiResponse.ok(service.submit(id, actor));
    }

    /** 同步药房发药结果（也由定时任务调用）。 */
    @PostMapping("/{id}/sync")
    public ApiResponse<DischargeMedicationView> sync(
            @PathVariable UUID id,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(value = AuthHeaders.ACTOR, defaultValue = "system") String actor,
            DataScope scope) {
        Permissions.require(permissions, WRITE);
        guard.require(scope, service.visitIdOf(id));
        return ApiResponse.ok(service.syncStatus(id, actor));
    }

    @PostMapping("/{id}/cancel")
    public ApiResponse<DischargeMedicationView> cancel(
            @PathVariable UUID id,
            @Valid @RequestBody CancelDischargeMedicationReq req,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(value = AuthHeaders.ACTOR, defaultValue = "system") String actor,
            DataScope scope) {
        Permissions.require(permissions, WRITE);
        guard.require(scope, service.visitIdOf(id));
        return ApiResponse.ok(service.cancel(id, req, actor));
    }
}
