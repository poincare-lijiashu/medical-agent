package com.medassist.auth.web;

import com.medassist.auth.domain.Campus;
import com.medassist.auth.service.CampusService;
import com.medassist.auth.web.dto.CreateCampusReq;
import com.medassist.auth.web.dto.UpdateCampusReq;
import com.medassist.common.api.ErrorCode;
import com.medassist.common.exception.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 院区维护接口的权限矩阵（G46，直接驱动 Controller）。 */
class CampusControllerPermissionTest {

    private CampusService service;
    private CampusController controller;

    private final UUID id = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = mock(CampusService.class);
        controller = new CampusController(service);
    }

    @Test
    @DisplayName("院区列表：缺 auth:campus:read 时 403，且不触达服务")
    void listRequiresReadPermission() {
        assertThatThrownBy(() -> controller.list("auth:user:read"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.FORBIDDEN);
        verify(service, never()).list();
    }

    @Test
    @DisplayName("院区列表：auth:campus:read 放行")
    void listAllowedWithReadPermission() {
        List<Campus> campuses = List.of(
                new Campus(id, "MAIN", "主院区", Campus.ACTIVE, 10, 0L));
        when(service.list()).thenReturn(campuses);

        assertThat(controller.list("auth:campus:read").data()).isEqualTo(campuses);
    }

    @Test
    @DisplayName("新建院区：只有查看权不能建（需 auth:campus:manage）")
    void createRequiresManagePermission() {
        var req = new CreateCampusReq("WEST", "西院区", 30);

        assertThatThrownBy(() -> controller.create(req, "auth:campus:read", "admin01"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.FORBIDDEN);
        verify(service, never()).create(any(), anyString());
    }

    @Test
    @DisplayName("新建院区：auth:campus:manage 放行")
    void createAllowedWithManagePermission() {
        var req = new CreateCampusReq("WEST", "西院区", 30);
        var campus = new Campus(id, "WEST", "西院区", Campus.ACTIVE, 30, 0L);
        when(service.create(any(), anyString())).thenReturn(campus);

        assertThat(controller.create(req, "auth:campus:manage", "admin01").data()).isEqualTo(campus);
    }

    @Test
    @DisplayName("修改院区：只有查看权不能改")
    void updateRequiresManagePermission() {
        var req = new UpdateCampusReq("西院区", Campus.DISABLED, 30, 0L);

        assertThatThrownBy(() -> controller.update(id, req, "auth:campus:read", "admin01"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.FORBIDDEN);
        verify(service, never()).update(any(), any(), anyString());
    }
}
