package com.medassist.auth.web;

import com.medassist.auth.service.UserService;
import com.medassist.auth.web.dto.AssignRolesReq;
import com.medassist.auth.web.dto.CreateUserReq;
import com.medassist.auth.web.dto.ResetPasswordReq;
import com.medassist.auth.web.dto.UpdateUserReq;
import com.medassist.auth.web.dto.UserResp;
import com.medassist.common.api.ApiResponse;
import com.medassist.common.api.PageResult;
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
 * <p>actor 来源：P0 阶段取网关注入的 {@code X-Actor}（P0-4 接入 JWT 后改为从令牌声明取，
 * 届时本参数由网关侧统一剥离，客户端无法伪造）。
 */
@RestController
@RequestMapping("/users")
public class UserController {

    private final UserService service;

    public UserController(UserService service) {
        this.service = service;
    }

    @GetMapping
    public ApiResponse<PageResult<UserResp>> list(
            @RequestParam(required = false) String orgCode,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(service.list(orgCode, status, page, size));
    }

    @GetMapping("/{id}")
    public ApiResponse<UserResp> detail(@PathVariable UUID id) {
        return ApiResponse.ok(service.detail(id));
    }

    @PostMapping
    public ApiResponse<UserResp> create(@Valid @RequestBody CreateUserReq req,
                                        @RequestHeader(value = "X-Actor", defaultValue = "system") String actor) {
        return ApiResponse.ok(service.create(req, actor));
    }

    @PutMapping("/{id}")
    public ApiResponse<UserResp> update(@PathVariable UUID id,
                                        @Valid @RequestBody UpdateUserReq req,
                                        @RequestHeader(value = "X-Actor", defaultValue = "system") String actor) {
        return ApiResponse.ok(service.update(id, req, actor));
    }

    @PostMapping("/{id}/disable")
    public ApiResponse<UserResp> disable(@PathVariable UUID id,
                                         @RequestHeader(value = "X-Actor", defaultValue = "system") String actor) {
        return ApiResponse.ok(service.disable(id, actor));
    }

    @PostMapping("/{id}/enable")
    public ApiResponse<UserResp> enable(@PathVariable UUID id,
                                        @RequestHeader(value = "X-Actor", defaultValue = "system") String actor) {
        return ApiResponse.ok(service.enable(id, actor));
    }

    @PutMapping("/{id}/roles")
    public ApiResponse<UserResp> assignRoles(@PathVariable UUID id,
                                             @Valid @RequestBody AssignRolesReq req,
                                             @RequestHeader(value = "X-Actor", defaultValue = "system") String actor) {
        return ApiResponse.ok(service.assignRoles(id, req.roles(), actor));
    }

    @PostMapping("/{id}/reset-password")
    public ApiResponse<Void> resetPassword(@PathVariable UUID id,
                                           @Valid @RequestBody ResetPasswordReq req,
                                           @RequestHeader(value = "X-Actor", defaultValue = "system") String actor) {
        service.resetPassword(id, req.newPassword(), actor);
        return ApiResponse.ok(null);
    }
}
