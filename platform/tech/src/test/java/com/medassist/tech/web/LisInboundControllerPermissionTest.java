package com.medassist.tech.web;

import com.medassist.common.api.ErrorCode;
import com.medassist.common.exception.BusinessException;
import com.medassist.common.security.DataScope;
import com.medassist.tech.service.LisInboundService;
import com.medassist.tech.service.TechAccessGuard;
import com.medassist.tech.web.TechDtos.OruResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * LIS 入站与重放接口权限矩阵（G67）。
 */
class LisInboundControllerPermissionTest {

    private LisInboundService service;
    private TechAccessGuard guard;
    private LisInboundController controller;

    private final UUID messageId = UUID.randomUUID();
    private final UUID applicationId = UUID.randomUUID();
    private final DataScope scope = DataScope.all("tech01", true);

    @BeforeEach
    void setUp() {
        service = mock(LisInboundService.class);
        guard = mock(TechAccessGuard.class);
        controller = new LisInboundController(service, guard);
    }

    private void assertForbidden(Runnable call) {
        assertThatThrownBy(call::run)
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.FORBIDDEN);
    }

    @Test
    @DisplayName("重放：必须 tech:lis:write")
    void replayRequiresWrite() {
        assertForbidden(() -> controller.replay(messageId, "tech:lis:read", "tech01", scope));
        verify(service, never()).replay(any(), anyString());
    }

    @Test
    @DisplayName("重放：关联申请时必须过院区守卫，越权直接 404 且不进入重放")
    void replayChecksApplicationCampus() {
        when(service.applicationIdOf(messageId)).thenReturn(applicationId);
        org.mockito.Mockito.doThrow(new BusinessException(ErrorCode.TECH_APPLICATION_NOT_FOUND))
                .when(guard).requireApplication(scope, applicationId);

        assertThatThrownBy(() -> controller.replay(messageId, "tech:lis:write", "tech01", scope))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.TECH_APPLICATION_NOT_FOUND);
        verify(service, never()).replay(any(), anyString());
    }

    @Test
    @DisplayName("重放：无关申请（申请号不存在的失败消息）不做院区校验")
    void replayWithoutApplicationSkipsGuard() {
        when(service.applicationIdOf(messageId)).thenReturn(null);
        when(service.replay(messageId, "tech01"))
                .thenReturn(new OruResult(messageId, "MC-1", "FAILED", "AP1",
                        null, null, "申请号不存在", false));

        controller.replay(messageId, "tech:lis:write", "tech01", scope);

        verify(guard, never()).requireApplication(eq(scope), any());
        verify(service).replay(messageId, "tech01");
    }
}
