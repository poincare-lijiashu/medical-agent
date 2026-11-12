package com.medassist.auth.web;

import com.medassist.auth.security.LoginThrottleProperties;
import com.medassist.common.api.ErrorCode;
import com.medassist.common.web.AbstractApiExceptionHandler;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * auth 服务的异常映射（实现基类，仅给出本域语义差异）。
 *
 * <p>429 的 Retry-After 取登录限速窗口的真实长度，而不是写死 300s——
 * 前端按该值提示"X 分钟后重试"，写死会在窗口调整后给出错误预期（S17）。
 */
@RestControllerAdvice
public class GlobalExceptionHandler extends AbstractApiExceptionHandler {

    private final long retryAfterSeconds;

    public GlobalExceptionHandler(LoginThrottleProperties throttle) {
        this.retryAfterSeconds = throttle == null ? 300L : Math.max(1L, throttle.window().toSeconds());
    }

    @Override
    protected HttpStatus statusOf(ErrorCode code) {
        return switch (code) {
            case NOT_FOUND, PATIENT_NOT_FOUND, REGISTRATION_NO_SLOT,
                 USER_NOT_FOUND, SESSION_NOT_FOUND, OAUTH_CLIENT_NOT_FOUND -> HttpStatus.NOT_FOUND;
            case UNAUTHORIZED, SSO_STATE_INVALID, SSO_TOKEN_INVALID, OAUTH_SECRET_INVALID -> HttpStatus.UNAUTHORIZED;
            case FORBIDDEN, LAST_ADMIN_PROTECTED, SSO_IDENTITY_NOT_LINKED -> HttpStatus.FORBIDDEN;
            case CONFLICT, PATIENT_DUPLICATE, PATIENT_MERGE_CONFLICT, REGISTRATION_DUPLICATED,
                 DRUG_STOCK_INSUFFICIENT, DRUG_BATCH_EXPIRED, USER_DUPLICATE,
                 OAUTH_CLIENT_STATUS_INVALID -> HttpStatus.CONFLICT;
            case VALIDATION_FAILED, ROLE_NOT_FOUND, ORG_NOT_FOUND, SSO_PROVISION_FAILED,
                 OAUTH_CLIENT_INVALID, OAUTH_SCOPE_NOT_ALLOWED, OAUTH_CLIENT_DISABLED ->
                    HttpStatus.UNPROCESSABLE_ENTITY;
            case TOO_MANY_REQUESTS -> HttpStatus.TOO_MANY_REQUESTS;
            case UPSTREAM_UNAVAILABLE, SSO_PROVIDER_UNAVAILABLE -> HttpStatus.SERVICE_UNAVAILABLE;
            case GATEWAY_TIMEOUT -> HttpStatus.GATEWAY_TIMEOUT;
            default -> HttpStatus.BAD_REQUEST;
        };
    }

    @Override
    protected long retryAfterSeconds() {
        return retryAfterSeconds;
    }
}
