package com.medassist.auth.service;

import com.medassist.auth.domain.Role;
import com.medassist.auth.domain.UserAccount;
import com.medassist.auth.repo.RbacRepository;
import com.medassist.auth.repo.TokenRepository;
import com.medassist.auth.repo.UserRepository;
import com.medassist.auth.security.PasswordHasher;
import com.medassist.auth.web.dto.CreateUserReq;
import com.medassist.common.api.ErrorCode;
import com.medassist.common.exception.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

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
 * 多科室执业的开户规则：临床角色必须绑定科室，非临床角色可以不绑定。
 *
 * <p>为什么把这条规则放在开户而不是登录：不绑定科室的医师登录后每个请求都会 403，
 * 现场只会看到"系统坏了"；在开户时直接拒绝，问题定位成本低一个量级。
 */
class UserServiceDeptTest {

    private UserRepository users;
    private RbacRepository rbac;
    private UserService service;

    private final UUID deptA = UUID.randomUUID();
    private final UUID deptB = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        users = mock(UserRepository.class);
        rbac = mock(RbacRepository.class);
        PasswordHasher hasher = mock(PasswordHasher.class);
        service = new UserService(users, rbac, JsonMapper.builder().build(), hasher,
                mock(TokenRepository.class));

        when(users.existsByUsername(anyString())).thenReturn(false);
        when(hasher.encode(anyString())).thenReturn("$2a$10$mock");
        when(rbac.findRole(anyString())).thenAnswer(inv ->
                Optional.of(new Role(inv.getArgument(0), "角色", "", true)));
        when(users.findById(any())).thenAnswer(inv -> {
            UUID id = inv.getArgument(0);
            return Optional.of(new UserAccount(id, "doctor01", "$2a$10$mock", "张医生",
                    "E1001", null, UserAccount.STATUS_ACTIVE, 0L, null,
                    Instant.now(), Instant.now()));
        });
        when(users.rolesOf(any())).thenReturn(List.of("DOCTOR"));
        when(users.deptIdsOf(any())).thenReturn(List.of(deptA, deptB));
    }

    private CreateUserReq req(List<String> roles, List<UUID> deptIds) {
        return new CreateUserReq("doctor01", "Abc12345", "张医生", "E1001", null,
                deptIds, roles);
    }

    @Test
    @DisplayName("医师未绑定科室 → 422，且不落库")
    void doctorWithoutDepartmentIsRejected() {
        assertThatThrownBy(() -> service.create(req(List.of("DOCTOR"), null), "admin01"))
                .isInstanceOfSatisfying(BusinessException.class, e ->
                        assertThat(e.errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED));

        verify(users, never()).insert(any());
        verify(users, never()).replaceDepts(any(), any());
    }

    @Test
    @DisplayName("医师绑定两个科室 → 开户时写入多科室绑定")
    void doctorWithDepartmentsPersistsBindings() {
        service.create(req(List.of("DOCTOR"), List.of(deptA, deptB)), "admin01");

        verify(users).replaceDepts(any(), eq(List.of(deptA, deptB)));
    }

    @Test
    @DisplayName("非临床角色（质控）可以不绑定科室")
    void nonClinicalRoleDoesNotRequireDepartments() {
        when(users.rolesOf(any())).thenReturn(List.of("QC"));
        when(users.deptIdsOf(any())).thenReturn(List.of());

        service.create(req(List.of("QC"), null), "admin01");

        verify(users).replaceDepts(any(), eq(List.of()));
    }

    @Test
    @DisplayName("科室列表去重：重复绑定不会写两行")
    void duplicateDepartmentsAreDeduplicated() {
        service.create(req(List.of("DOCTOR"), List.of(deptA, deptA, deptB)), "admin01");

        verify(users).replaceDepts(any(), eq(List.of(deptA, deptB)));
    }

    @Test
    @DisplayName("科室数超过 20 → 422（防止用科室列表拼出全院）")
    void tooManyDepartmentsIsRejected() {
        List<UUID> many = java.util.stream.IntStream.range(0, 21)
                .mapToObj(i -> UUID.randomUUID()).toList();

        assertThatThrownBy(() -> service.create(req(List.of("DOCTOR"), many), "admin01"))
                .isInstanceOfSatisfying(BusinessException.class, e ->
                        assertThat(e.errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED));
        verify(users, never()).insert(any());
    }
}
