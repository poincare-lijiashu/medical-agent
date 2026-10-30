package com.medassist.auth.web;

import com.medassist.auth.service.AuthService;
import com.medassist.auth.web.dto.LoginReq;
import com.medassist.auth.web.dto.LoginResp;
import com.medassist.auth.web.dto.MeResp;
import com.medassist.auth.web.dto.VerifyResp;
import com.medassist.auth.web.dto.VerifyTokenReq;
import com.medassist.common.api.ApiResponse;
import com.medassist.common.api.ErrorCode;
import com.medassist.common.exception.BusinessException;
import com.medassist.common.security.AuthHeaders;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * 认证 API。
 *
 * <p>路径说明：经网关访问时为 {@code /api/v2/auth/login}（网关 StripPrefix=3 → {@code /login}）；
 * {@code /me} 依赖网关注入的 {@code X-User-Id}，客户端直连 8081 无法伪造（见 README 部署约束）。
 */
@RestController
public class AuthController {

    private final AuthService service;

    public AuthController(AuthService service) {
        this.service = service;
    }

    /** 登录：校验 bcrypt 密码并签发 JWT。网关白名单中唯一放行的业务路径。 */
    @PostMapping("/login")
    public ApiResponse<LoginResp> login(@Valid @RequestBody LoginReq req) {
        return ApiResponse.ok(service.login(req));
    }

    /** 当前登录人信息（含角色与权限点），身份来自网关注入头。 */
    @GetMapping("/me")
    public ApiResponse<MeResp> me(@RequestHeader(value = AuthHeaders.USER_ID, required = false) String userId) {
        if (userId == null || userId.isBlank()) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }
        UUID id;
        try {
            id = UUID.fromString(userId.trim());
        } catch (IllegalArgumentException e) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }
        return ApiResponse.ok(service.me(id));
    }

    /** 内部令牌校验（集群内排障；网关对该路径直接 404，不对外暴露）。 */
    @PostMapping("/internal/token/verify")
    public ApiResponse<VerifyResp> verify(@Valid @RequestBody VerifyTokenReq req) {
        return ApiResponse.ok(service.verify(req.token()));
    }
}
