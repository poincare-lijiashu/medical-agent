package com.medassist.auth.web;

import com.medassist.auth.service.UserService;
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
 * <p>P1 授权矩阵：每个端点显式声明所需权限点，角色调整只需改 auth 域 role_permission，
 * 本类无需改动。
 */
@RestController
@RequestMapping("/users")
public class UserController {

    private static final String READ = "auth:user:read";
    private static final String CREATE = "auth:user:create";
    private static final String UPDATE = "auth:user:update";
    private static final String DISABLE = "auth:user:disable";
    private static final String ASSIGN_ROLE = "auth:role:assign";

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
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions) {
        Permissions.require(permissions, READ);
        return ApiResponse.ok(service.list(orgCode, status, page, size));
    }

    @GetMapping("/{id}")
    public ApiResponse<UserResp> detail(
            @PathVariable UUID id,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions) {
        Permissions.require(permissions, READ);
        return ApiResponse.ok(service.detail(id));
    }

    @PostMapping
    public ApiResponse<UserResp> create(
            @Valid @RequestBody CreateUserReq req,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(value = AuthHeaders.ACTOR, defaultValue = "system") String actor) {
        Permissions.require(permissions, CREATE);
        return ApiResponse.ok(service.create(req, actor));
    }

    @PutMapping("/{id}")
    public ApiResponse<UserResp> update(
            @PathVariable UUID id,
            @Valid @RequestBody UpdateUserReq req,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(value = AuthHeaders.ACTOR, defaultValue = "system") String actor) {
        Permissions.require(permissions, UPDATE);
        return ApiResponse.ok(service.update(id, req, actor));
    }

    @PostMapping("/{id}/disable")
    public ApiResponse<UserResp> disable(
            @PathVariable UUID id,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(value = AuthHeaders.ACTOR, defaultValue = "system") String actor) {
        Permissions.require(permissions, DISABLE);
        return ApiResponse.ok(service.disable(id, actor));
    }

    @PostMapping("/{id}/enable")
    public ApiResponse<UserResp> enable(
            @PathVariable UUID id,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(value = AuthHeaders.ACTOR, defaultValue = "system") String actor) {
        Permissions.require(permissions, DISABLE);
        return ApiResponse.ok(service.enable(id, actor));
    }

    @PutMapping("/{id}/roles")
    public ApiResponse<UserResp> assignRoles(
            @PathVariable UUID id,
            @Valid @RequestBody AssignRolesReq req,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(value = AuthHeaders.ACTOR, defaultValue = "system") String actor) {
        Permissions.require(permissions, ASSIGN_ROLE);
        return ApiResponse.ok(service.assignRoles(id, req.roles(), actor));
    }

    @PostMapping("/{id}/reset-password")
    public ApiResponse<Void> resetPassword(
            @PathVariable UUID id,
            @Valid @RequestBody ResetPasswordReq req,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(value = AuthHeaders.ACTOR, defaultValue = "system") String actor) {
        Permissions.require(permissions, UPDATE);
        service.resetPassword(id, req.newPassword(), actor);
        return ApiResponse.ok(null);
    }
}
