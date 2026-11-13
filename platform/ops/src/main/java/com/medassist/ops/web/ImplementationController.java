package com.medassist.ops.web;

import com.medassist.common.api.ApiResponse;
import com.medassist.common.api.PageResult;
import com.medassist.common.security.AuthHeaders;
import com.medassist.common.security.Permissions;
import com.medassist.ops.domain.ImplementationModel.Course;
import com.medassist.ops.domain.ImplementationModel.Exam;
import com.medassist.ops.domain.ImplementationModel.Project;
import com.medassist.ops.domain.ImplementationModel.ProjectEvent;
import com.medassist.ops.domain.ImplementationModel.Readiness;
import com.medassist.ops.domain.ImplementationModel.Template;
import com.medassist.ops.service.ImplementationService;
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
import java.util.Map;
import java.util.UUID;

/**
 * 实施交付与培训考核 API（§298）。
 *
 * <p>权限切分（auth V106）：
 * <ul>
 *   <li>{@code ops:impl:read}：模板/项目/任务/就绪/课程/成绩查询；</li>
 *   <li>{@code ops:impl:manage}：维护模板与课程、创建项目、更新任务、开考与录入成绩；</li>
 *   <li>{@code ops:impl:verify}：上线批准、验收签署、任务豁免（四眼）。</li>
 * </ul>
 *
 * <p>操作人来自网关注入的 {@code X-Actor}；上线/验收由服务层强制"非负责人"。
 */
@RestController
@RequestMapping("/ops/impl")
public class ImplementationController {

    private static final String READ = "ops:impl:read";
    private static final String MANAGE = "ops:impl:manage";
    private static final String VERIFY = "ops:impl:verify";

    private final ImplementationService service;

    public ImplementationController(ImplementationService service) {
        this.service = service;
    }

    // ------------------------------------------------------------------ 模板

    @GetMapping("/templates")
    public ApiResponse<List<Template>> templates(
            @RequestParam(defaultValue = "true") boolean onlyActive,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions) {
        Permissions.require(permissions, READ);
        return ApiResponse.ok(service.listTemplates(onlyActive));
    }

    @PostMapping("/templates")
    public ApiResponse<Template> createTemplate(
            @Valid @RequestBody OpsDtos.ImplTemplateReq req,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(value = AuthHeaders.ACTOR, required = false) String actor,
            @RequestHeader(value = AuthHeaders.CLIENT_IP, required = false) String clientIp,
            @RequestHeader(value = AuthHeaders.TRACE_ID, required = false) String traceId) {
        Permissions.require(permissions, MANAGE);
        return ApiResponse.ok(service.createTemplate(req, actor, clientIp, traceId));
    }

    @GetMapping("/templates/{id}")
    public ApiResponse<Template> template(
            @PathVariable UUID id,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions) {
        Permissions.require(permissions, READ);
        return ApiResponse.ok(service.template(id));
    }

    @PostMapping("/templates/{id}/status")
    public ApiResponse<Template> templateStatus(
            @PathVariable UUID id,
            @Valid @RequestBody OpsDtos.ImplStatusReq req,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(value = AuthHeaders.ACTOR, required = false) String actor,
            @RequestHeader(value = AuthHeaders.CLIENT_IP, required = false) String clientIp,
            @RequestHeader(value = AuthHeaders.TRACE_ID, required = false) String traceId) {
        Permissions.require(permissions, MANAGE);
        return ApiResponse.ok(service.changeTemplateStatus(id, req.status(), actor, clientIp, traceId));
    }

    // ------------------------------------------------------------------ 项目

    @GetMapping("/projects")
    public ApiResponse<PageResult<Project>> projects(
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions) {
        Permissions.require(permissions, READ);
        int safePage = Math.max(page, 1);
        int safeSize = Math.min(Math.max(size, 1), PageResult.MAX_PAGE_SIZE);
        long total = service.countProjects(status);
        List<Project> items = service.listProjects(status, safeSize, (safePage - 1) * safeSize);
        return ApiResponse.ok(PageResult.of(safePage, safeSize, total, items));
    }

    @PostMapping("/projects")
    public ApiResponse<Project> createProject(
            @Valid @RequestBody OpsDtos.ImplProjectCreateReq req,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(value = AuthHeaders.ACTOR, required = false) String actor,
            @RequestHeader(value = AuthHeaders.CLIENT_IP, required = false) String clientIp,
            @RequestHeader(value = AuthHeaders.TRACE_ID, required = false) String traceId) {
        Permissions.require(permissions, MANAGE);
        return ApiResponse.ok(service.createProject(req, actor, clientIp, traceId));
    }

