package com.medassist.clinical.web;

import com.medassist.clinical.domain.ClinicalModel.PacuBoardRow;
import com.medassist.clinical.domain.ClinicalModel.PacuStay;
import com.medassist.clinical.service.PacuService;
import com.medassist.clinical.service.VisitAccessGuard;
import com.medassist.clinical.web.PacuDtos.AdmitReq;
import com.medassist.clinical.web.PacuDtos.DischargeReq;
import com.medassist.clinical.web.PacuDtos.ObservationReq;
import com.medassist.clinical.web.PacuDtos.PacuView;
import com.medassist.clinical.web.PacuDtos.SignReq;
import com.medassist.common.api.ErrorCode;
import com.medassist.common.exception.BusinessException;
import com.medassist.common.security.DataScope;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 复苏接口权限矩阵：读用 clinical:surgery:read，写用 clinical:surgery:write。 */
class PacuControllerPermissionTest {

    private PacuService service;
    private VisitAccessGuard guard;
    private PacuController controller;

    private final UUID caseId = UUID.randomUUID();
    private final DataScope scope = DataScope.all("admin01", true);
    private final PacuView view = new PacuView(new PacuStay(UUID.randomUUID(), caseId, null,
            UUID.randomUUID(), UUID.randomUUID(), Instant.now(), null, null,
            PacuStay.IN_PACU, null, null, "nurse01", 0L, Instant.now()), List.of());

    @BeforeEach
    void setUp() {
        service = mock(PacuService.class);
        guard = mock(VisitAccessGuard.class);
        controller = new PacuController(service, guard);
    }

    private void assertForbidden(Runnable call) {
        assertThatThrownBy(call::run)
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.FORBIDDEN);
    }

    @Test
    @DisplayName("读取复苏记录：必须 clinical:surgery:read，且先过病例守卫")
    void getRequiresRead() {
        when(service.get(caseId)).thenReturn(view);

        assertForbidden(() -> controller.get(caseId, "clinical:surgery:write", scope));
        controller.get(caseId, "clinical:surgery:read", scope);

        verify(guard).requireCase(scope, caseId);
        verify(service).get(caseId);
    }

    @Test
    @DisplayName("入室/观察/出室/签名：必须 clinical:surgery:write")
    void writesRequireWritePermission() {
        assertForbidden(() -> controller.admit(caseId, new AdmitReq(null, null),
                "clinical:surgery:read", "nurse01", scope));
        assertForbidden(() -> controller.addObservation(caseId,
                new ObservationReq(Instant.now(), 2, 2, 2, 2, 2, null, null, null, null, null,
                        null), "clinical:surgery:read", "nurse01", scope));
        assertForbidden(() -> controller.discharge(caseId, new DischargeReq(0L, null, "WARD"),
                "clinical:surgery:read", "nurse01", scope));
        assertForbidden(() -> controller.sign(caseId, new SignReq(1L),
                "clinical:surgery:read", "doctor02", scope));

        verify(service, never()).admit(any(), any(), any());
        verify(service, never()).addObservation(any(), any(), any());
        verify(service, never()).discharge(any(), any(), any());
        verify(service, never()).sign(any(), anyLong(), any());
    }

    @Test
    @DisplayName("看板：只读权限即可，且不按病例守卫")
    void boardRequiresReadOnly() {
        when(service.board(eq("IN_PACU"), any(), eq(50), any())).thenReturn(List.<PacuBoardRow>of());

        assertForbidden(() -> controller.board("IN_PACU", null, 50,
                "clinical:surgery:write", scope));
        controller.board("IN_PACU", null, 50, "clinical:surgery:read", scope);

        verify(service).board("IN_PACU", null, 50, scope);
        verify(guard, never()).requireCase(any(), any());
    }
}
