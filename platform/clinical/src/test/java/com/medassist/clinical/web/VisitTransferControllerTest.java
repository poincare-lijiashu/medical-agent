package com.medassist.clinical.web;

import com.medassist.clinical.domain.ClinicalModel.VisitTransfer;
import com.medassist.clinical.service.VisitTransferService;
import com.medassist.clinical.web.ClinicalDtos.TransferVisitReq;
import com.medassist.common.api.ErrorCode;
import com.medassist.common.exception.BusinessException;
import com.medassist.common.security.DataScope;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
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
 * 转科接口的权限串：读用 {@code clinical:visit:read}，写用 {@code clinical:visit:treat}。
 *
 * <p>权限串写错时接口本身仍能跑通，只有把"缺权限"用例固化下来才能防回归。
 */
class VisitTransferControllerTest {

    private VisitTransferService service;
    private VisitTransferController controller;

    private final UUID visitId = UUID.randomUUID();
    private final UUID deptId = UUID.randomUUID();
    private final DataScope scope = DataScope.ofDepartment("doctor01", deptId, true);

    @BeforeEach
    void setUp() {
        service = mock(VisitTransferService.class);
        controller = new VisitTransferController(service);
    }

    @Test
    @DisplayName("转科：缺少 clinical:visit:treat 时 403，且不触达服务层")
    void transferRequiresTreatPermission() {
        assertThatThrownBy(() -> controller.transfer(visitId,
                new TransferVisitReq(deptId, null, "需要转科"),
                "clinical:visit:read", "doctor01", scope))
                .isInstanceOfSatisfying(BusinessException.class, e ->
                        assertThat(e.errorCode()).isEqualTo(ErrorCode.FORBIDDEN));
        verify(service, never()).transfer(any(), any(), any(), anyString());
    }

    @Test
    @DisplayName("转科记录：缺少 clinical:visit:read 时 403")
    void listRequiresReadPermission() {
        assertThatThrownBy(() -> controller.list(visitId, "clinical:visit:treat", scope))
                .isInstanceOfSatisfying(BusinessException.class, e ->
                        assertThat(e.errorCode()).isEqualTo(ErrorCode.FORBIDDEN));
        verify(service, never()).list(any(), any());
    }

    @Test
    @DisplayName("转科记录：有权限时透传服务层结果")
    void listPassesThrough() {
        when(service.list(scope, visitId)).thenReturn(List.of());

        assertThat(controller.list(visitId, "clinical:visit:read", scope).data()).isEmpty();
        verify(service).list(scope, visitId);
    }

    @Test
    @DisplayName("转科：有 clinical:visit:treat 时透传且保留操作者")
    void transferPassesThrough() {
        VisitTransfer expected = new VisitTransfer(UUID.randomUUID(), visitId, deptId,
                UUID.randomUUID(), "需要转科", "doctor01", java.time.Instant.now(), 0L);
        when(service.transfer(eq(scope), eq(visitId), any(), eq("doctor01")))
                .thenReturn(expected);

        assertThat(controller.transfer(visitId, new TransferVisitReq(deptId, null, "需要转科"),
                "clinical:visit:treat", "doctor01", scope).data()).isEqualTo(expected);
    }
}
