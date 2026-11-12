package com.medassist.auth.service;

import com.medassist.auth.config.TenantProperties;
import com.medassist.auth.domain.UserAccount;
import com.medassist.auth.repo.RbacRepository;
import com.medassist.auth.repo.TokenRepository;
import com.medassist.auth.repo.UserRepository;
import com.medassist.auth.security.PasswordHasher;
import com.medassist.auth.security.PasswordPolicy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * P0-S1 回归：改密与停用必须撤销该账号全部刷新令牌。
 *
 * <p>否则被盗的 refresh token 在改密/停用后仍能续签（有效期 7 天），等价于"改密无效"。
 */
class UserServiceTokenRevocationTest {

    private UserRepository users;
    private TokenRepository tokens;
    private UserService service;

    private final UUID userId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        users = mock(UserRepository.class);
        tokens = mock(TokenRepository.class);
        service = new UserService(users, mock(RbacRepository.class), JsonMapper.builder().build(),
                mock(PasswordHasher.class), tokens,
                new TenantProperties("tenant-001", null, true), new PasswordPolicy());

        when(users.findById(userId)).thenReturn(Optional.of(new UserAccount(userId, "doctor01",
                "$2a$10$mock", "张医生", "E1001", null, UserAccount.STATUS_ACTIVE, false, 0L, null,
                Instant.now(), Instant.now(), "tenant-001")));
    }

    @Test
    @DisplayName("改密后撤销全部刷新令牌")
    void resetPasswordRevokesSessions() {
        when(users.updatePassword(any(), any(), anyBoolean())).thenReturn(true);

        service.resetPassword(userId, "NewPass123", "admin01", "tenant-001");

        verify(tokens).revokeAllForUser(userId);
    }

    @Test
    @DisplayName("管理员重置口令：生成一次性临时口令，并置首登必改标记（§285）")
    void resetPasswordGeneratesTemporaryCredential() {
        when(users.updatePassword(any(), anyString(), anyBoolean())).thenReturn(true);

        String temp = service.resetPassword(userId, null, "admin01", "tenant-001");

        assertThat(temp).hasSize(20);
        verify(users).updatePassword(eq(userId), any(), eq(true));
        verify(tokens).revokeAllForUser(userId);
    }

    @Test
    @DisplayName("管理员显式指定口令也要置首登必改标记")
    void resetPasswordWithExplicitValueStillForcesChange() {
        when(users.updatePassword(any(), any(), anyBoolean())).thenReturn(true);

        String temp = service.resetPassword(userId, "Ward2026#Round", "admin01", "tenant-001");

        assertThat(temp).isNull();
        verify(users).updatePassword(eq(userId), any(), eq(true));
    }

    @Test
    @DisplayName("停用账号后撤销全部刷新令牌")
    void disableRevokesSessions() {
        when(users.rolesOf(userId)).thenReturn(List.of("DOCTOR"));
        when(users.updateStatus(any(), anyString(), anyLong())).thenReturn(true);

        service.disable(userId, "admin01", "tenant-001");

        verify(tokens).revokeAllForUser(userId);
    }
}
