package com.medassist.auth.web;

import com.medassist.auth.service.AuthService;
import com.medassist.auth.web.dto.LoginReq;
import com.medassist.auth.web.dto.LoginResp;
import com.medassist.auth.web.dto.MeResp;
import com.medassist.auth.web.dto.RefreshReq;
import com.medassist.auth.web.dto.RevocationsResp;
import com.medassist.auth.web.dto.VerifyResp;
import com.medassist.auth.web.dto.VerifyTokenReq;
import com.medassist.common.api.ApiResponse;
import com.medassist.common.api.ErrorCode;
import com.medassist.common.exception.BusinessException;
import com.medassist.common.security.AuthHeaders;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.UUID;

/**
 * 认证 API。
 *
 * <p>路径说明：经网关访问时为 {@code /api/v2/auth/login}（网关 StripPrefix=3 → {@code /login}）；
 * 身份头（{@code X-User-Id} / {@code X-Token-Id}）由网关按 JWT 重写，客户端无法伪造。
 * {@code /internal/**} 由网关直接 404，只允许集群内直连（网关撤销名单同步即走内网直连）。
 */
@RestController
public class AuthController {

    private final AuthService service;

    public AuthController(AuthService service) {
        this.service = service;
    }

    /** 登录：校验 bcrypt 密码，签发访问令牌 + 刷新令牌。网关白名单中唯一放行的业务路径。 */
    @PostMapping("/login")
    public ApiResponse<LoginResp> login(@Valid @RequestBody LoginReq req, HttpServletRequest request) {
        return ApiResponse.ok(service.login(req, clientIp(request), userAgent(request)));
    }

    /** 刷新：轮换刷新令牌并重新签发访问令牌。同样在网关白名单（访问令牌已过期时也要能调用）。 */
    @PostMapping("/refresh")
    public ApiResponse<LoginResp> refresh(@Valid @RequestBody RefreshReq req, HttpServletRequest request) {
        return ApiResponse.ok(service.refresh(req.refreshToken(), clientIp(request), userAgent(request)));
    }

    /** 登出：撤销当前访问令牌与全部刷新令牌（全端下线）。 */
    @PostMapping("/logout")
    public ApiResponse<Void> logout(
            @RequestHeader(value = AuthHeaders.USER_ID, required = false) String userId,
            @RequestHeader(value = AuthHeaders.TOKEN_ID, required = false) String tokenId,
            @RequestHeader(value = AuthHeaders.ACTOR, required = false) String actor) {
        if (userId == null || userId.isBlank() || tokenId == null || tokenId.isBlank()) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }
        UUID user;
        UUID jti;
        try {
            user = UUID.fromString(userId.trim());
            jti = UUID.fromString(tokenId.trim());
        } catch (IllegalArgumentException e) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }
        service.logout(user, jti, actor == null || actor.isBlank() ? user.toString() : actor);
        return ApiResponse.ok(null);
    }

    /** 当前登录人信息（含角色与权限点），身份来自网关注入头。 */
    @GetMapping("/me")
    public ApiResponse<MeResp> me(@RequestHeader(value = AuthHeaders.USER_ID, required = false) String userId) {
        return ApiResponse.ok(service.me(parseUserId(userId)));
    }

    /** 内部：网关增量拉取访问令牌撤销名单（只允许集群内直连）。 */
    @GetMapping("/internal/revocations")
    public ApiResponse<RevocationsResp> revocations(@RequestParam(defaultValue = "0") long since) {
        return ApiResponse.ok(service.revocationsSince(Instant.ofEpochMilli(Math.max(0, since))));
    }

    /** 内部令牌校验（集群内排障；网关对该路径直接 404，不对外暴露）。 */
    @PostMapping("/internal/token/verify")
    public ApiResponse<VerifyResp> verify(@Valid @RequestBody VerifyTokenReq req) {
        return ApiResponse.ok(service.verify(req.token()));
    }

    // ---------------------------------------------------------------- 内部方法

    private static UUID parseUserId(String userId) {
        if (userId == null || userId.isBlank()) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }
        try {
            return UUID.fromString(userId.trim());
        } catch (IllegalArgumentException e) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }
    }

    /**
     * 客户端 IP：网关默认追加 {@code X-Forwarded-For}，取首段（最接近真实客户端）。
     * 注意首段可被伪造——IP 维度仅作辅助限速，账号维度才是主防线。
     */
    private static String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            String first = forwarded.split(",")[0].trim();
            if (!first.isEmpty()) {
                return truncate(first, 64);
            }
        }
        return truncate(request.getRemoteAddr() == null ? "unknown" : request.getRemoteAddr(), 64);
    }

    private static String userAgent(HttpServletRequest request) {
        String ua = request.getHeader("User-Agent");
        return ua == null ? "" : truncate(ua, 256);
    }

    private static String truncate(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max);
    }
}
