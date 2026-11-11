package com.medassist.ops.web;

import com.medassist.common.api.ErrorCode;
import com.medassist.common.exception.BusinessException;
import com.medassist.ops.repo.OpsRepository;
import com.medassist.ops.service.OpsDashboardService;
import com.medassist.ops.service.SlaService;
import com.medassist.ops.service.TicketService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 运营权限矩阵：建单、处理、SLA 管理、审计必须分离。
 *
 * <p>SLA 是对外承诺；能让一线改 SLA，等于让"承诺"随时可被自己放宽。
 */
class OpsControllerPermissionTest {

    private SlaService slaService;
    private TicketService ticketService;
    private OpsDashboardService dashboardService;
    private OpsRepository repo;
    private OpsController controller;

    @BeforeEach
    void setUp() {
        slaService = mock(SlaService.class);
        ticketService = mock(TicketService.class);
        dashboardService = mock(OpsDashboardService.class);
        repo = mock(OpsRepository.class);
        controller = new OpsController(slaService, ticketService, dashboardService, repo);
    }

    private void assertForbidden(Runnable call) {
        assertThatThrownBy(call::run)
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.FORBIDDEN);
    }

    @Test
    @DisplayName("查询与看板：必须 ops:read")
    void readPermissions() {
        when(slaService.listServices(true)).thenReturn(List.of());
        assertForbidden(() -> controller.services(true, "ops:ticket:write"));
        assertThat(controller.services(true, "ops:read").data()).isEmpty();

        assertForbidden(() -> controller.dashboard(null, "ops:ticket:create"));
        when(dashboardService.dashboard()).thenReturn(Map.of("open", 3));
        assertThat(controller.dashboard(null, "ops:read").data()).containsEntry("open", 3);
    }

    @Test
    @DisplayName("建单：必须 ops:ticket:create，处理权限不能替代")
    void createPermission() {
        OpsDtos.TicketCreateReq req = new OpsDtos.TicketCreateReq(
                "t", null, "platform-clinical", "P2", null, null, null);
        assertForbidden(() -> controller.createTicket(req, "ops:ticket:write", "u", null, null));
    }

    @Test
    @DisplayName("处理工单：必须 ops:ticket:write")
    void writePermission() {
        assertForbidden(() -> controller.accept(UUID.randomUUID(), "ops:read", "u", null, null));
    }

    @Test
    @DisplayName("SLA 管理：必须 ops:sla:manage，一线处理权限不能改承诺")
    void slaManagePermission() {
        OpsDtos.SlaPolicyReq req = new OpsDtos.SlaPolicyReq("platform-clinical", "P2",
                30, 480, 60, false, null, "ACTIVE");
        assertForbidden(() -> controller.upsertPolicy(req, "ops:ticket:write", "u", null, null));
    }

    @Test
    @DisplayName("审计：必须 ops:audit:read")
    void auditPermission() {
        assertForbidden(() -> controller.audit(null, "ops:read"));
    }
}
