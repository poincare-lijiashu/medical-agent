package com.medassist.auth.web;

import com.medassist.auth.service.AuthService;
import com.medassist.auth.web.dto.VerifyTokenReq;
import com.medassist.common.api.ErrorCode;
import com.medassist.common.exception.BusinessException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * 内部接口鉴权：{@code /internal/**} 必须 fail-closed。
 *
 * <p>{@code /internal/token/verify} 会返回完整 claims、{@code /internal/revocations}
 * 会返回全院撤销名单——只靠"网关 404"挡外部不足以防集群内任意主机调用。
 */
class AuthControllerInternalTest {

    private final AuthService service = mock(AuthService.class);

    @Test
    @DisplayName("内部令牌未配置：即使客户端带任意令牌也拒绝（fail-closed）")
    void rejectsWhenTokenNotConfigured() {
        AuthController controller = new AuthController(service, "");

        assertThatThrownBy(() -> controller.revocations(0L, "anything"))
                .isInstanceOfSatisfying(BusinessException.class, e ->
                        assertThat(e.errorCode()).isEqualTo(ErrorCode.FORBIDDEN));
        verify(service, never()).revocationsSince(any());
    }

    @Test
    @DisplayName("缺少 X-Internal-Token：403，且不调用业务逻辑")
    void rejectsWhenHeaderMissing() {
        AuthController controller = new AuthController(service, "internal-secret");

        assertThatThrownBy(() -> controller.verify(new VerifyTokenReq("jwt"), null))
                .isInstanceOfSatisfying(BusinessException.class, e ->
                        assertThat(e.errorCode()).isEqualTo(ErrorCode.FORBIDDEN));
        verify(service, never()).verify(anyString());
    }

    @Test
    @DisplayName("令牌不匹配：403")
    void rejectsWhenTokenMismatched() {
        AuthController controller = new AuthController(service, "internal-secret");

        assertThatThrownBy(() -> controller.revocations(0L, "wrong-secret"))
                .isInstanceOfSatisfying(BusinessException.class, e ->
                        assertThat(e.errorCode()).isEqualTo(ErrorCode.FORBIDDEN));
        verify(service, never()).revocationsSince(any());
    }

    @Test
    @DisplayName("令牌匹配：放行并委派给 AuthService")
    void allowsWithMatchingToken() {
        AuthController controller = new AuthController(service, "internal-secret");

        controller.revocations(0L, "internal-secret");
        controller.verify(new VerifyTokenReq("jwt"), "internal-secret");

        verify(service).revocationsSince(any());
        verify(service).verify("jwt");
    }
}
