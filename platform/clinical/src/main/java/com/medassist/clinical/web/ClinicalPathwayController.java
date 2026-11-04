package com.medassist.clinical.web;

import com.medassist.clinical.domain.ClinicalModel.PathwayInstance;
import com.medassist.clinical.domain.ClinicalModel.PathwayTemplate;
import com.medassist.clinical.service.ClinicalPathwayService;
import com.medassist.clinical.web.ClinicalPathwayDtos.CreateTemplateReq;
import com.medassist.clinical.web.ClinicalPathwayDtos.EnrollReq;
import com.medassist.clinical.web.ClinicalPathwayDtos.ExitReq;
import com.medassist.clinical.web.ClinicalPathwayDtos.InstanceView;
import com.medassist.clinical.web.ClinicalPathwayDtos.TemplateView;
import com.medassist.clinical.web.ClinicalPathwayDtos.UpdateTaskReq;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * 临床路径 API（G29）。
 *
 * <p>权限：查看 {@code inpatient:pathway:read}；模板维护/入径/退出 {@code inpatient:pathway:manage}；
 * 任务完成/跳过/结束 {@code inpatient:pathway:execute}。
 */
@RestController
@RequestMapping("/clinical/pathways")
public class ClinicalPathwayController {

    private static final String READ = "inpatient:pathway:read";
    private static final String MANAGE = "inpatient:pathway:manage";
    private static final String EXECUTE = "inpatient:pathway:execute";

    private final ClinicalPathwayService service;

    public ClinicalPathwayController(ClinicalPathwayService service) {
        this.service = service;
    }

    @GetMapping("/templates")
    public ApiResponse<List<PathwayTemplate>> templates(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) Integer limit,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions) {
        Permissions.require(permissions, READ);
        return ApiResponse.ok(service.listTemplates(status, keyword, limit));
    }

    @GetMapping("/templates/{id}")
    public ApiResponse<TemplateView> template(
            @PathVariable UUID id,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions) {
        Permissions.require(permissions, READ);
        return ApiResponse.ok(service.templateDetail(id));
    }

    @PostMapping("/templates")
    public ApiResponse<TemplateView> createTemplate(
            @Valid @RequestBody CreateTemplateReq req,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(value = AuthHeaders.ACTOR, defaultValue = "system") String actor) {
        Permissions.require(permissions, MANAGE);
        return ApiResponse.ok(service.createTemplate(req, actor));
    }

    @PostMapping("/templates/{id}/publish")
    public ApiResponse<TemplateView> publish(
            @PathVariable UUID id,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(value = AuthHeaders.ACTOR, defaultValue = "system") String actor) {
        Permissions.require(permissions, MANAGE);
        return ApiResponse.ok(service.publishTemplate(id, actor));
    }

    @PostMapping("/templates/{id}/disable")
    public ApiResponse<TemplateView> disable(
            @PathVariable UUID id,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(value = AuthHeaders.ACTOR, defaultValue = "system") String actor) {
        Permissions.require(permissions, MANAGE);
        return ApiResponse.ok(service.disableTemplate(id, actor));
    }

    @GetMapping("/instances")
    public ApiResponse<List<PathwayInstance>> instances(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) UUID deptId,
            @RequestParam(required = false) Integer limit,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions) {
        Permissions.require(permissions, READ);
        return ApiResponse.ok(service.listInstances(status, deptId, limit));
    }

    @GetMapping("/instances/{id}")
    public ApiResponse<InstanceView> instance(
            @PathVariable UUID id,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions) {
        Permissions.require(permissions, READ);
        return ApiResponse.ok(service.instanceDetail(id));
    }

    /** 入径：住院号（visitNo）或就诊 ID 二选一。 */
    @PostMapping("/instances")
    public ApiResponse<InstanceView> enroll(
            @Valid @RequestBody EnrollReq req,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(value = AuthHeaders.ACTOR, defaultValue = "system") String actor) {
        Permissions.require(permissions, MANAGE);
        return ApiResponse.ok(service.enroll(req, actor));
    }

    /** 任务完成（DONE）或跳过（SKIPPED，必须写原因）。 */
    @PostMapping("/instances/{id}/tasks/{taskId}/status")
    public ApiResponse<InstanceView> updateTask(
            @PathVariable UUID id,
            @PathVariable UUID taskId,
            @Valid @RequestBody UpdateTaskReq req,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(value = AuthHeaders.ACTOR, defaultValue = "system") String actor) {
        Permissions.require(permissions, EXECUTE);
        return ApiResponse.ok(service.updateTask(id, taskId, req, actor));
    }

    @PostMapping("/instances/{id}/complete")
    public ApiResponse<InstanceView> complete(
            @PathVariable UUID id,
            @RequestBody(required = false) CompleteReq req,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(value = AuthHeaders.ACTOR, defaultValue = "system") String actor) {
        Permissions.require(permissions, EXECUTE);
        return ApiResponse.ok(service.complete(id, req == null ? null : req.note(), actor));
    }

    @PostMapping("/instances/{id}/exit")
    public ApiResponse<InstanceView> exit(
            @PathVariable UUID id,
            @Valid @RequestBody ExitReq req,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(value = AuthHeaders.ACTOR, defaultValue = "system") String actor) {
        Permissions.require(permissions, MANAGE);
        return ApiResponse.ok(service.exit(id, req, actor));
    }

    /** 结束路径可选备注。 */
    public record CompleteReq(@jakarta.validation.constraints.Size(max = 200) String note) {
    }
}
