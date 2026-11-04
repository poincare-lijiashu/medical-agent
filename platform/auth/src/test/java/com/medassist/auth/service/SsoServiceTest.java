package com.medassist.auth.service;

import com.medassist.auth.config.SsoProperties;
import com.medassist.auth.domain.SsoIdentity;
import com.medassist.auth.domain.UserAccount;
import com.medassist.auth.repo.SsoRepository;
import com.medassist.auth.repo.UserRepository;
import com.medassist.auth.web.dto.LoginResp;
import com.medassist.auth.web.dto.MeResp;
import com.medassist.auth.web.dto.SsoDtos.AuthorizeView;
import com.medassist.auth.web.dto.SsoDtos.CallbackReq;
import com.medassist.common.api.ErrorCode;
import com.medassist.common.exception.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * SSO 授权码 + PKCE 规则：一次性 state、aud/nonce 校验、账号绑定与自动开户。
 */
class SsoServiceTest {

    private OidcClient oidc;
    private SsoRepository sso;
    private UserRepository users;
    private SsoUserProvisioner provisioner;
    private AuthService authService;

    private final UUID userId = UUID.randomUUID();
    private final SsoRepository.LoginState state = new SsoRepository.LoginState(
            "state-1", "OIDC", "verifier-1", "nonce-1", "https://console/sso/callback");

    private SsoService service(boolean enabled, boolean autoProvision) {
        SsoProperties props = new SsoProperties(enabled, "OIDC", "https://idp.example",
                "medassist-console", "secret", "https://console/sso/callback",
                List.of("openid", "profile"), Duration.ofSeconds(2), "preferred_username",
                "email", "name", autoProvision, List.of("DOCTOR"), "D001");
        return new SsoService(props, oidc, sso, users, provisioner, authService,
                JsonMapper.builder().build());
    }

    @BeforeEach
    void setUp() {
        oidc = mock(OidcClient.class);
        sso = mock(SsoRepository.class);
        users = mock(UserRepository.class);
        provisioner = mock(SsoUserProvisioner.class);
        authService = mock(AuthService.class);
        when(oidc.discovery()).thenReturn(new OidcClient.Discovery("https://idp.example",
                "https://idp.example/authorize", "https://idp.example/token",
                "https://idp.example/jwks"));
    }