    @GetMapping("/projects/{id}")
    public ApiResponse<Project> project(
            @PathVariable UUID id,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions) {
        Permissions.require(permissions, READ);
        return ApiResponse.ok(service.detail(id));
    }

    @GetMapping("/projects/{id}/events")
    public ApiResponse<List<ProjectEvent>> events(
            @PathVariable UUID id,
            @RequestParam(defaultValue = "100") int limit,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions) {
        Permissions.require(permissions, READ);
        return ApiResponse.ok(service.events(id, limit));
    }

    @GetMapping("/projects/{id}/readiness")
    public ApiResponse<Readiness> readiness(
            @PathVariable UUID id,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions) {
        Permissions.require(permissions, READ);
        return ApiResponse.ok(service.readiness(id));
    }

    @PostMapping("/projects/{id}/start")
    public ApiResponse<Project> start(
            @PathVariable UUID id,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(value = AuthHeaders.ACTOR, required = false) String actor,
            @RequestHeader(value = AuthHeaders.CLIENT_IP, required = false) String clientIp,
            @RequestHeader(value = AuthHeaders.TRACE_ID, required = false) String traceId) {
        Permissions.require(permissions, MANAGE);
        return ApiResponse.ok(service.start(id, actor, clientIp, traceId));
    }

    @PostMapping("/projects/{id}/tasks/{taskId}")
    public ApiResponse<Project> updateTask(
            @PathVariable UUID id,
            @PathVariable UUID taskId,
            @Valid @RequestBody OpsDtos.ImplTaskUpdateReq req,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(value = AuthHeaders.ACTOR, required = false) String actor,
            @RequestHeader(value = AuthHeaders.CLIENT_IP, required = false) String clientIp,
            @RequestHeader(value = AuthHeaders.TRACE_ID, required = false) String traceId) {
        Permissions.require(permissions, MANAGE);
        return ApiResponse.ok(service.updateTask(id, taskId, req, actor, clientIp, traceId));
    }

    @PostMapping("/projects/{id}/tasks/{taskId}/waive")
    public ApiResponse<Project> waiveTask(
            @PathVariable UUID id,
            @PathVariable UUID taskId,
            @Valid @RequestBody OpsDtos.ImplWaiverReq req,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(value = AuthHeaders.ACTOR, required = false) String actor,
            @RequestHeader(value = AuthHeaders.CLIENT_IP, required = false) String clientIp,
            @RequestHeader(value = AuthHeaders.TRACE_ID, required = false) String traceId) {
        Permissions.require(permissions, VERIFY);
        return ApiResponse.ok(service.waiveTask(id, taskId, req, actor, clientIp, traceId));
    }

    @PostMapping("/projects/{id}/go-live")
    public ApiResponse<Project> goLive(
            @PathVariable UUID id,
            @RequestBody(required = false) OpsDtos.ImplApprovalReq req,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(value = AuthHeaders.ACTOR, required = false) String actor,
            @RequestHeader(value = AuthHeaders.CLIENT_IP, required = false) String clientIp,
            @RequestHeader(value = AuthHeaders.TRACE_ID, required = false) String traceId) {
        Permissions.require(permissions, VERIFY);
        return ApiResponse.ok(service.goLive(id, req, actor, clientIp, traceId));
    }

    @PostMapping("/projects/{id}/accept")
    public ApiResponse<Project> accept(
            @PathVariable UUID id,
            @Valid @RequestBody OpsDtos.ImplAcceptReq req,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(value = AuthHeaders.ACTOR, required = false) String actor,
            @RequestHeader(value = AuthHeaders.CLIENT_IP, required = false) String clientIp,
            @RequestHeader(value = AuthHeaders.TRACE_ID, required = false) String traceId) {
        Permissions.require(permissions, VERIFY);
        return ApiResponse.ok(service.accept(id, req, actor, clientIp, traceId));
    }

    @PostMapping("/projects/{id}/cancel")
    public ApiResponse<Project> cancel(
            @PathVariable UUID id,
            @Valid @RequestBody OpsDtos.ImplCancelReq req,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(value = AuthHeaders.ACTOR, required = false) String actor,
            @RequestHeader(value = AuthHeaders.CLIENT_IP, required = false) String clientIp,
            @RequestHeader(value = AuthHeaders.TRACE_ID, required = false) String traceId) {
        Permissions.require(permissions, MANAGE);
        return ApiResponse.ok(service.cancel(id, req, actor, clientIp, traceId));
    }

