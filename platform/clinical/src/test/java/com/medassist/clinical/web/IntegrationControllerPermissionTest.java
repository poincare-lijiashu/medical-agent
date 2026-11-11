package com.medassist.clinical.web;

import com.medassist.clinical.service.IntegrationMessageService;
import com.medassist.clinical.web.IntegrationDtos.IntegrationEnqueueReq;
import com.medassist.common.api.ErrorCode;
import com.medassist.common.exception.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 集成台账接口权限：查看与重投分离，缺权限 403。
 */
class IntegrationControllerPermissionTest {

    private IntegrationMessageService service;
    private IntegrationController controller;

    @BeforeEach
    void setUp() {
        service = mock(IntegrationMessageService.class);
        controller = new IntegrationController(service);
    }

    @Test
    @DisplayName("查看：缺 integration:message:read 时 403")
    void listAndSummaryRequireRead() {
        assertThatThrownBy(() -> controller.list(null, null, 20, null))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.FORBIDDEN);
        assertThatThrownBy(() -> controller.summary(null))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.FORBIDDEN);
        verify(service, never()).list(any(), any(), any());
        verify(service, never()).summary();
    }

    @Test
    @DisplayName("登记与重投：需要 retry 权限，且透传操作者")
    void enqueueAndRetryRequireRetryPermission() {
        var req = new IntegrationEnqueueReq("HIS", "ADT_A01", "P001", "MSH|^~\\&|HIS");
        assertThatThrownBy(() -> controller.enqueue(req, "integration:message:read", "ops01"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.FORBIDDEN);
        verify(service, never()).enqueue(any(), anyString());

        controller.enqueue(req, "integration:message:retry", "ops01");
        verify(service).enqueue(req, "ops01");

        UUID id = UUID.randomUUID();
        when(service.retry(id, "ops01")).thenReturn(null);
        controller.retry(id, "integration:message:retry", "ops01");
        verify(service).retry(id, "ops01");
    }
}
