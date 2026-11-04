package com.medassist.emr.web;

import com.medassist.common.api.ErrorCode;
import com.medassist.common.exception.BusinessException;
import com.medassist.emr.domain.EmrModel.CaSignature;
import com.medassist.emr.service.CaSignatureService;
import com.medassist.emr.web.CaDtos.RevokeReq;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * CA 台账权限：查看 / 验签 / 撤销分权。
 */
class CaControllerPermissionTest {

    private CaSignatureService service;
    private CaController controller;

    private final UUID id = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = mock(CaSignatureService.class);
        controller = new CaController(service);
    }

    private CaSignature ca() {
        return new CaSignature(id, UUID.randomUUID(), UUID.randomUUID(), 1, "doctor01",
                "AUTHOR", "HASH", "MOCK", "MOCK-doctor01", "HMAC-SHA256", "SIG",
                Instant.now(), CaSignature.VALID, 0, null, null, null, null, null,
                Instant.now());
    }

    private void assertForbidden(Runnable call) {
        assertThatThrownBy(call::run)
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.FORBIDDEN);
    }

    @Test
    @DisplayName("台账查看：emr:ca:read")
    void readPermissions() {
        when(service.list(null, null, null, 100)).thenReturn(List.of());
        assertForbidden(() -> controller.signatures(null, null, null, 100, "emr:ca:verify"));
        controller.signatures(null, null, null, 100, "emr:ca:read");
        verify(service).list(null, null, null, 100);

        assertForbidden(() -> controller.board("emr:ca:sign"));
        when(service.board()).thenReturn(new CaSignatureService.Board(2, 5, 1, 0, "MOCK"));
        assertThat(controller.board("emr:ca:read").data().validTotal()).isEqualTo(5);

        assertForbidden(() -> controller.mode("emr:ca:manage"));
        when(service.mode()).thenReturn("MOCK");
        assertThat(controller.mode("emr:ca:read").data().mode()).isEqualTo("MOCK");
    }

    @Test
    @DisplayName("验签：emr:ca:verify")
    void verifyPermission() {
        assertForbidden(() -> controller.verify(id, "emr:ca:read", "qc01"));
        verify(service, never()).verify(any(), any());
        when(service.verify(id, "qc01")).thenReturn(ca());
        controller.verify(id, "emr:ca:verify", "qc01");
        verify(service).verify(id, "qc01");
    }

    @Test
    @DisplayName("撤销：emr:ca:manage")
    void revokePermission() {
        RevokeReq req = new RevokeReq("证书吊销");
        assertForbidden(() -> controller.revoke(id, req, "emr:ca:verify", "admin01"));
        verify(service, never()).revoke(any(), any(), any());
        when(service.revoke(id, "证书吊销", "admin01")).thenReturn(ca());
        controller.revoke(id, req, "emr:ca:manage", "admin01");
        verify(service).revoke(id, "证书吊销", "admin01");
    }
}