    // ------------------------------------------------------------------ 培训课程

    @GetMapping("/courses")
    public ApiResponse<List<Course>> courses(
            @RequestParam(defaultValue = "true") boolean onlyActive,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions) {
        Permissions.require(permissions, READ);
        return ApiResponse.ok(service.listCourses(onlyActive));
    }

    @PostMapping("/courses")
    public ApiResponse<Course> createCourse(
            @Valid @RequestBody OpsDtos.ImplCourseReq req,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(value = AuthHeaders.ACTOR, required = false) String actor,
            @RequestHeader(value = AuthHeaders.CLIENT_IP, required = false) String clientIp,
            @RequestHeader(value = AuthHeaders.TRACE_ID, required = false) String traceId) {
        Permissions.require(permissions, MANAGE);
        return ApiResponse.ok(service.createCourse(req, actor, clientIp, traceId));
    }

    /** 课程详情含题库与答案键，仅实施管理员可读，避免只读角色拿到答案。 */
    @GetMapping("/courses/{id}")
    public ApiResponse<Course> course(
            @PathVariable UUID id,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions) {
        Permissions.require(permissions, MANAGE);
        return ApiResponse.ok(service.course(id));
    }

    @PostMapping("/courses/{id}/status")
    public ApiResponse<Course> courseStatus(
            @PathVariable UUID id,
            @Valid @RequestBody OpsDtos.ImplStatusReq req,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(value = AuthHeaders.ACTOR, required = false) String actor,
            @RequestHeader(value = AuthHeaders.CLIENT_IP, required = false) String clientIp,
            @RequestHeader(value = AuthHeaders.TRACE_ID, required = false) String traceId) {
        Permissions.require(permissions, MANAGE);
        return ApiResponse.ok(service.changeCourseStatus(id, req.status(), actor, clientIp, traceId));
    }

    // ------------------------------------------------------------------ 考试与成绩

    @GetMapping("/projects/{id}/exams")
    public ApiResponse<List<Exam>> exams(
            @PathVariable UUID id,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions) {
        Permissions.require(permissions, READ);
        return ApiResponse.ok(service.listExams(id));
    }

    @PostMapping("/projects/{id}/exams")
    public ApiResponse<Exam> createExam(
            @PathVariable UUID id,
            @Valid @RequestBody OpsDtos.ImplExamReq req,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(value = AuthHeaders.ACTOR, required = false) String actor,
            @RequestHeader(value = AuthHeaders.CLIENT_IP, required = false) String clientIp,
            @RequestHeader(value = AuthHeaders.TRACE_ID, required = false) String traceId) {
        Permissions.require(permissions, MANAGE);
        return ApiResponse.ok(service.createExam(id, req, actor, clientIp, traceId));
    }

    @GetMapping("/exams/{id}")
    public ApiResponse<Exam> exam(
            @PathVariable UUID id,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions) {
        Permissions.require(permissions, READ);
        return ApiResponse.ok(service.exam(id));
    }

    @PostMapping("/exams/{id}/close")
    public ApiResponse<Exam> closeExam(
            @PathVariable UUID id,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(value = AuthHeaders.ACTOR, required = false) String actor,
            @RequestHeader(value = AuthHeaders.CLIENT_IP, required = false) String clientIp,
            @RequestHeader(value = AuthHeaders.TRACE_ID, required = false) String traceId) {
        Permissions.require(permissions, MANAGE);
        return ApiResponse.ok(service.closeExam(id, actor, clientIp, traceId));
    }

    @PostMapping("/exams/{id}/attempts")
    public ApiResponse<Exam> recordAttempt(
            @PathVariable UUID id,
            @Valid @RequestBody OpsDtos.ImplAttemptReq req,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(value = AuthHeaders.ACTOR, required = false) String actor,
            @RequestHeader(value = AuthHeaders.CLIENT_IP, required = false) String clientIp,
            @RequestHeader(value = AuthHeaders.TRACE_ID, required = false) String traceId) {
        Permissions.require(permissions, MANAGE);
        return ApiResponse.ok(service.recordAttempt(id, req, actor, clientIp, traceId));
    }

    // ------------------------------------------------------------------ 看板

    @GetMapping("/dashboard")
    public ApiResponse<Map<String, Object>> dashboard(
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions) {
        Permissions.require(permissions, READ);
        return ApiResponse.ok(service.dashboard());
    }
}
