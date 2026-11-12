package com.medassist.ops.service;

import com.medassist.common.api.ErrorCode;
import com.medassist.common.exception.BusinessException;
import com.medassist.common.security.HookTokens;
import com.medassist.ops.domain.OpsIntegrationModel.AlertSource;
import com.medassist.ops.domain.OpsIntegrationModel.AlertState;
import com.medassist.ops.domain.OpsIntegrationModel.HookEvent;
import com.medassist.ops.domain.OpsIntegrationModel.SourceCreated;
import com.medassist.ops.domain.OpsModel;
import com.medassist.ops.domain.OpsModel.ServiceCatalog;
import com.medassist.ops.domain.OpsModel.Ticket;
import com.medassist.ops.repo.AlertIntegrationRepository;
import com.medassist.ops.repo.AlertIntegrationRepository.StoredSource;
import com.medassist.ops.repo.OpsRepository;
import com.medassist.ops.web.OpsDtos.TicketCreateReq;
import com.medassist.ops.web.OpsIntegrationDtos.AlertItem;
import com.medassist.ops.web.OpsIntegrationDtos.AlertSourceReq;
import com.medassist.ops.web.OpsIntegrationDtos.AlertmanagerPayload;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 告警接入：建单、去重、自动恢复、映射与服务兜底。
 *
 * <p>这些行为直接决定值班人员会不会被"同一条告警建 20 张单"淹没，
 * 以及告警恢复后工单是否还挂在队列里。
 */
class AlertIngestServiceTest {

    private static final String SOURCE = "prom-prod";
    private static final String TOKEN = "oa_test_token";
    private static final UUID TICKET_ID = UUID.randomUUID();

    private AlertIntegrationRepository repo;
    private OpsRepository opsRepo;
    private TicketService ticketService;
    private AlertIngestService service;
    private AlertSource source;

    @BeforeEach
    void setUp() {
        repo = mock(AlertIntegrationRepository.class);
        opsRepo = mock(OpsRepository.class);
        ticketService = mock(TicketService.class);
        service = new AlertIngestService(repo, opsRepo, ticketService);
        source = new AlertSource(UUID.randomUUID(), SOURCE, "Prometheus 生产", "ALERTMANAGER",
                "oa_test", null,
                Map.of("critical", "P1", "warning", "P2", "info", "IGNORE"),
                true, 60, "ACTIVE", null, "admin", 0, Instant.now(), Instant.now());
        when(repo.findSource(SOURCE))
                .thenReturn(Optional.of(new StoredSource(source, HookTokens.hash(TOKEN))));
        when(opsRepo.findService("platform-clinical"))
                .thenReturn(Optional.of(service("platform-clinical")));
        when(opsRepo.findService("infra-monitoring"))
                .thenReturn(Optional.of(service("infra-monitoring")));
    }

    @Test
    @DisplayName("firing：按 severity 建 P1 工单，并记录 fingerprint 状态")
    void firingCreatesTicket() {
        when(repo.findState(SOURCE, "fp-1")).thenReturn(Optional.empty());
        when(ticketService.create(any(), anyString(), any(), any())).thenReturn(ticket("NEW"));

        Map<String, Object> summary = ingest(alert("firing", "critical", "fp-1", "platform-clinical"));

        assertThat(summary.get("created")).isEqualTo(1);
        ArgumentCaptor<TicketCreateReq> req = ArgumentCaptor.forClass(TicketCreateReq.class);
        verify(ticketService).create(req.capture(), eq("integration:" + SOURCE), any(), any());
        assertThat(req.getValue().priority()).isEqualTo("P1");
        assertThat(req.getValue().source()).isEqualTo("MONITOR");
        assertThat(req.getValue().reporterDept()).isEqualTo("监控平台");
        verify(repo).upsertState(any(AlertState.class));
        verify(repo).insertEvent(argThatAction("TICKET_CREATED"), isNull());
    }

