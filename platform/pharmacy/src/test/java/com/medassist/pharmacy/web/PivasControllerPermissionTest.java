package com.medassist.pharmacy.web;

import com.medassist.common.api.ErrorCode;
import com.medassist.common.exception.BusinessException;
import com.medassist.pharmacy.service.PivasService;
import com.medassist.pharmacy.web.PivasDtos.CheckPivasReq;
import com.medassist.pharmacy.web.PivasDtos.CreatePivasOrderReq;
import com.medassist.pharmacy.web.PivasDtos.RejectPivasReq;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * 静配接口权限矩阵：查看 / 配置 / 复核三权分立。
 */
class PivasControllerPermissionTest {

    private PivasService service;
    private PivasController controller;

    private final UUID id = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = mock(PivasService.class);
        controller = new PivasController(service);
    }

    private void assertForbidden(Runnable call) {
        assertThatThrownBy(call::run)
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.FORBIDDEN);
    }

    @Test
    @DisplayName("查看：必须 pharmacy:pivas:read")
    void readEndpointsRequireRead() {
        assertForbidden(() -> controller.list(null, 10, "pharmacy:pivas:check"));
        controller.list("PENDING", 10, "pharmacy:pivas:read");
        verify(service).list("PENDING", 10);

        assertForbidden(() -> controller.detail(id, "pharmacy:pivas:prepare"));
        controller.detail(id, "pharmacy:pivas:read");
        verify(service).detail(id);

        assertForbidden(() -> controller.trace("BAG-1", null));
        controller.trace("BAG-1", "pharmacy:pivas:read");
        verify(service).trace("BAG-1");

        assertForbidden(() -> controller.summary("pharmacy:read"));
        controller.summary("pharmacy:pivas:read");
        verify(service).summary();
    }

    @Test
    @DisplayName("建单/配置：必须 pharmacy:pivas:prepare")
    void prepareEndpointsRequirePrepare() {
        CreatePivasOrderReq req = new CreatePivasOrderReq(id, null, null, null, null, null);

        assertForbidden(() -> controller.create(req, "pharmacy:pivas:check", "pharm01"));
        verify(service, never()).create(any(), any());
        controller.create(req, "pharmacy:pivas:prepare", "pharm01");
        verify(service).create(req, "pharm01");

        assertForbidden(() -> controller.prepare(id, "pharmacy:pivas:read", "pharm01"));
        verify(service, never()).prepare(any(), any());
        controller.prepare(id, "pharmacy:pivas:prepare", "pharm01");
        verify(service).prepare(id, "pharm01");
    }

    @Test
    @DisplayName("复核/驳回：必须 pharmacy:pivas:check，配置权限不能代复核")
    void checkEndpointsRequireCheck() {
        CheckPivasReq req = new CheckPivasReq(true, null);

        assertForbidden(() -> controller.check(id, req, "pharmacy:pivas:prepare", "pharm02"));
        verify(service, never()).check(any(), any(), any());
        controller.check(id, req, "pharmacy:pivas:check", "pharm02");
        verify(service).check(id, req, "pharm02");

        RejectPivasReq reject = new RejectPivasReq("处方停用");
        assertForbidden(() -> controller.reject(id, reject, "pharmacy:pivas:prepare", "pharm02"));
        verify(service, never()).reject(any(), any(), any());
        controller.reject(id, reject, "pharmacy:pivas:check", "pharm02");
        verify(service).reject(id, reject, "pharm02");
    }

    @Test
    @DisplayName("权限头为空：403 且不触达服务层")
    void emptyPermissionsForbidden() {
        assertForbidden(() -> controller.detail(id, null));
        verify(service, never()).detail(any());
    }
}
