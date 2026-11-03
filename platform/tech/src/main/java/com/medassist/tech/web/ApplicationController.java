package com.medassist.tech.web;

import com.medassist.common.api.ApiResponse;
import com.medassist.common.security.AuthHeaders;
import com.medassist.common.security.DataScope;
import com.medassist.common.security.Permissions;
import com.medassist.tech.domain.TechModel.Application;
import com.medassist.tech.service.ApplicationService;
import com.medassist.tech.web.TechDtos.ApplicationView;
import com.medassist.tech.web.TechDtos.CollectSpecimenReq;
import com.medassist.tech.web.TechDtos.CreateApplicationReq;
import com.medassist.tech.web.TechDtos.ReceiveSpecimenReq;
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
 * 检查/检验申请与标本流转 API。
 *
 * <p>权限划分：医师开单/取消；护士采集标本；技师接收标本、开始执行、关闭申请；
 * 全员可查（tech:read）。
 */
@RestController
@RequestMapping("/tech/applications")
public class ApplicationController {

    private static final String CREATE = "tech:application:create";
    private static final String CANCEL = "tech:application:cancel";
    private static final String COLLECT = "tech:specimen:collect";
    private static final String RECEIVE = "tech:specimen:receive";
    private static final String EXECUTE = "tech:report:write";
    private static final String CLOSE = "tech:application:close";
    private static final String READ = "tech:read";

    private final ApplicationService service;

    public ApplicationController(ApplicationService service) {
        this.service = service;
    }

    @PostMapping
    public ApiResponse<ApplicationView> create(
            @Valid @RequestBody CreateApplicationReq req,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(value = AuthHeaders.ACTOR, defaultValue = "system") String actor) {
        Permissions.require(permissions, CREATE);
        return ApiResponse.ok(service.create(req, actor));
    }

    @GetMapping
    public ApiResponse<List<Application>> list(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) UUID patientId,
            @RequestParam(required = false) Integer limit,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            DataScope scope) {
        Permissions.require(permissions, READ);
        // P0-S3 行级范围：全科室角色不过滤，其余按科室集合过滤
        List<Application> apps = service.list(status, patientId,
                scope.allDepartments() ? null : scope.deptCodes(), limit);
        return ApiResponse.ok(PhiMask.applications(apps, scope));
    }

    /**
     * 各状态申请数量（P0-F4 工作台 KPI）。
     *
     * <p>与列表同一科室范围；列表被 200 条上限截断时计数仍准确，
     * 前端只需「1 次计数 + 1 次当前页签」。
     */
    @GetMapping("/summary")
    public ApiResponse<java.util.Map<String, Integer>> summary(
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            DataScope scope) {
        Permissions.require(permissions, READ);
        return ApiResponse.ok(service.statusCounts(
                scope.allDepartments() ? null : scope.deptCodes()));
    }

    @GetMapping("/{id}")
    public ApiResponse<ApplicationView> detail(
            @PathVariable UUID id,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            DataScope scope) {
        Permissions.require(permissions, READ);
        ApplicationView view = service.detail(id);
        if (!scope.allDepartments() && view.application().deptId() != null
                && !scope.canSeeDepartment(view.application().deptId())) {
            // 跨科室读单拒绝：越权与不存在同为 404，避免泄露"该申请存在但你看不到"
            throw new com.medassist.common.exception.BusinessException(
                    com.medassist.common.api.ErrorCode.TECH_APPLICATION_NOT_FOUND);
        }
        return ApiResponse.ok(new ApplicationView(
                PhiMask.application(view.application(), scope), view.specimen()));
    }

    @PostMapping("/{id}/cancel")
    public ApiResponse<ApplicationView> cancel(
            @PathVariable UUID id,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(value = AuthHeaders.ACTOR, defaultValue = "system") String actor) {
        Permissions.require(permissions, CANCEL);
        return ApiResponse.ok(service.cancel(id, actor));
    }

    /** 采集标本（检验）：生成唯一条码，申请进入 SPECIMEN_COLLECTED。 */
    @PostMapping("/{id}/specimen")
    public ApiResponse<ApplicationView> collectSpecimen(
            @PathVariable UUID id,
            @Valid @RequestBody(required = false) CollectSpecimenReq req,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(value = AuthHeaders.ACTOR, defaultValue = "system") String actor) {
        Permissions.require(permissions, COLLECT);
        return ApiResponse.ok(service.collectSpecimen(id, req, actor));
    }

    /** 接收/拒收标本；拒收会作废本次申请（需重新开单）。 */
    @PostMapping("/{id}/specimen/receive")
    public ApiResponse<ApplicationView> receiveSpecimen(
            @PathVariable UUID id,
            @Valid @RequestBody ReceiveSpecimenReq req,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(value = AuthHeaders.ACTOR, defaultValue = "system") String actor) {
        Permissions.require(permissions, RECEIVE);
        return ApiResponse.ok(service.receiveSpecimen(id, req.accept(), req.reason(), actor));
    }

    /** 开始执行（检查类从 APPLIED、检验类从 RECEIVED）。 */
    @PostMapping("/{id}/start")
    public ApiResponse<ApplicationView> start(
            @PathVariable UUID id,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(value = AuthHeaders.ACTOR, defaultValue = "system") String actor) {
        Permissions.require(permissions, EXECUTE);
        return ApiResponse.ok(service.start(id, actor));
    }

    /** 关闭申请：必须已出报告且危急值全部闭环。 */
    @PostMapping("/{id}/close")
    public ApiResponse<ApplicationView> close(
            @PathVariable UUID id,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(value = AuthHeaders.ACTOR, defaultValue = "system") String actor) {
        Permissions.require(permissions, CLOSE);
        return ApiResponse.ok(service.close(id, actor));
    }
}
