package com.medassist.pharmacy.web;

import com.medassist.common.api.ErrorCode;
import com.medassist.common.exception.BusinessException;
import com.medassist.pharmacy.service.AntibioticService;
import com.medassist.pharmacy.web.AntibioticDtos.ApprovalReq;
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
 * 抗菌药物专项接口权限矩阵：查看与审批分权。
 */
class AntibioticControllerPermissionTest {

    private AntibioticService service;
    private AntibioticController controller;

    private final UUID rxId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = mock(AntibioticService.class);
        controller = new AntibioticController(service);
    }

    private void assertForbidden(Runnable call) {
        assertThatThrownBy(call::run)
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.FORBIDDEN);
    }

    @Test
    @DisplayName("统计/待审/审批详情：必须 pharmacy:antibiotic:read")
    void readEndpointsRequireRead() {
        assertForbidden(() -> controller.stats(null, null, "pharmacy:antibiotic:approve"));
        controller.stats(null, null, "pharmacy:antibiotic:read");
        verify(service).stats(null, null);

        assertForbidden(() -> controller.pending(10, null));
        controller.pending(10, "pharmacy:antibiotic:read");
        verify(service).pending(10);

        assertForbidden(() -> controller.approval(rxId, "pharmacy:read"));
        controller.approval(rxId, "pharmacy:antibiotic:read");
        verify(service).approval(rxId);
    }

    @Test
    @DisplayName("专项审批：必须 pharmacy:antibiotic:approve，只读权限不能代审")
    void approveRequiresApprove() {
        ApprovalReq req = new ApprovalReq(true, null);

        assertForbidden(() -> controller.approve(rxId, req, "pharmacy:antibiotic:read", "pharm01"));
        verify(service, never()).approve(any(), any(), any());

        controller.approve(rxId, req, "pharmacy:antibiotic:approve", "pharm01");
        verify(service).approve(rxId, req, "pharm01");
    }

    @Test
    @DisplayName("权限头为空：403 且不触达服务层")
    void emptyPermissionsForbidden() {
        assertForbidden(() -> controller.approval(rxId, null));
        verify(service, never()).approval(any());
    }
}
