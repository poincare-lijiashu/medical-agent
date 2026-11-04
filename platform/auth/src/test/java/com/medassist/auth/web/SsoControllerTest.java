package com.medassist.auth.web;

import com.medassist.auth.service.SsoService;
import com.medassist.auth.web.dto.LoginResp;
import com.medassist.auth.web.dto.MeResp;
import com.medassist.auth.web.dto.SsoDtos.AuthorizeView;
import com.medassist.auth.web.dto.SsoDtos.CallbackReq;
import com.medassist.auth.web.dto.SsoDtos.ConfigView;
import com.medassist.common.api.ApiResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * SSO 控制器：登录前接口不依赖权限头，且回调必须把可信客户端信息透传给服务层。
 */
class SsoControllerTest {

    private final SsoService service = mock(SsoService.class);
    private final SsoController controller = new SsoController(service);

    @Test
    @DisplayName("config：直接透出非敏感配置（不含 client_secret）")
    void configDelegates() {
        ConfigView view = new ConfigView(true, "OIDC", "https://idp", "client", null,
                List.of("openid"));
        when(service.config()).thenReturn(view);

        ApiResponse<ConfigView> response = controller.config();

        assertThat(response.data()).isSameAs(view);
    }

    @Test
    @DisplayName("authorize：允许空请求体（使用配置的回调地址）")
    void authorizeAllowsEmptyBody() {
        AuthorizeView view = new AuthorizeView("https://idp/authorize?state=s", "s");
        when(service.authorize(null)).thenReturn(view);

        assertThat(controller.authorize(null).data()).isSameAs(view);
    }

    @Test
    @DisplayName("callback：把可信 IP 与 User-Agent 透传给服务层")
    void callbackPassesClientContext() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("10.20.30.40");
        request.addHeader("User-Agent", "JUnit");
        LoginResp login = loginResp();
        when(service.callback(any(CallbackReq.class), eq("10.20.30.40"), eq("JUnit")))
                .thenReturn(login);

        LoginResp result = controller.callback(new CallbackReq("code", "state"), request).data();

        assertThat(result).isSameAs(login);
        verify(service).callback(any(CallbackReq.class), eq("10.20.30.40"), eq("JUnit"));
    }

    private static LoginResp loginResp() {
        Instant now = Instant.now();
        UUID id = UUID.randomUUID();
        MeResp me = new MeResp(id, "doctor01", "Doctor", "E001", "D001", List.of(),
                List.of("DOCTOR"), List.of("emr:record:read"), now);
        return new LoginResp("access", "Bearer", 1800, now.plusSeconds(1800),
                "refresh", now.plusSeconds(604800), me);
    }
}