    @Test
    @DisplayName("未启用/配置不全：config 关闭，authorize 返回 SSO_DISABLED")
    void disabledRejectsAuthorize() {
        SsoService service = service(false, false);
        assertThat(service.config().enabled()).isFalse();
        assertThatThrownBy(() -> service.authorize(null))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.SSO_DISABLED);
    }

    @Test
    @DisplayName("authorize：生成 PKCE(S256)+nonce+state，并落一次性状态")
    void authorizeStoresStateAndBuildsUrl() {
        SsoService service = service(true, false);

        AuthorizeView view = service.authorize(null);

        assertThat(view.authorizeUrl()).contains("response_type=code")
                .contains("client_id=medassist-console")
                .contains("code_challenge_method=S256")
                .contains("state=" + view.state())
                .contains("nonce=")
                .contains("redirect_uri=")
                .contains("console");
        verify(sso).insertState(eq(view.state()), eq("OIDC"), anyString(), anyString(),
                eq("https://console/sso/callback"), any(Instant.class));
    }

    @Test
    @DisplayName("authorize：回调地址与配置不一致时拒绝（防开放重定向）")
    void authorizeRejectsForeignRedirectUri() {
        SsoService service = service(true, false);
        assertThatThrownBy(() -> service.authorize("https://evil.example/cb"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
        verify(sso, never()).insertState(anyString(), anyString(), anyString(), anyString(),
                anyString(), any(Instant.class));
    }

    @Test
    @DisplayName("callback：state 已被消费/过期 → 40111，且不再调用 IdP")
    void callbackRejectsConsumedState() {
        SsoService service = service(true, false);
        when(sso.consumeState("state-1")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.callback(new CallbackReq("code", "state-1"), "ip", "ua"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.SSO_STATE_INVALID);
        verify(oidc, never()).exchangeCode(anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("callback：audience 不包含本系统 client_id → 40112")
    void callbackRejectsWrongAudience() {
        SsoService service = service(true, false);
        when(sso.consumeState("state-1")).thenReturn(Optional.of(state));
        when(oidc.exchangeCode("code", "verifier-1", state.redirectUri())).thenReturn("id-token");
        when(oidc.decodeIdToken("id-token")).thenReturn(jwt("doctor01", "nonce-1", List.of("other")));

        assertThatThrownBy(() -> service.callback(new CallbackReq("code", "state-1"), "ip", "ua"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.SSO_TOKEN_INVALID);
        verify(authService, never()).loginExternal(any(), anyString(), anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("callback：nonce 与发起登录时不一致 → 40112（防 id_token 重放）")
    void callbackRejectsWrongNonce() {
        SsoService service = service(true, false);
        when(sso.consumeState("state-1")).thenReturn(Optional.of(state));
        when(oidc.exchangeCode("code", "verifier-1", state.redirectUri())).thenReturn("id-token");
        when(oidc.decodeIdToken("id-token"))
                .thenReturn(jwt("doctor01", "other-nonce", List.of("medassist-console")));

        assertThatThrownBy(() -> service.callback(new CallbackReq("code", "state-1"), "ip", "ua"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.SSO_TOKEN_INVALID);
    }

    @Test
    @DisplayName("callback：本地有同名账号 → 首次绑定并签发平台令牌")
    void callbackBindsExistingUser() {
        SsoService service = service(true, false);
        UserAccount user = user("doctor01");
        LoginResp expected = loginResp(user);
        when(sso.consumeState("state-1")).thenReturn(Optional.of(state));
        when(oidc.exchangeCode("code", "verifier-1", state.redirectUri())).thenReturn("id-token");
        when(oidc.decodeIdToken("id-token"))
                .thenReturn(jwt("doctor01", "nonce-1", List.of("medassist-console")));
        when(sso.findIdentity("OIDC", "subject-1")).thenReturn(Optional.empty());
        when(users.findByUsername("doctor01")).thenReturn(Optional.of(user));
        when(sso.findIdentityByUser("OIDC", user.id())).thenReturn(Optional.empty());
        when(authService.loginExternal(user, "OIDC", "subject-1", "ip", "ua")).thenReturn(expected);

        LoginResp result = service.callback(new CallbackReq("code", "state-1"), "ip", "ua");

        assertThat(result).isSameAs(expected);
        verify(provisioner).bind(user, "OIDC", "subject-1", "doctor@example.com", "Doctor One");
    }

    @Test
    @DisplayName("callback：本地无同名账号且未开启自动开户 → 40310")
    void callbackRejectsUnlinkedIdentity() {
        SsoService service = service(true, false);
        when(sso.consumeState("state-1")).thenReturn(Optional.of(state));
        when(oidc.exchangeCode("code", "verifier-1", state.redirectUri())).thenReturn("id-token");
        when(oidc.decodeIdToken("id-token"))
                .thenReturn(jwt("ghost01", "nonce-1", List.of("medassist-console")));
        when(sso.findIdentity("OIDC", "subject-1")).thenReturn(Optional.empty());
        when(users.findByUsername("ghost01")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.callback(new CallbackReq("code", "state-1"), "ip", "ua"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.SSO_IDENTITY_NOT_LINKED);
        verify(provisioner, never()).provisionAndBind(any(), anyString(), anyString(), anyString(),
                anyString(), anyString());
    }

    @Test
    @DisplayName("callback：已绑定身份 → 直接按本地账号签发，并刷新绑定信息")
    void callbackUsesBoundIdentity() {
        SsoService service = service(true, false);
        UserAccount user = user("doctor01");
        LoginResp expected = loginResp(user);
        SsoIdentity identity = new SsoIdentity(UUID.randomUUID(), "OIDC", "subject-1", user.id(),
                "doctor@example.com", "Doctor One", Instant.now().minusSeconds(60), Instant.now());
        when(sso.consumeState("state-1")).thenReturn(Optional.of(state));
        when(oidc.exchangeCode("code", "verifier-1", state.redirectUri())).thenReturn("id-token");
        when(oidc.decodeIdToken("id-token"))
                .thenReturn(jwt("doctor01", "nonce-1", List.of("medassist-console")));
        when(sso.findIdentity("OIDC", "subject-1")).thenReturn(Optional.of(identity));
        when(users.findById(user.id())).thenReturn(Optional.of(user));
        when(authService.loginExternal(user, "OIDC", "subject-1", "ip", "ua")).thenReturn(expected);

        LoginResp result = service.callback(new CallbackReq("code", "state-1"), "ip", "ua");

        assertThat(result).isSameAs(expected);
        verify(sso).upsertIdentity(any(SsoIdentity.class));
        verify(provisioner, never()).bind(any(), anyString(), anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("callback：开启自动开户 → 创建本地账号并绑定")
    void callbackAutoProvisionsUser() {
        SsoService service = service(true, true);
        UserAccount user = user("newdoc01");
        LoginResp expected = loginResp(user);
        when(sso.consumeState("state-1")).thenReturn(Optional.of(state));
        when(oidc.exchangeCode("code", "verifier-1", state.redirectUri())).thenReturn("id-token");
        when(oidc.decodeIdToken("id-token"))
                .thenReturn(jwt("newdoc01", "nonce-1", List.of("medassist-console")));
        when(sso.findIdentity("OIDC", "subject-1")).thenReturn(Optional.empty());
        when(users.findByUsername("newdoc01")).thenReturn(Optional.empty());
        when(provisioner.provisionAndBind(any(), eq("OIDC"), eq("subject-1"), eq("newdoc01"),
                eq("Doctor One"), eq("doctor@example.com"))).thenReturn(user);
        when(authService.loginExternal(user, "OIDC", "subject-1", "ip", "ua")).thenReturn(expected);

        LoginResp result = service.callback(new CallbackReq("code", "state-1"), "ip", "ua");

        assertThat(result).isSameAs(expected);
        verify(provisioner).provisionAndBind(any(), eq("OIDC"), eq("subject-1"), eq("newdoc01"),
                eq("Doctor One"), eq("doctor@example.com"));
    }

    // ---------------------------------------------------------------- helpers

    private static Jwt jwt(String username, String nonce, List<String> audience) {
        Instant now = Instant.now();
        return Jwt.withTokenValue("id-token")
                .header("alg", "RS256")
                .subject("subject-1")
                .audience(audience)
                .claim("nonce", nonce)
                .claim("preferred_username", username)
                .claim("email", "doctor@example.com")
                .claim("name", "Doctor One")
                .issuedAt(now)
                .expiresAt(now.plusSeconds(300))
                .build();
    }

    private UserAccount user(String username) {
        Instant now = Instant.now();
        return new UserAccount(userId, username, "hash", "Doctor One", "E001", "D001",
                "ACTIVE", 1L, now, now, now);
    }

    private LoginResp loginResp(UserAccount user) {
        Instant now = Instant.now();
        MeResp me = new MeResp(user.id(), user.username(), user.displayName(), user.employeeNo(),
                user.orgCode(), List.of(), List.of("DOCTOR"), List.of("emr:record:read"), now);
        return new LoginResp("access", "Bearer", 1800, now.plusSeconds(1800),
                "refresh", now.plusSeconds(604800), me);
    }
}
