package com.medassist.ops.service;

import com.medassist.common.api.ErrorCode;
import com.medassist.common.exception.BusinessException;
import com.medassist.ops.config.OpsProperties;
import com.medassist.ops.domain.OpsModel;
import com.medassist.ops.domain.OpsModel.ServiceCatalog;
import com.medassist.ops.domain.OpsModel.SlaPolicy;
import com.medassist.ops.domain.OpsModel.Ticket;
import com.medassist.ops.domain.OpsModel.TicketEvent;
import com.medassist.ops.repo.OpsRepository;
import com.medassist.ops.web.OpsDtos;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 工单状态机与 SLA 行为：首响、挂起顺延、解决补记违约、重开重算。
 */
class TicketServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-06T02:00:00Z"); // 北京时间 10:00
    private static final String SERVICE = "platform-clinical";
    private static final UUID TICKET_ID = UUID.randomUUID();
    private static final UUID POLICY_ID = UUID.randomUUID();

    private OpsRepository repo;
    private TicketService service;
    private SlaPolicy policy;
    private ServiceCatalog catalog;

    @BeforeEach
    void setUp() {
        repo = mock(OpsRepository.class);
        SlaService slaService = new SlaService(repo);
        service = new TicketService(repo, slaService, new OpsProperties(),
                Clock.fixed(NOW, ZoneOffset.UTC));

        policy = new SlaPolicy(POLICY_ID, SERVICE, "P2", 30, 480, 60,
                false, null, "ACTIVE", 0);
        catalog = new ServiceCatalog(UUID.randomUUID(), SERVICE, "临床核心", "PLATFORM",
                "临床组", "CLINICAL_ONCALL", null, "ACTIVE", 40, 0);

        when(repo.findService(SERVICE)).thenReturn(Optional.of(catalog));
        when(repo.findPolicy(SERVICE, "P2")).thenReturn(Optional.of(policy));
        when(repo.listPolicies(any())).thenReturn(List.of(policy));
        when(repo.listEvents(any(), anyInt())).thenReturn(List.of());
    }

    @Test
    @DisplayName("建单：报告人取操作人，按 P2 计算 30 分钟首响 / 480 分钟解决")
    void create() {
        when(repo.nextTicketNo()).thenReturn("OP20261106000001");
        AtomicReference<Ticket> saved = new AtomicReference<>();
        doAnswer(invocation -> {
            saved.set(invocation.getArgument(0));
            return null;
        }).when(repo).insertTicket(any());
        when(repo.findTicket(any())).thenAnswer(inv -> Optional.ofNullable(saved.get()));

        Ticket ticket = service.create(new OpsDtos.TicketCreateReq(
                "门诊叫号不可用", "候诊队列不刷新", SERVICE, "P2", "门诊部", "PHONE",
                List.of("门诊")), "reporter01", null, null);

        assertThat(ticket.ticketNo()).isEqualTo("OP20261106000001");
        assertThat(ticket.status()).isEqualTo(OpsModel.NEW);
        assertThat(ticket.reporter()).isEqualTo("reporter01");
        assertThat(ticket.assigneeGroup()).isEqualTo("CLINICAL_ONCALL");
        assertThat(ticket.responseDueAt()).isEqualTo(NOW.plus(Duration.ofMinutes(30)));
        assertThat(ticket.resolveDueAt()).isEqualTo(NOW.plus(Duration.ofMinutes(480)));

        ArgumentCaptor<TicketEvent> event = ArgumentCaptor.forClass(TicketEvent.class);
        verify(repo).insertEvent(event.capture());
        assertThat(event.getValue().action()).isEqualTo("CREATE");
        assertThat(event.getValue().actor()).isEqualTo("reporter01");
    }

    @Test
    @DisplayName("建单必须带真实操作人")
    void createRequiresActor() {
        assertThatThrownBy(() -> service.create(new OpsDtos.TicketCreateReq(
                "t", null, SERVICE, "P2", null, null, null), " ", null, null))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.OPS_ACTOR_REQUIRED);
    }

    @Test
    @DisplayName("挂起中的工单不能派单，必须先恢复")
    void assignPendingRejected() {
        stubTicket(ticket(OpsModel.PENDING, 1, null, NOW.plusSeconds(3600),
                null, NOW.minusSeconds(600), 10, null, 0));

        assertThatThrownBy(() -> service.assign(TICKET_ID,
                new OpsDtos.AssignReq("engineer01", null), "manager01", null, null))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.OPS_TICKET_PAUSED);
        verify(repo, never()).updateFields(any(), anyLong(), anyMap());
    }

    @Test
    @DisplayName("NEW 状态不能直接解决")
    void resolveFromNewRejected() {
        stubTicket(ticket(OpsModel.NEW, 0, null, NOW.plusSeconds(1800),
                null, null, 0, null, 0));

        assertThatThrownBy(() -> service.resolve(TICKET_ID, "已处理", "engineer01", null, null))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.OPS_TICKET_TRANSITION_INVALID);
    }

    @Test
    @DisplayName("解决：写入结论与解决时间，并把首响补上")
    void resolve() {
        stubTicket(ticket(OpsModel.IN_PROGRESS, 2, null, NOW.plusSeconds(1800),
                null, null, 0, null, 0));
        when(repo.updateFields(eq(TICKET_ID), eq(2L), anyMap())).thenReturn(true);

        service.resolve(TICKET_ID, "已重启叫号服务并恢复", "engineer01", null, null);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> fields = ArgumentCaptor.forClass(Map.class);
        verify(repo).updateFields(eq(TICKET_ID), eq(2L), fields.capture());
        assertThat(fields.getValue()).containsEntry("status", OpsModel.RESOLVED)
                .containsEntry("resolution", "已重启叫号服务并恢复")
                .containsEntry("resolved_at", NOW)
                .containsEntry("first_response_at", NOW);
    }

    @Test
    @DisplayName("挂起恢复：暂停 30 分钟，首响与解决截止时间各顺延 30 分钟")
    void resumeShiftsDueTimes() {
        Instant pausedAt = NOW.minus(Duration.ofMinutes(30));
        stubTicket(ticket(OpsModel.PENDING, 3, null, NOW.plusSeconds(1800),
                pausedAt, pausedAt, 0, null, 0));
        when(repo.updateFields(eq(TICKET_ID), eq(3L), anyMap())).thenReturn(true);

        service.resume(TICKET_ID, "继续处理", "engineer01", null, null);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> fields = ArgumentCaptor.forClass(Map.class);
        verify(repo).updateFields(eq(TICKET_ID), eq(3L), fields.capture());
        assertThat(fields.getValue()).containsEntry("status", OpsModel.IN_PROGRESS)
                .containsEntry("sla_paused_total_minutes", 30)
                .containsEntry("response_due_at", NOW.plusSeconds(1800 + 1800));
        verify(repo).insertEvent(any(TicketEvent.class));
    }

    @Test
    @DisplayName("关闭：未解决不能关闭")
    void closeRequiresResolved() {
        stubTicket(ticket(OpsModel.IN_PROGRESS, 1, null, NOW.plusSeconds(1800),
                null, null, 0, null, 0));

        assertThatThrownBy(() -> service.close(TICKET_ID, 5, null, "manager01", null, null))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.OPS_TICKET_TRANSITION_INVALID);
    }

    @Test
    @DisplayName("重开：状态回 REOPENED，重开次数 +1，解决时限按新策略重算")
    void reopen() {
        stubTicket(ticket(OpsModel.RESOLVED, 4, NOW.minusSeconds(600), NOW.minusSeconds(60),
                NOW.minusSeconds(3600), null, 0, NOW.minusSeconds(60), 1));
        when(repo.updateFields(eq(TICKET_ID), eq(4L), anyMap())).thenReturn(true);

        service.reopen(TICKET_ID, "问题复现", "reporter01", null, null);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> fields = ArgumentCaptor.forClass(Map.class);
        verify(repo).updateFields(eq(TICKET_ID), eq(4L), fields.capture());
        assertThat(fields.getValue()).containsEntry("status", OpsModel.REOPENED)
                .containsEntry("reopen_count", 2)
                .containsEntry("resolve_due_at", NOW.plus(Duration.ofMinutes(480)))
                .containsEntry("sla_resolve_breached", false);
    }

    @Test
    @DisplayName("超时扫描：首响超时落事件，且只记一次")
    void scanSla() {
        UUID id = UUID.randomUUID();
        when(repo.findBreachCandidates(1000)).thenReturn(List.of(id));
        when(repo.findTicket(id)).thenReturn(Optional.of(ticket(OpsModel.IN_PROGRESS, 1, null,
                NOW.minusSeconds(60), null, null, 0, null, 0)));
        when(repo.markResponseBreached(id)).thenReturn(true);
        when(repo.markResolveBreached(id)).thenReturn(false);

        Map<String, Object> summary = service.scanSla("scheduler-test", null, null);

        assertThat(summary).containsEntry("responseBreached", 1)
                .containsEntry("resolveBreached", 0);
        ArgumentCaptor<TicketEvent> event = ArgumentCaptor.forClass(TicketEvent.class);
        verify(repo).insertEvent(event.capture());
        assertThat(event.getValue().action()).isEqualTo("SLA_RESPONSE_BREACH");
    }

    private void stubTicket(Ticket ticket) {
        when(repo.findTicket(TICKET_ID)).thenReturn(Optional.of(ticket));
    }

    private static Ticket ticket(String status, long version, Instant firstResponse,
                                 Instant responseDue, Instant pausedAt, Instant resolvedAt,
                                 int pausedTotal, Instant resolveDue, int reopenCount) {
        return new Ticket(TICKET_ID, "OP20261106000001", "门诊叫号不可用", "desc", SERVICE,
                "P2", "reporter01", "门诊部", "engineer01", "CLINICAL_ONCALL", status,
                "PORTAL", POLICY_ID, responseDue, resolveDue, firstResponse, resolvedAt,
                resolvedAt == null ? null : resolvedAt, null, reopenCount, null, pausedAt,
                pausedTotal, null, resolvedAt == null ? null : "已处理", List.of(),
                false, false, version, NOW.minusSeconds(3600), NOW, List.of(),
                "ON_TRACK", "ON_TRACK");
    }
}
