package com.medassist.ops.service;

import com.medassist.ops.domain.GoLiveModel.Report;
import com.medassist.ops.domain.ImplementationModel.Project;
import com.medassist.ops.domain.ImplementationModel.Readiness;
import com.medassist.ops.domain.OpsModel.DrDrill;
import com.medassist.ops.domain.SloModel.Detail;
import com.medassist.ops.domain.SloModel.Evaluation;
import com.medassist.ops.domain.SloModel.Slo;
import com.medassist.ops.repo.BackupDrRepository;
import com.medassist.ops.repo.GoLiveRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 上线指挥台：门禁聚合、fail-closed 与证据包（§302）。
 */
class GoLiveCockpitServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-13T08:00:00Z");
    private static final UUID PROJECT_ID = UUID.randomUUID();
    private static final UUID SLO_ID = UUID.randomUUID();

    private GoLiveRepository repo;
    private ImplementationService implementationService;
    private BackupDrRepository backupRepo;
    private SloService sloService;
    private GoLiveCockpitService service;

    @BeforeEach
    void setUp() {
        repo = mock(GoLiveRepository.class);
        implementationService = mock(ImplementationService.class);
        backupRepo = mock(BackupDrRepository.class);
        sloService = mock(SloService.class);
        service = new GoLiveCockpitService(repo, implementationService, backupRepo, sloService,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    @DisplayName("全部通过：无阻断项，证据包含演练与项目编号")
    void allGreen() {
        when(implementationService.detail(PROJECT_ID)).thenReturn(project(true, 96));
        when(backupRepo.findLatestPassed(eq("ALL"), eq("PROD"), any()))
                .thenReturn(Optional.of(drill()));
        when(sloService.list(true)).thenReturn(List.of(detail("HEALTHY")));
        when(repo.openMajorIncidents()).thenReturn(List.of());
        when(repo.openQualityIssues()).thenReturn(List.of());
        when(repo.activeFreezes(any(), eq("ALL"))).thenReturn(List.of());
        when(repo.openP1Tickets()).thenReturn(List.of());

        Report report = service.build(PROJECT_ID, null, "PROD");

        assertThat(report.ready()).isTrue();
        assertThat(report.blockers()).isEmpty();
        assertThat(report.factors()).hasSize(7);
        assertThat(report.evidence().get("projectNo")).isEqualTo("IMP2026111300001");
        assertThat(report.evidence().get("drillNo")).isEqualTo("DR202611130001");
    }

    @Test
    @DisplayName("多门禁失败：演练缺失、SLO 违约、事件、质量问题、冻结均阻断")
    void multipleBlockers() {
        when(implementationService.detail(PROJECT_ID)).thenReturn(project(false, 80));
        when(backupRepo.findLatestPassed(any(), any(), any())).thenReturn(Optional.empty());
        when(sloService.list(true)).thenReturn(List.of(detail("BREACHED")));
        when(repo.openMajorIncidents()).thenReturn(List.of(Map.of("incident_no", "INC-1")));
        when(repo.openQualityIssues()).thenReturn(List.of(
                Map.of("issue_no", "DQI-1", "severity", "BLOCKER")));
        when(repo.activeFreezes(any(), any())).thenReturn(List.of(Map.of("name", "year-end")));
        when(repo.openP1Tickets()).thenReturn(List.of());

        Report report = service.build(PROJECT_ID, "platform-gateway", "PROD");

        assertThat(report.ready()).isFalse();
        assertThat(report.blockers()).hasSizeGreaterThanOrEqualTo(5);
        assertThat(report.factors())
                .filteredOn(f -> "FAIL".equals(f.status()))
                .extracting(f -> f.code())
                .contains("IMPL", "DR", "SLO", "INCIDENT", "QUALITY", "FREEZE");
    }

    @Test
    @DisplayName("数据源异常：按不可放行处理（fail-closed）")
    void sourceFailureIsFailClosed() {
        when(implementationService.detail(PROJECT_ID)).thenThrow(new IllegalStateException("db down"));
        when(backupRepo.findLatestPassed(any(), any(), any())).thenThrow(new IllegalStateException("db down"));
        when(sloService.list(true)).thenThrow(new IllegalStateException("db down"));
        when(repo.openMajorIncidents()).thenThrow(new IllegalStateException("db down"));
        when(repo.openQualityIssues()).thenThrow(new IllegalStateException("db down"));
        when(repo.activeFreezes(any(), any())).thenThrow(new IllegalStateException("db down"));
        when(repo.openP1Tickets()).thenThrow(new IllegalStateException("db down"));

        Report report = service.build(PROJECT_ID, "platform-gateway", "PROD");

        assertThat(report.ready()).isFalse();
        assertThat(report.blockers()).hasSizeGreaterThanOrEqualTo(6);
    }

    @Test
    @DisplayName("未选择项目：实施就绪 SKIP 且不得判 READY（门禁未参与≠通过）")
    void noProjectIsSkip() {
        when(backupRepo.findLatestPassed(any(), any(), any())).thenReturn(Optional.of(drill()));
        when(sloService.list(true)).thenReturn(List.of());
        when(repo.openMajorIncidents()).thenReturn(List.of());
        when(repo.openQualityIssues()).thenReturn(List.of());
        when(repo.activeFreezes(any(), any())).thenReturn(List.of());
        when(repo.openP1Tickets()).thenReturn(List.of());

        Report report = service.build(null, null, "PROD");

        assertThat(report.ready()).isFalse();
        assertThat(report.project()).isNull();
        assertThat(report.warnings()).anyMatch(w -> w.contains("未选择实施项目"));
        // IMPL 与 SLO 都未参与判定，必须作为缺口显式阻断，避免绿标误导评审
        assertThat(String.valueOf(report.evidence().get("gaps")))
                .contains("IMPL").contains("SLO");
        assertThat(report.blockers()).anyMatch(b -> b.contains("未参与判定"));
    }

    // ------------------------------------------------------------------ 构造

    private static Project project(boolean ready, int score) {
        Readiness readiness = new Readiness("IMP2026111300001", ready, score, 90, 95, 100,
                ready ? List.of() : List.of("1 项目章程"), List.of(), List.of(), List.of(), 0,
                List.of(), NOW);
        return new Project(PROJECT_ID, "IMP2026111300001", UUID.randomUUID(), "STD", "标准模板",
                "tenant-001", "示范医院", "IN_PROGRESS", "ops01", LocalDate.now(),
                LocalDate.now().plusDays(30), null, null, null, null, null, List.of(), 0, NOW, NOW,
                List.of(), readiness);
    }

    private static DrDrill drill() {
        return new DrDrill(UUID.randomUUID(), "DR202611130001", "季度灾备演练", "ALL", "PROD",
                "PASSED", null, 120, 30, 15, 5, 5, true, "ops01", "ops02", NOW, "证据", null,
                NOW.minusSeconds(3600), NOW, 0, NOW, NOW, List.of());
    }

    private static Detail detail(String status) {
        Slo slo = new Slo(SLO_ID, "SLO-GATEWAY", "网关可用性", "platform-gateway",
                "AVAILABILITY", new BigDecimal("99.9"), 30, null, "ops01", null, "ACTIVE",
                0, "ops01", NOW, NOW);
        Evaluation evaluation = new Evaluation(status, 1000, 999, 1, new BigDecimal("99.9"),
                new BigDecimal("1.00"), BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ONE,
                NOW.minusSeconds(86400), NOW, 1);
        return new Detail(slo, evaluation, List.of());
    }
}
