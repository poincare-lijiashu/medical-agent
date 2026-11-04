package com.medassist.clinical.web;

import com.medassist.clinical.service.EmergencyService;
import com.medassist.clinical.web.EmergencyDtos.CreateTriageReq;
import com.medassist.clinical.web.EmergencyDtos.LeaveReq;
import com.medassist.clinical.web.EmergencyDtos.ObserveReq;
import com.medassist.clinical.web.EmergencyDtos.OutcomeReq;
import com.medassist.clinical.web.EmergencyDtos.RegisterTriageReq;
import com.medassist.clinical.web.EmergencyDtos.TriageView;
import com.medassist.common.api.ErrorCode;
import com.medassist.common.exception.BusinessException;
import com.medassist.common.security.DataScope;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 急诊接口权限矩阵：查看 / 分诊录入 / 挂号 / 留观办理四权分立。
 */
class EmergencyControllerPermissionTest {

    private EmergencyService service;
    private EmergencyController controller;

    private final UUID triageId = UUID.randomUUID();
    private final UUID observationId = UUID.randomUUID();
    private final DataScope fullScope = DataScope.all("admin01", true);

    @BeforeEach
    void setUp() {
        service = mock(EmergencyService.class);
        controller = new EmergencyController(service);
    }

    private void assertForbidden(Runnable call) {
        assertThatThrownBy(call::run)
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.FORBIDDEN);
    }

    @Test
    @DisplayName("查询：read 放行，其他急诊权限不能替代")
    void readRequiresRead() {
        assertForbidden(() -> controller.board(null, null, null, "emergency:triage:create"));
        controller.board(null, null, null, "emergency:triage:read");
        verify(service).board(null, null, null);

        assertForbidden(() -> controller.triages(null, null, null, null,
                "emergency:observation:manage", fullScope));
        when(service.listTriages(null, null, null, null)).thenReturn(List.of());
        controller.triages(null, null, null, null, "emergency:triage:read", fullScope);
        verify(service).listTriages(null, null, null, null);

        assertForbidden(() -> controller.beds(null, null));
        controller.beds(null, "emergency:triage:read");
        verify(service).listBeds(null);

        when(service.listObservations(null, null, null, null)).thenReturn(List.of());
        assertForbidden(() -> controller.observations(null, null, null, null,
                "emergency:triage:register", fullScope));
        controller.observations(null, null, null, null, "emergency:triage:read", fullScope);
        verify(service).listObservations(null, null, null, null);
    }

    @Test
    @DisplayName("分诊录入：必须 emergency:triage:create")
    void createRequiresCreate() {
        CreateTriageReq req = new CreateTriageReq(UUID.randomUUID(), UUID.randomUUID(), 2,
                "胸痛", new BigDecimal("36.8"), 88, 20, 120, 80, 98, null, null);
        assertForbidden(() -> controller.createTriage(req, "emergency:triage:register",
                "nurse01", fullScope));
        verify(service, never()).createTriage(any(), any());

        controller.createTriage(req, "emergency:triage:create", "nurse01", fullScope);
        verify(service).createTriage(req, "nurse01");
    }

    @Test
    @DisplayName("挂号：必须 emergency:triage:register")
    void registerRequiresRegister() {
        RegisterTriageReq req = new RegisterTriageReq("doctor01");
        assertForbidden(() -> controller.register(triageId, req, "emergency:triage:create",
                "registrar01", fullScope));
        verify(service, never()).register(any(), any(), any());

        controller.register(triageId, req, "emergency:triage:register", "registrar01", fullScope);
        verify(service).register(triageId, req, "registrar01");
    }

    @Test
    @DisplayName("离院：分诊录入权限即可，未挂号离院不走留观权限")
    void leaveRequiresCreate() {
        LeaveReq req = new LeaveReq("自行离院");
        assertForbidden(() -> controller.leave(triageId, req, "emergency:observation:manage",
                "nurse01", fullScope));
        controller.leave(triageId, req, "emergency:triage:create", "nurse01", fullScope);
        verify(service).leave(triageId, "自行离院", "nurse01");
    }

    @Test
    @DisplayName("留观：入床与转归都必须 emergency:observation:manage")
    void observationRequiresManage() {
        assertForbidden(() -> controller.observe(triageId, new ObserveReq(null),
                "emergency:triage:register", "nurse01", fullScope));
        controller.observe(triageId, new ObserveReq(null), "emergency:observation:manage",
                "nurse01", fullScope);
        verify(service).observe(eq(triageId), any(), eq("nurse01"));

        OutcomeReq req = new OutcomeReq("DISCHARGED", null);
        assertForbidden(() -> controller.outcome(observationId, req, "emergency:triage:read",
                "doctor01", fullScope));
        controller.outcome(observationId, req, "emergency:observation:manage", "doctor01",
                fullScope);
        verify(service).completeObservation(observationId, req, "doctor01");
    }

    @Test
    @DisplayName("PHI：无 phi:read 时分诊患者号与姓名在服务端掩码")
    void masksPhiWithoutReadPermission() {
        TriageView view = new TriageView(triageId, "ET20261104000001", UUID.randomUUID(),
                "MR0001", "张三", UUID.randomUUID(), "急诊科", 2, "胸痛",
                new BigDecimal("36.8"), 88, 20, 120, 80, 98, true,
                "WAITING", null, null, "nurse01", Instant.now(), Instant.now(), null, 0,
                Instant.now());
        when(service.createTriage(any(), eq("nurse01"))).thenReturn(view);

        CreateTriageReq req = new CreateTriageReq(UUID.randomUUID(), UUID.randomUUID(), 2,
                "胸痛", null, null, null, null, null, null, null, null);
        var masked = controller.createTriage(req, "emergency:triage:create", "nurse01",
                DataScope.all("nurse01", false)).data();

        assertThat(masked.patientNo()).isNotEqualTo("MR0001");
        assertThat(masked.patientName()).isNotEqualTo("张三");
    }
}
