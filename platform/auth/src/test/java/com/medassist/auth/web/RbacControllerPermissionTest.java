package com.medassist.auth.web;

import com.medassist.auth.domain.Org;
import com.medassist.auth.repo.RbacRepository;
import com.medassist.common.api.ErrorCode;
import com.medassist.common.exception.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 角色/机构参考数据接口的权限矩阵（直接驱动 Controller，不启动 Spring 上下文）。 */
class RbacControllerPermissionTest {

    private RbacRepository repo;
    private RbacController controller;

    @BeforeEach
    void setUp() {
        repo = mock(RbacRepository.class);
        controller = new RbacController(repo);
    }

    @Test
    @DisplayName("角色参考数据：缺 auth:user:read 时 403，且不触达仓储")
    void rolesRequireReadPermission() {
        assertThatThrownBy(() -> controller.roles("auth:user:create"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.FORBIDDEN);
        verify(repo, never()).listRoles();
    }

    @Test
    @DisplayName("机构参考数据：auth:user:read 放行并保留排序")
    void orgsAllowedWithReadPermission() {
        List<Org> orgs = List.of(new Org("D001", "内科", null, "DEPT", "ACTIVE", 1));
        when(repo.listOrgs()).thenReturn(orgs);

        assertThat(controller.orgs("auth:user:read").data()).isEqualTo(orgs);
    }
}
