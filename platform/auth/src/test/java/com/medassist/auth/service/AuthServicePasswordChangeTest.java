package com.medassist.auth.service;

import com.medassist.auth.config.TenantProperties;
import com.medassist.auth.domain.UserAccount;
import com.medassist.auth.repo.RbacRepository;
import com.medassist.auth.repo.TokenRepository;
import com.medassist.auth.repo.UserRepository;
import com.medassist.auth.security.JwtProperties;
import com.medassist.auth.security.JwtTokenService;
import com.medassist.auth.security.LoginThrottleProperties;
import com.medassist.auth.security.PasswordHasher;
import com.medassist.auth.security.PasswordPolicy;
import com.medassist.auth.web.dto.LoginResp;
import com.medassist.common.exception.BusinessException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 自助改密（§285）：口令校验、策略、标记清除、会话撤销四件事必须同时成立。
 *
 * <p>用真实 {@link PasswordHasher}（bcrypt）而不是 mock：这里要验证的正是
 * "拿旧哈希去比对"这类只有真实哈希才暴露的问题（例如误用新口令去比对旧哈希）。
 */
class AuthServicePasswordChangeTest {

    private static final String OLD_PASSWORD = "OldPass123";
    private static final String NEW_PASSWORD = "Ward2026#Round";

    private final UUID userId = UUID.randomUUID();
    private final UUID tokenId = UUID.randomUUID();

    private UserRepository users;
    private TokenRepository tokens;
    private PasswordHasher hasher;
    private AuthService service;

    @BeforeEach
    void setUp() {
        users = mock(UserRepository.class);
        RbacRepository rbac = mock(RbacRepository.class);
        tokens = mock(TokenRepository.class);
        JwtTokenService jwt = mock(JwtTokenService.class);
        hasher = new PasswordHasher();

        UserAccount user = new UserAccount(userId, "doctor01", hasher.encode(OLD_PASSWORD),
                "张医生", "E1001", "D001", UserAccount.STATUS_ACTIVE, true, 0L, null,
                Instant.now(), Instant.now(), "tenant-001");
        when(users.findById(userId)).thenReturn(Optional.of(user));
        when(users.rolesOf(userId)).thenReturn(List.of("DOCTOR"));
        when(users.deptIdsOf(userId)).thenReturn(List.of());
        when(users.campusIdsOf(userId)).thenReturn(List.of());
        when(users.orgNameOf(any())).thenReturn(Optional.empty());
        when(rbac.permissionsOfUser(userId)).thenReturn(List.of("emr:record:read"));

        when(jwt.properties()).thenReturn(new JwtProperties("medassist-auth",
                "unit-test-secret-0123456789abcdefghijklmnop", Duration.ofMinutes(30), Duration.ofDays(7)));
        when(jwt.issue(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new JwtTokenService.IssuedToken("access-new", Instant.now(),
                        Instant.now().plusSeconds(1800), 1800, "jti-new"));

        service = new AuthService(users, rbac, tokens, jwt, mock(LoginThrottle.class), hasher,
                JsonMapper.builder().build(), new SimpleMeterRegistry(),
                mock(TenantService.class), new TenantProperties("tenant-001", null, true),
                new PasswordPolicy(),
                new LoginThrottleProperties(true, 5, 30, Duration.ofMinutes(5)));
    }

    @Test
    @DisplayName("当前密码错误：422，且不改库、不撤销会话")
    void rejectsWrongCurrentPassword() {
        assertThatThrownBy(() -> service.changeOwnPassword(userId, tokenId,
                "WrongPass1", NEW_PASSWORD, "10.0.0.8", "ua"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("当前密码不正确");

        verify(users, never()).updatePassword(any(), any(), anyBoolean());
        verify(tokens, never()).revokeAllForUser(any());
    }

    @Test
    @DisplayName("新口令与当前口令相同：拒绝")
    void rejectsSamePassword() {
        assertThatThrownBy(() -> service.changeOwnPassword(userId, tokenId,
                OLD_PASSWORD, OLD_PASSWORD, "10.0.0.8", "ua"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("不能与当前密码相同");
    }

    @Test
    @DisplayName("新口令不符合策略：拒绝并保留首登标记")
    void rejectsWeakNewPassword() {
        assertThatThrownBy(() -> service.changeOwnPassword(userId, tokenId,
                OLD_PASSWORD, "12345678", "10.0.0.8", "ua"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("字母与数字");

        verify(users, never()).updatePassword(any(), any(), anyBoolean());
    }

    @Test
    @DisplayName("改密成功：清首登标记、撤销全部刷新令牌与当前 jti、下发新令牌")
    void rotatesCredentialsAndClearsFlag() {
        LoginResp resp = service.changeOwnPassword(userId, tokenId,
                OLD_PASSWORD, NEW_PASSWORD, "10.0.0.8", "unit-test-agent");

        ArgumentCaptor<String> hash = ArgumentCaptor.forClass(String.class);
        verify(users).updatePassword(eq(userId), hash.capture(), eq(false));
        assertThat(hasher.verify(NEW_PASSWORD, hash.getValue())).isTrue();
        assertThat(hasher.verify(OLD_PASSWORD, hash.getValue())).isFalse();

        verify(tokens).revokeAllForUser(userId);
        verify(tokens).revokeJti(eq(tokenId), eq(userId), any(),
                eq("PASSWORD_CHANGE"), eq("doctor01"));
        verify(tokens).insertRefresh(any(), eq(userId), any(), any(), isNull(),
                eq("10.0.0.8"), eq("unit-test-agent"));

        assertThat(resp.accessToken()).isEqualTo("access-new");
        assertThat(resp.user().mustChangePassword()).isFalse();
        assertThat(resp.refreshToken()).isNotBlank();
    }

    @Test
    @DisplayName("SSO 登录作废临时本地口令：避免本人卡在“不知道当前口令”的强制改密里")
    void ssoRetiresTemporaryPassword() {
        UserAccount temporary = new UserAccount(userId, "doctor01", hasher.encode(OLD_PASSWORD),
                "张医生", "E1001", "D001", UserAccount.STATUS_ACTIVE, true, 0L, null,
                Instant.now(), Instant.now(), "tenant-001");

        LoginResp resp = service.loginExternal(temporary, "oidc", "subject-1", "10.0.0.9", "ua");

        // 口令被随机化（管理员手里的旧口令立即失效），标记清零
        ArgumentCaptor<String> hash = ArgumentCaptor.forClass(String.class);
        verify(users).updatePassword(eq(userId), hash.capture(), eq(false));
        assertThat(hasher.verify(OLD_PASSWORD, hash.getValue())).isFalse();
        assertThat(resp.user().mustChangePassword()).isFalse();
    }
}
