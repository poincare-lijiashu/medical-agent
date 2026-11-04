package com.medassist.emr.web;

import com.medassist.common.api.ErrorCode;
import com.medassist.common.exception.BusinessException;
import com.medassist.common.security.DataScope;
import com.medassist.emr.service.ConsentAccessGuard;
import com.medassist.emr.service.ConsentService;
import com.medassist.emr.web.ConsentDtos.ConsentView;
import com.medassist.emr.web.ConsentDtos.CreateConsentReq;
import com.medassist.emr.web.ConsentDtos.CreateTemplateReq;
import com.medassist.emr.web.ConsentDtos.RevokeReq;
import com.medassist.emr.web.ConsentDtos.SignReq;
import com.medassist.emr.web.ConsentDtos.TemplateView;
import com.medassist.emr.web.ConsentDtos.VersionReq;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 知情同意书权限矩阵：read / write / sign / revoke / manage 分权。
 */
class ConsentControllerPermissionTest {

    private ConsentService service;
    private ConsentAccessGuard guard;
    private ConsentController controller;
    private final UUID id = UUID.randomUUID();
    private final DataScope scope = DataScope.all("admin01", true);

    @BeforeEach
    void setUp() {
        service = mock(ConsentService.class);
        guard = mock(ConsentAccessGuard.class);
        controller = new ConsentController(service, guard);
    }

    private void assertForbidden(Runnable call) {
        assertThatThrownBy(call::run)
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.FORBIDDEN);
    }

    @Test
    @DisplayName("模板：查看 read，维护 manage")
    void templatePermissions() {
        when(service.templates(null, 100)).thenReturn(List.of());
        controller.templates(null, 100, "emr:consent:read");
        assertForbidden(() -> controller.templates(null, 100, "emr:consent:write"));

        CreateTemplateReq req = new CreateTemplateReq("T1", "模板", "SURGERY", "内容", null);
        assertForbidden(() -> controller.createTemplate(req, "emr:consent:write", "doctor01"));
        when(service.createTemplate(any(), anyString())).thenReturn(templateView());
        assertThat(controller.createTemplate(req, "emr:consent:manage", "admin01")
                .data().templateCode()).isEqualTo("T1");

        assertForbidden(() -> controller.publishTemplate(id, new VersionReq(0L),
                "emr:consent:read", "admin01"));
        when(service.publishTemplate(id, 0L, "admin01")).thenReturn(templateView());
        controller.publishTemplate(id, new VersionReq(0L), "emr:consent:manage", "admin01");

        assertForbidden(() -> controller.disableTemplate(id, new VersionReq(0L),
                "emr:consent:read", "admin01"));
        when(service.disableTemplate(id, 0L, "admin01")).thenReturn(templateView());
        controller.disableTemplate(id, new VersionReq(0L), "emr:consent:manage", "admin01");
    }

    @Test
    @DisplayName("同意书：创建 write，签署 sign，撤销 revoke，查看 read")
    void consentPermissions() {
        CreateConsentReq req = new CreateConsentReq(UUID.randomUUID(), UUID.randomUUID(),
                "CONSENT-SURGERY", "阑尾切除术");
        when(service.create(any(), anyString(), any())).thenReturn(consentView());
        assertForbidden(() -> controller.create(req, "emr:consent:read", "doctor01", scope));
        controller.create(req, "emr:consent:write", "doctor01", scope);

        SignReq sign = new SignReq("李四", "配偶", null, "doctor01", false, 0L);
        when(service.sign(eq(id), any(), anyString())).thenReturn(consentView());
        assertForbidden(() -> controller.sign(id, sign, "emr:consent:write", "doctor01", scope));
        controller.sign(id, sign, "emr:consent:sign", "doctor01", scope);

        RevokeReq revoke = new RevokeReq("患者反悔", 1L);
        when(service.revoke(eq(id), any(), anyString())).thenReturn(consentView());
        assertForbidden(() -> controller.revoke(id, revoke, "emr:consent:sign", "doctor01", scope));
        controller.revoke(id, revoke, "emr:consent:revoke", "admin01", scope);

        when(service.detail(id)).thenReturn(consentView());
        assertForbidden(() -> controller.detail(id, "emr:consent:write", scope));
        controller.detail(id, "emr:consent:read", scope);

        when(service.board()).thenReturn(new com.medassist.emr.repo.ConsentRepository.ConsentBoard(
                2, 3, 1));
        assertForbidden(() -> controller.board("emr:consent:write"));
        assertThat(controller.board("emr:consent:read").data().signedToday()).isEqualTo(3);
    }

    private TemplateView templateView() {
        Instant now = Instant.now();
        return new TemplateView(id, "T1", "模板", "SURGERY", "内容", 1, "DRAFT",
                "admin01", now, now, 0L);
    }

    private ConsentView consentView() {
        Instant now = Instant.now();
        return new ConsentView(id, "IC1", UUID.randomUUID(), "CONSENT-SURGERY", 1, "手术",
                UUID.randomUUID(), "P1", "张三", UUID.randomUUID(), "阑尾切除术",
                "正文", "hash", "DRAFT", null, null, null, "doctor01", null, null,
                null, null, null, null, null, null, "doctor01", now, now, 0L);
    }
}
