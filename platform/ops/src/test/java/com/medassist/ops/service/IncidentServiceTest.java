package com.medassist.ops.service;

import com.medassist.common.api.ErrorCode;
import com.medassist.common.exception.BusinessException;
import com.medassist.ops.config.OpsProperties;
import com.medassist.ops.domain.OpsModel.Incident;
import com.medassist.ops.domain.OpsModel.Postmortem;
import com.medassist.ops.domain.OpsModel.ServiceCatalog;
import com.medassist.ops.repo.OpsRepository;
import com.medassist.ops.web.OpsDtos;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 重大事件状态机与复盘职责分离。
 */
class IncidentServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-12T02:00:00Z");
    private static final UUID INCIDENT_ID = UUID.randomUUID();

    private OpsRepository repo;
    private SlaService slaService;
    private IncidentService service;

    @BeforeEach
    void setUp() {
        repo = mock(OpsRepository.class);
        slaService = mock(SlaService.class);
        when(slaService.requireService(any())).thenReturn(new ServiceCatalog(
                UUID.randomUUID(), "platform-gateway", "统一网关", "PLATFORM", null,
                "PLATFORM_ONCALL", null, "ACTIVE", 10, 0));
        when(repo.listIncidentEvents(any(), anyInt())).thenReturn(List.of());
        service = new IncidentService(repo, slaService, new OpsProperties(),
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    @DisplayName("建事件：默认指挥人为操作人，状态 OPEN，写 CREATE 轨迹")
    void create() {
        when(repo.nextIncidentNo()).thenReturn("INC20261112000001");
        AtomicReference<Incident> saved = new AtomicReference<>();
        doAnswer(invocation -> {
            saved.set(invocation.getArgument(0));
            return null;
        }).when(repo).insertIncident(any());
        when(repo.findIncident(any())).thenAnswer(inv -> Optional.ofNullable(saved.get()));

        Incident incident = service.create(new OpsDtos.IncidentCreateReq(
                "Gateway unavailable", "SEV1", null, List.of("platform-gateway"),
                null, "All API 502", "OPD cannot register"), "commander01", null, null);

        assertThat(incident.incidentNo()).isEqualTo("INC20261112000001");
        assertThat(incident.status()).isEqualTo("OPEN");
        assertThat(incident.commander()).isEqualTo("commander01");
        verify(repo).insertIncidentEvent(any());
        verify(repo).audit(eq("commander01"), eq("INCIDENT_CREATE"), eq("INC20261112000001"),
                anyMap(), any(), any());
    }

    @Test
    @DisplayName("状态机：OPEN 不能直接 CLOSED")
    void invalidTransition() {
        when(repo.findIncident(INCIDENT_ID)).thenReturn(Optional.of(incident("OPEN", null, null)));

        assertThatThrownBy(() -> service.changeStatus(INCIDENT_ID,
                new OpsDtos.IncidentStatusReq("CLOSED", "done"), "ops01", null, null))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.OPS_INCIDENT_TRANSITION_INVALID);
    }

    @Test
    @DisplayName("解决：写入 resolved_at，状态 RESOLVED")
    void resolve() {
        when(repo.findIncident(INCIDENT_ID)).thenReturn(Optional.of(incident("OPEN", null, null)));
        when(repo.updateIncidentFields(eq(INCIDENT_ID), eq(0L), anyMap())).thenReturn(true);

        service.changeStatus(INCIDENT_ID, new OpsDtos.IncidentStatusReq(
                "RESOLVED", "Gateway restarted"), "ops01", null, null);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> fields = ArgumentCaptor.forClass(Map.class);
        verify(repo).updateIncidentFields(eq(INCIDENT_ID), eq(0L), fields.capture());
        assertThat(fields.getValue()).containsEntry("status", "RESOLVED")
                .containsEntry("resolved_at", NOW);
    }

    @Test
    @DisplayName("复盘：只有已解决事件可创建，且重复创建被拒绝")
    void createPostmortemGuards() {
        when(repo.findIncident(INCIDENT_ID)).thenReturn(Optional.of(incident("OPEN", null, null)));
        assertThatThrownBy(() -> service.createPostmortem(INCIDENT_ID,
                new OpsDtos.PostmortemReq(List.of(), "root", List.of(), List.of()),
                "author01", null, null))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.OPS_INCIDENT_STATUS_INVALID);

        when(repo.findIncident(INCIDENT_ID)).thenReturn(Optional.of(incident("RESOLVED", NOW, null)));
        when(repo.findPostmortem(INCIDENT_ID)).thenReturn(Optional.of(
                postmortem("DRAFT", "author01", "root", List.of(Map.of("action", "x")))));
        assertThatThrownBy(() -> service.createPostmortem(INCIDENT_ID,
                new OpsDtos.PostmortemReq(List.of(), "root", List.of(), List.of()),
                "author01", null, null))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.OPS_POSTMORTEM_STATUS_INVALID);
    }

    @Test
    @DisplayName("复盘提交：缺根因或纠正措施不能提交")
    void submitRequiresRootCauseAndActions() {
        when(repo.findPostmortem(INCIDENT_ID)).thenReturn(Optional.of(
                postmortem("DRAFT", "author01", null, List.of())));

        assertThatThrownBy(() -> service.submitPostmortem(INCIDENT_ID, "author01", null, null))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.OPS_POSTMORTEM_INCOMPLETE);
    }

    @Test
    @DisplayName("复盘审批：作者不能审批自己的复盘")
    void selfReviewRejected() {
        when(repo.findPostmortem(INCIDENT_ID)).thenReturn(Optional.of(
                postmortem("REVIEWED", "author01", "root", List.of(Map.of("action", "x")))));

        assertThatThrownBy(() -> service.approvePostmortem(INCIDENT_ID,
                new OpsDtos.PostmortemApproveReq("ok"), "author01", null, null))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.OPS_POSTMORTEM_SELF_REVIEW);
    }

    @Test
    @DisplayName("复盘审批：他人审批通过，写入审批人与时间")
    void approveByOther() {
        Postmortem draft = postmortem("REVIEWED", "author01", "root",
                List.of(Map.of("action", "increase pool")));
        when(repo.findPostmortem(INCIDENT_ID)).thenReturn(Optional.of(draft));
        when(repo.findIncident(INCIDENT_ID)).thenReturn(Optional.of(incident("RESOLVED", NOW, null)));
        when(repo.updatePostmortemFields(eq(draft.id()), eq(1L), anyMap())).thenReturn(true);

        service.approvePostmortem(INCIDENT_ID, new OpsDtos.PostmortemApproveReq("approved"),
                "manager01", null, null);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> fields = ArgumentCaptor.forClass(Map.class);
        verify(repo).updatePostmortemFields(eq(draft.id()), eq(1L), fields.capture());
        assertThat(fields.getValue()).containsEntry("status", "APPROVED")
                .containsEntry("approved_by", "manager01")
                .containsEntry("approved_at", NOW);
    }

    private static Incident incident(String status, Instant resolvedAt, Instant closedAt) {
        return new Incident(INCIDENT_ID, "INC20261112000001", "Gateway unavailable", "SEV1",
                status, "commander01", NOW.minusSeconds(600), NOW.minusSeconds(600),
                resolvedAt, closedAt, List.of("platform-gateway"), null, "summary", null,
                "impact", 0, NOW.minusSeconds(600), NOW, List.of());
    }

    private static Postmortem postmortem(String status, String author, String rootCause,
                                         List<Map<String, Object>> actions) {
        return new Postmortem(UUID.randomUUID(), INCIDENT_ID, author, status,
                List.of(Map.of("ts", "2026-11-12T09:00:00+08:00", "event", "detected")),
                rootCause, List.of("no capacity headroom"), actions, null, null, 1,
                NOW.minusSeconds(60), NOW);
    }
}
