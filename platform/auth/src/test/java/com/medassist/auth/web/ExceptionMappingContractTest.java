package com.medassist.auth.web;

import com.medassist.common.api.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 错误码 → HTTP 状态契约测试（P1-3）。
 *
 * <p>为什么需要：{@code statusOf} 是 switch，新增 {@link ErrorCode} 时忘加分支会落进
 * {@code default -> 400}，服务层单测全绿、线上却把"流程未完成/状态非法"报成"参数错误"。
 * 本测试用枚举全量遍历把"漏配"变成可见失败，并锁定全平台一致的核心码映射。
 */
class ExceptionMappingContractTest {

    // auth 的处理器需要限流配置（只影响 429 的 Retry-After 头）；契约测试只关心状态码映射
    private final GlobalExceptionHandler handler = new GlobalExceptionHandler(null);

    @Test
    @DisplayName("每个错误码都必须有确定的 HTTP 状态（不允许返回 null）")
    void everyErrorCodeHasStatus() {
        for (ErrorCode code : ErrorCode.values()) {
            assertThat(handler.statusOf(code)).as("错误码 %s 未映射 HTTP 状态", code).isNotNull();
        }
    }

    @Test
    @DisplayName("核心错误码在全平台映射一致（防各域口径漂移）")
    void coreCodesAreConsistentAcrossDomains() {
        assertThat(handler.statusOf(ErrorCode.VALIDATION_FAILED)).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(handler.statusOf(ErrorCode.NOT_FOUND)).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(handler.statusOf(ErrorCode.FORBIDDEN)).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(handler.statusOf(ErrorCode.UNAUTHORIZED)).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(handler.statusOf(ErrorCode.CONFLICT)).isEqualTo(HttpStatus.CONFLICT);
        assertThat(handler.statusOf(ErrorCode.TOO_MANY_REQUESTS)).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(handler.statusOf(ErrorCode.UPSTREAM_UNAVAILABLE)).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(handler.statusOf(ErrorCode.GATEWAY_TIMEOUT)).isEqualTo(HttpStatus.GATEWAY_TIMEOUT);
        // P1-D6：新增 auth 专属码后，HTTP 语义保持不变（仅码更精确）
        assertThat(handler.statusOf(ErrorCode.USER_NOT_FOUND)).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(handler.statusOf(ErrorCode.SESSION_NOT_FOUND)).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(handler.statusOf(ErrorCode.USER_DUPLICATE)).isEqualTo(HttpStatus.CONFLICT);
        assertThat(handler.statusOf(ErrorCode.ROLE_NOT_FOUND)).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(handler.statusOf(ErrorCode.ORG_NOT_FOUND)).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(handler.statusOf(ErrorCode.LAST_ADMIN_PROTECTED)).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    @DisplayName("SSO 错误码：状态/令牌 401、未绑定 403、开户失败 422、IdP 不可用 503")
    void ssoCodesMapToExpectedStatus() {
        assertThat(handler.statusOf(ErrorCode.SSO_DISABLED)).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(handler.statusOf(ErrorCode.SSO_STATE_INVALID)).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(handler.statusOf(ErrorCode.SSO_TOKEN_INVALID)).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(handler.statusOf(ErrorCode.SSO_IDENTITY_NOT_LINKED)).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(handler.statusOf(ErrorCode.SSO_PROVISION_FAILED))
                .isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(handler.statusOf(ErrorCode.SSO_PROVIDER_UNAVAILABLE))
                .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    }

    @Test
    @DisplayName("业务错误码不得落到 5xx（否则前端会把业务拒绝当系统故障重试）")
    void businessCodesAreNotServerErrors() {
        for (ErrorCode code : ErrorCode.values()) {
            // 依赖/系统不可用类允许 5xx（UPSTREAM_UNAVAILABLE / KB_EMBEDDING_UNAVAILABLE 等），
            // 其余业务拒绝被映射成 5xx 会让前端误判为系统故障并重试
            String name = code.name();
            if (name.endsWith("UNAVAILABLE") || name.endsWith("TIMEOUT")
                    || code == ErrorCode.INTERNAL_ERROR) {
                continue;
            }
            assertThat(handler.statusOf(code).is5xxServerError())
                    .as("错误码 %s 被映射为 5xx，业务拒绝不应触发客户端重试", code)
                    .isFalse();
        }
    }
}