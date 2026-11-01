package com.medassist.clinical.web;

import com.medassist.clinical.domain.ClinicalModel.SurgeryLevel;
import com.medassist.clinical.service.SurgeryLevelService;
import com.medassist.clinical.web.SurgeryDtos.SurgeryVersionReq;
import com.medassist.clinical.web.SurgeryLevelDtos.RejectLevelReq;
import com.medassist.clinical.web.SurgeryLevelDtos.SetLevelReq;
import com.medassist.common.api.ApiResponse;
import com.medassist.common.security.AuthHeaders;
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

import java.util.UUID;

/**
 * 手术分级与审批 API。
 *
 * <p>权限三分：
 * <ul>
 *   <li>{@code clinical:surgery:read} —— 查询分级（医师/护士/病案/质控）；</li>
 *   <li>{@code clinical:surgery:write} —— 提交或调整分级（术者）；</li>
 *   <li>{@code clinical:surgery:approve} —— 审批/驳回三、四级分级（医务/质控）。
 *       与 write 分离，确保"申请的人"和"批的人"不是同一个权限面。</li>
 * </ul>
 *
 * <p>GET 在未分级时返回 200 且 {@code data} 为空：未分级是正常业务状态，
 * 用 404 表达会让前端把"还没填"当成接口异常。
 */
@RestController
@RequestMapping("/clinical/surgery-cases/{caseId}/level")
public class SurgeryLevelController {

    private static final String READ = "clinical:surgery:read";
    private static final String WRITE = "clinical:surgery:write";
    private static final String APPROVE = "clinical:surgery:approve";

    private final SurgeryLevelService service;

    public SurgeryLevelController(SurgeryLevelService service) {
        this.service = service;
    }

    @GetMapping
    public ApiResponse<SurgeryLevel> get(
            @PathVariable UUID caseId,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions) {
        Permissions.require(permissions, READ);
        return ApiResponse.ok(service.get(caseId));
    }

    @PutMapping
    public ApiResponse<SurgeryLevel> setLevel(
            @PathVariable UUID caseId, @Valid @RequestBody SetLevelReq req,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(value = AuthHeaders.ACTOR, defaultValue = "system") String actor) {
        Permissions.require(permissions, WRITE);
        return ApiResponse.ok(service.setLevel(caseId, req.level(), req.version(), actor));
    }

    @PostMapping("/approve")
    public ApiResponse<SurgeryLevel> approve(
            @PathVariable UUID caseId, @Valid @RequestBody SurgeryVersionReq req,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(value = AuthHeaders.ACTOR, defaultValue = "system") String actor) {
        Permissions.require(permissions, APPROVE);
        return ApiResponse.ok(service.approve(caseId, req.version(), actor));
    }

    @PostMapping("/reject")
    public ApiResponse<SurgeryLevel> reject(
            @PathVariable UUID caseId, @Valid @RequestBody RejectLevelReq req,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(value = AuthHeaders.ACTOR, defaultValue = "system") String actor) {
        Permissions.require(permissions, APPROVE);
        return ApiResponse.ok(service.reject(caseId, req.version(), req.reason(), actor));
    }
}
