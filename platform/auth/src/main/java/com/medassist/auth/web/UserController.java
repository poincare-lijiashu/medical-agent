package com.medassist.auth.web;

import com.medassist.auth.service.UserService;
import com.medassist.auth.web.dto.AssignCampusesReq;
import com.medassist.auth.web.dto.AssignRolesReq;
import com.medassist.auth.web.dto.CreateUserReq;
import com.medassist.auth.web.dto.ResetPasswordReq;
import com.medassist.auth.web.dto.UpdateUserReq;
import com.medassist.auth.web.dto.UserResp;
import com.medassist.common.api.ApiResponse;
import com.medassist.common.api.PageResult;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * 账号管理 API。
 *
 * <p>actor 与权限来源：网关注入的 {@code X-Actor} / {@code X-Permissions}（P0-4 起由网关按
 * JWT 声明重写，客户端提交的同名头会被网关先行剥离，无法伪造）。
 *
 * <p>§281 起所有端点都带租户作用域：{@code X-Tenant} 决定"在维护哪家医院的账号"。
 * 目标账号不属于该租户时返回 404（与"不存在"同码），不泄露别家账号是否存在。
 */
@RestController
@RequestMapping("/users")
public class UserController {

    private static final String READ = "auth:user:read";
    private static final String CREATE = "auth:user:create";
    private static final String UPDATE = "auth:user:update";
    private static final String DISABLE = "auth:user:disable";
    private static final String ASSIGN_ROLE = "auth:role:assign";
    /** 改密是独立权限点（P1-S1）：账号维护权不应隐含"替他人设置密码"。 */
    private static final String RESET_PASSWORD = "auth:user:reset_password";

    private final UserService service;

    public UserController(UserService service) {
        this.service = service;
    }

    @GetMapping
    public ApiResponse<PageResult<UserResp>> list(
            @RequestParam(required = false) String orgCode,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(value = AuthHeaders.TENANT, required = false) String tenant) {
        Permissions.require(permissions, READ);
        return ApiResponse.ok(service.list(tenant, orgCode, status, page, size));
    }

    @GetMapping("/{id}")
    public ApiResponse<UserResp> detail(
            @PathVariable UUID id,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(value = AuthHeaders.TENANT, required = false) String tenant) {
        Permissions.require(permissions, READ);
        return ApiResponse.ok(service.detail(id, tenant));
    }

    @PostMapping
    public ApiResponse<UserResp> create(
            @Valid @RequestBody CreateUserReq req,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(value = AuthHeaders.TENANT, required = false) String tenant,
            @RequestHeader(value = AuthHeaders.ACTOR, defaultValue = "system") String actor) {
        Permissions.require(permissions, CREATE);
        return ApiResponse.ok(service.create(req, actor, tenant));
    }

    @PutMapping("/{id}")
    public ApiResponse<UserResp> update(
            @PathVariable UUID id,
            @Valid @RequestBody UpdateUserReq req,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(value = AuthHeaders.TENANT, required = false) String tenant,
            @RequestHeader(value = AuthHeaders.ACTOR, defaultValue = "system") String actor) {
        Permissions.require(permissions, UPDATE);
        return ApiResponse.ok(service.update(id, req, actor, tenant));
    }

    @PostMapping("/{id}/disable")
    public ApiResponse<UserResp> disable(
            @PathVariable UUID id,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(value = AuthHeaders.TENANT, required = false) String tenant,
            @RequestHeader(value = AuthHeaders.ACTOR, defaultValue = "system") String actor) {
        Permissions.require(permissions, DISABLE);
        return ApiResponse.ok(service.disable(id, actor, tenant));
    }

    @PostMapping("/{id}/enable")
    public ApiResponse<UserResp> enable(
            @PathVariable UUID id,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(value = AuthHeaders.TENANT, required = false) String tenant,
            @RequestHeader(value = AuthHeaders.ACTOR, defaultValue = "system") String actor) {
        Permissions.require(permissions, DISABLE);
        return ApiResponse.ok(service.enable(id, actor, tenant));
    }

    @PutMapping("/{id}/roles")
    public ApiResponse<UserResp> assignRoles(
            @PathVariable UUID id,
            @Valid @RequestBody AssignRolesReq req,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(value = AuthHeaders.TENANT, required = false) String tenant,
            @RequestHeader(value = AuthHeaders.ACTOR, defaultValue = "system") String actor) {
        Permissions.require(permissions, ASSIGN_ROLE);
        return ApiResponse.ok(service.assignRoles(id, req.roles(), actor, tenant));
    }

    /** 分配账号可见院区（G45）。 */
    @PutMapping("/{id}/campuses")
    public ApiResponse<UserResp> assignCampuses(
            @PathVariable UUID id,
            @Valid @RequestBody AssignCampusesReq req,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(value = AuthHeaders.TENANT, required = false) String tenant,
            @RequestHeader(value = AuthHeaders.ACTOR, defaultValue = "system") String actor) {
        Permissions.require(permissions, UPDATE);
        return ApiResponse.ok(service.assignCampuses(id, req.campusIds(), actor, tenant));
    }

    @PostMapping("/{id}/reset-password")
    public ApiResponse<Void> resetPassword(
            @PathVariable UUID id,
            @Valid @RequestBody ResetPasswordReq req,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(value = AuthHeaders.TENANT, required = false) String tenant,
            @RequestHeader(value = AuthHeaders.ACTOR, defaultValue = "system") String actor) {
        Permissions.require(permissions, RESET_PASSWORD);
        service.resetPassword(id, req.newPassword(), actor, tenant);
        return ApiResponse.ok(null);
    }
}
