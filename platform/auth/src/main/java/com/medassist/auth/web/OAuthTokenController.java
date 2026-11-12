package com.medassist.auth.web;

import com.medassist.auth.service.OAuthClientService;
import com.medassist.auth.web.dto.OAuthDtos.TokenResp;
import com.medassist.common.api.ErrorCode;
import com.medassist.common.exception.BusinessException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * OAuth2 令牌端点（`POST /oauth/token`，client_credentials）。
 *
 * <p>为什么不套统一响应壳：第三方 SDK 按 RFC 6749 §5.1/§5.2 解析，
 * 返回 `{code,data}` 会让"标准 OAuth2 客户端"接不上。因此本端点单独返回
 * `access_token/token_type/expires_in/scope`，错误返回 `{error,error_description}`。
 *
 * <p>安全：请求体里的 client_secret 只用于比对，不写日志；令牌 15 分钟有效。
 */
@RestController
public class OAuthTokenController {

    private static final Logger log = LoggerFactory.getLogger(OAuthTokenController.class);

    private final OAuthClientService service;

    public OAuthTokenController(OAuthClientService service) {
        this.service = service;
    }

    @PostMapping(value = "/oauth/token", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE)
    public ResponseEntity<Object> token(
            @RequestParam(value = "grant_type", required = false) String grantType,
            @RequestParam(value = "client_id", required = false) String clientId,
            @RequestParam(value = "client_secret", required = false) String clientSecret,
            @RequestParam(value = "scope", required = false) String scope) {
        if (!"client_credentials".equals(grantType)) {
            return oauthError("unsupported_grant_type", "仅支持 client_credentials", HttpStatus.BAD_REQUEST);
        }
        try {
            OAuthClientService.TokenIssued issued = service.issueToken(clientId, clientSecret, scope);
            return ResponseEntity.ok(new TokenResp(issued.accessToken(), issued.tokenType(),
                    issued.expiresIn(), String.join(" ", issued.scopes())));
        } catch (BusinessException e) {
            String code = switch (e.errorCode()) {
                case OAUTH_SECRET_INVALID -> "invalid_client";
                case OAUTH_CLIENT_DISABLED -> "invalid_client";
                case OAUTH_SCOPE_NOT_ALLOWED -> "invalid_scope";
                default -> "invalid_request";
            };
            HttpStatus status = "invalid_client".equals(code) ? HttpStatus.UNAUTHORIZED : HttpStatus.BAD_REQUEST;
            log.info("oauth.token.rejected client_id={} error={}", clientId, code);
            return oauthError(code, e.getMessage(), status, e.errorCode());
        }
    }

    private ResponseEntity<Object> oauthError(String error, String desc, HttpStatus status) {
        return oauthError(error, desc, status, null);
    }

    /** 内部错误码同时放进 `trace_code`，便于院方排查时对上审计/日志。 */
    private ResponseEntity<Object> oauthError(String error, String desc, HttpStatus status, ErrorCode code) {
        Map<String, Object> body = new LinkedHashMap<>();
        if (code != null) {
            body.put("trace_code", code.code());
        }
        body.put("error", error);
        body.put("error_description", desc == null ? error : desc);
        return ResponseEntity.status(status).body(body);
    }
}