    @Test
    @DisplayName("重复 firing：节流窗口内不建单、不刷屏")
    void repeatedFiringIsDeduped() {
        AlertState firing = new AlertState(SOURCE, "fp-1", "FIRING", "HighErrorRate", "critical",
                "P1", "platform-clinical", "[CRITICAL] HighErrorRate", TICKET_ID,
                Instant.now().minusSeconds(300), Instant.now(), null, Map.of(), Map.of(), 1);
        when(repo.findState(SOURCE, "fp-1")).thenReturn(Optional.of(firing));

        Map<String, Object> summary = ingest(alert("firing", "critical", "fp-1", "platform-clinical"));

        assertThat(summary.get("deduped")).isEqualTo(1);
        verify(ticketService, never()).create(any(), any(), any(), any());
        verify(ticketService, never()).comment(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("resolved：处理中的工单自动解决")
    void resolvedClosesInProgressTicket() {
        stateFiring();
        when(ticketService.detail(TICKET_ID, 1)).thenReturn(ticket(OpsModel.IN_PROGRESS));

        Map<String, Object> summary = ingest(alert("resolved", "critical", "fp-1", "platform-clinical"));

        assertThat(summary.get("resolved")).isEqualTo(1);
        verify(ticketService).resolve(eq(TICKET_ID), anyString(), eq("integration:" + SOURCE),
                any(), any());
        verify(repo).insertEvent(argThatAction("TICKET_RESOLVED"), isNull());
    }

    @Test
    @DisplayName("resolved：人工接单前已恢复的工单自动取消，不占用队列")
    void resolvedCancelsNewTicket() {
        stateFiring();
        when(ticketService.detail(TICKET_ID, 1)).thenReturn(ticket(OpsModel.NEW));

        ingest(alert("resolved", "critical", "fp-1", "platform-clinical"));

        verify(ticketService).cancel(eq(TICKET_ID), anyString(), eq("integration:" + SOURCE),
                any(), any());
        verify(ticketService, never()).resolve(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("IGNORE 级别：只留痕不建单")
    void ignoredSeverityDoesNotCreateTicket() {
        when(repo.findState(SOURCE, "fp-2")).thenReturn(Optional.empty());

        Map<String, Object> summary = ingest(alert("firing", "info", "fp-2", "platform-clinical"));

        assertThat(summary.get("ignored")).isEqualTo(1);
        verify(ticketService, never()).create(any(), any(), any(), any());
        verify(repo).insertEvent(argThatAction("IGNORED"), isNull());
    }

    @Test
    @DisplayName("未知服务：回退到 infra-monitoring，避免告警因标签不规范丢失")
    void unknownServiceFallsBack() {
        when(repo.findState(SOURCE, "fp-3")).thenReturn(Optional.empty());
        when(opsRepo.findService("unknown-svc")).thenReturn(Optional.empty());
        when(ticketService.create(any(), anyString(), any(), any())).thenReturn(ticket("NEW"));

        ingest(alert("firing", "critical", "fp-3", "unknown-svc"));

        ArgumentCaptor<TicketCreateReq> req = ArgumentCaptor.forClass(TicketCreateReq.class);
        verify(ticketService).create(req.capture(), anyString(), any(), any());
        assertThat(req.getValue().serviceCode()).isEqualTo("infra-monitoring");
    }

    @Test
    @DisplayName("令牌错误/源停用：一律 401，不暴露接入源是否存在")
    void badTokenRejected() {
        assertThatThrownBy(() -> service.ingest(SOURCE, "Bearer wrong",
                payload(alert("firing", "critical", "fp-1", "platform-clinical")), null, null))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.OPS_INTEGRATION_TOKEN_INVALID);

        when(repo.findSource("missing")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.ingest("missing", "Bearer " + TOKEN,
                payload(alert("firing", "critical", "fp-1", "platform-clinical")), null, null))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.OPS_INTEGRATION_TOKEN_INVALID);
    }

    @Test
    @DisplayName("创建接入源：明文只返回一次，库内只存哈希")
    void createSourceStoresHashOnly() {
        when(repo.findSource("new-src")).thenReturn(Optional.empty());

        SourceCreated created = service.createSource(new AlertSourceReq("new-src", "新告警源",
                "ALERTMANAGER", null, null, null, null), "admin", null, null);

        assertThat(created.token()).startsWith("oa_");
        ArgumentCaptor<String> hash = ArgumentCaptor.forClass(String.class);
        verify(repo).insertSource(any(AlertSource.class), hash.capture());
        assertThat(hash.getValue()).isNotEqualTo(created.token());
        assertThat(HookTokens.matches(created.token(), hash.getValue())).isTrue();
    }

    // ------------------------------------------------------------------ 辅助

    private Map<String, Object> ingest(AlertItem alert) {
        return service.ingest(SOURCE, "Bearer " + TOKEN, payload(alert), null, null);
    }

    private static AlertmanagerPayload payload(AlertItem alert) {
        return new AlertmanagerPayload("4", "firing", "g", "ops", Map.of(), Map.of(),
                List.of(alert));
    }

    private static AlertItem alert(String status, String severity, String fingerprint,
                                   String service) {
        return new AlertItem(status,
                Map.of("alertname", "HighErrorRate", "severity", severity,
                        "service", service, "instance", "api-1"),
                Map.of("summary", "5xx 比例过高"), "2026-11-12T08:00:00Z",
                "0001-01-01T00:00:00Z", "http://grafana", fingerprint);
    }

    private void stateFiring() {
        when(repo.findState(SOURCE, "fp-1")).thenReturn(Optional.of(new AlertState(
                SOURCE, "fp-1", "FIRING", "HighErrorRate", "critical", "P1",
                "platform-clinical", "title", TICKET_ID, Instant.now().minusSeconds(60),
                Instant.now().minusSeconds(30), null, Map.of(), Map.of(), 1)));
    }

    private static ServiceCatalog service(String code) {
        return new ServiceCatalog(UUID.randomUUID(), code, code, "PLATFORM", null,
                "PLATFORM_ONCALL", null, "ACTIVE", 1, 0);
    }

    private static Ticket ticket(String status) {
        return new Ticket(TICKET_ID, "OP20261112000001", "HighErrorRate", null,
                "platform-clinical", "P1", "integration:" + SOURCE, "监控平台", null,
                "PLATFORM_ONCALL", status, "MONITOR", null, null, null, null, null, null, null,
                0, null, null, 0, null, null, List.of(), false, false, 0, Instant.now(),
                Instant.now(), List.of(), "ON_TRACK", "ON_TRACK");
    }

    /** 事件插入的 trace 参数为 null，这里只按 action 匹配。 */
    private static HookEvent argThatAction(String action) {
        return org.mockito.ArgumentMatchers.argThat(e -> action.equals(e.action()));
    }
}
