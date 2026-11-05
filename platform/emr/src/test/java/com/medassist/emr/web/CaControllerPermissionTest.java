package com.medassist.emr.web;

import com.medassist.common.api.ErrorCode;
import com.medassist.common.exception.BusinessException;
import com.medassist.common.security.DataScope;
import com.medassist.emr.domain.EmrModel.CaSignature;
import com.medassist.emr.service.CaSignatureService;
import com.medassist.emr.service.EmrAccessGuard;
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
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * CA 台账权限：查看 / 验签 / 撤销分权，以及病历归属校验（G51）。
 */
class CaControllerPermissionTest {

    private CaSignatureService service;
    private EmrAccessGuard guard;
    private CaController controller;

    private final UUID id = UUID.randomUUID();
    /** 跨院区角色：允许不带 recordId 查台账。 */
    private final DataScope allScope = DataScope.all("admin01", true);
    /** 单院区角色：必须指定 recordId。 */
    private final DataScope singleCampus = DataScope.of("doctor01", java.util.Set.of(UUID.randomUUID()),
            false, java.util.Set.of(DataScope.DEFAULT_CAMPUS_ID), false, true);

    @BeforeEach
    void setUp() {
        service = mock(CaSignatureService.class);
        guard = mock(EmrAccessGuard.class);
        controller = new CaController(service, guard);
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
        assertForbidden(() -> controller.signatures(null, null, null, 100, "emr:ca:verify", allScope));
        controller.signatures(null, null, null, 100, "emr:ca:read", allScope);
        verify(service).list(null, null, null, 100);

        assertForbidden(() -> controller.board("emr:ca:sign"));
        when(service.board()).thenReturn(new CaSignatureService.Board(2, 5, 1, 0, "MOCK"));
        assertThat(controller.board("emr:ca:read").data().validTotal()).isEqualTo(5);

        assertForbidden(() -> controller.mode("emr:ca:manage"));
        when(service.mode()).thenReturn("MOCK");
        assertThat(controller.mode("emr:ca:read").data().mode()).isEqualTo("MOCK");
    }

    @Test
    @DisplayName("G51：单院区账号不指定病历不能查整院台账")
    void campusScopedListRequiresRecordId() {
        assertForbidden(() -> controller.signatures(null, null, null, 100, "emr:ca:read", singleCampus));
        verify(service, never()).list(any(), any(), any(), anyInt());
    }

    @Test
    @DisplayName("G51：按病历查台账走归属校验，越权时不返回数据")
    void listByRecordChecksOwnership() {
        UUID recordId = UUID.randomUUID();
        doThrow(new BusinessException(ErrorCode.NOT_FOUND)).when(guard)
                .requireRecord(singleCampus, recordId);

        assertThatThrownBy(() ->
                controller.signatures(recordId, null, null, 100, "emr:ca:read", singleCampus))
                .isInstanceOf(BusinessException.class);
        verify(service, never()).list(any(), any(), any(), anyInt());
    }

    @Test
    @DisplayName("验签：emr:ca:verify")
    void verifyPermission() {
        assertForbidden(() -> controller.verify(id, "emr:ca:read", "qc01", allScope));
        verify(service, never()).verify(any(), any());
        when(service.verify(id, "qc01")).thenReturn(ca());
        controller.verify(id, "emr:ca:verify", "qc01", allScope);
        verify(service).verify(id, "qc01");
    }

    @Test
    @DisplayName("撤销：emr:ca:manage")
    void revokePermission() {
        RevokeReq req = new RevokeReq("证书吊销");
        assertForbidden(() -> controller.revoke(id, req, "emr:ca:verify", "admin01", allScope));
        verify(service, never()).revoke(any(), any(), any());
        when(service.revoke(id, "证书吊销", "admin01")).thenReturn(ca());
        controller.revoke(id, req, "emr:ca:manage", "admin01", allScope);
        verify(service).revoke(id, "证书吊销", "admin01");
    }
}
