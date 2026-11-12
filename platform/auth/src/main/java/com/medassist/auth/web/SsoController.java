package com.medassist.auth.web;

import com.medassist.auth.service.SsoService;
import com.medassist.auth.web.dto.LoginResp;
import com.medassist.auth.web.dto.SsoDtos.AuthorizeReq;
import com.medassist.auth.web.dto.SsoDtos.AuthorizeView;
import com.medassist.auth.web.dto.SsoDtos.CallbackReq;
import com.medassist.auth.web.dto.SsoDtos.ConfigView;
import com.medassist.common.api.ApiResponse;
import com.medassist.common.security.AuthHeaders;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 统一身份 SSO API（G42）。
 *
 * <p>三个接口都在登录前调用，网关白名单放行；安全由一次性 state + PKCE + nonce 保证。
 * 不在响应中返回 client_secret，也不透出 IdP 的错误详情。
 */
@RestController
@RequestMapping("/sso")
public class SsoController {

    private final SsoService service;

    public SsoController(SsoService service) {
        this.service = service;
    }

    @GetMapping("/config")
    public ApiResponse<ConfigView> config() {
        return ApiResponse.ok(service.config());
    }

    @PostMapping("/oidc/authorize")
    public ApiResponse<AuthorizeView> authorize(
            @RequestBody(required = false) @Valid AuthorizeReq req) {
        return ApiResponse.ok(service.authorize(req == null ? null : req.redirectUri()));
    }

    @PostMapping("/oidc/callback")
    public ApiResponse<LoginResp> callback(
            @Valid @RequestBody CallbackReq req,
            @RequestHeader(value = AuthHeaders.TENANT, required = false) String tenant,
            HttpServletRequest request) {
        return ApiResponse.ok(service.callback(req, clientIp(request), userAgent(request), tenant));
    }

    private static String clientIp(HttpServletRequest request) {
        String trusted = request.getHeader(AuthHeaders.CLIENT_IP);
        if (trusted != null && !trusted.isBlank() && !"unknown".equalsIgnoreCase(trusted.trim())) {
            return truncate(trusted.trim(), 64);
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
