package com.medassist.auth.web;

import com.medassist.auth.service.UserService;
import com.medassist.auth.web.dto.AssignCampusesReq;
import com.medassist.common.api.ErrorCode;
import com.medassist.common.exception.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 院区分配接口权限（G45）：必须 auth:user:update。 */
class UserControllerCampusTest {

    private UserService service;
    private UserController controller;
    private final UUID userId = UUID.randomUUID();
    private final List<UUID> campuses = List.of(UUID.randomUUID());

    @BeforeEach
    void setUp() {
        service = mock(UserService.class);
        controller = new UserController(service);
    }

    @Test
    @DisplayName("分配院区：只读权限 403，update 权限放行")
    void assignCampusesRequiresUpdate() {
        AssignCampusesReq req = new AssignCampusesReq(campuses);

        assertThatThrownBy(() -> controller.assignCampuses(userId, req,
                "auth:user:read", "admin01"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.FORBIDDEN);

        controller.assignCampuses(userId, req, "auth:user:update", "admin01");
        verify(service).assignCampuses(userId, campuses, "admin01");
    }
}
