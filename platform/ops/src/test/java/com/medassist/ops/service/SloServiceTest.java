package com.medassist.ops.service;

import com.medassist.common.api.ErrorCode;
import com.medassist.common.exception.BusinessException;
import com.medassist.ops.domain.SloModel.Dashboard;
import com.medassist.ops.domain.SloModel.Evaluation;
import com.medassist.ops.domain.SloModel.Slo;
import com.medassist.ops.domain.SloModel.Snapshot;
import com.medassist.ops.repo.OpsRepository;
import com.medassist.ops.repo.SloRepository;
import com.medassist.ops.web.OpsDtos;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
 * SLO：达标率/错误预算/燃烧率、快照幂等与状态判定（§300）。
 */
class SloServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-13T06:00:00Z");
    private static final UUID SLO_ID = UUID.randomUUID();

    private SloRepository repo;
    private SloService service;

    @BeforeEach
    void setUp() {
        repo = mock(SloRepository.class);
        SlaService slaService = mock(SlaService.class);
        service = new SloService(repo, mock(OpsRepository.class), slaService,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    @DisplayName("评估：全部成功时错误预算剩余 100%")
    void evaluateHealthy() {
        Evaluation e = evaluate(slo("AVAILABILITY", "99.9"), 10000, 10000);

        assertThat(e.status()).isEqualTo("HEALTHY");
        assertThat(e.ratioPercent()).isEqualByComparingTo("100.0000");
        assertThat(e.errorBudgetEvents()).isEqualByComparingTo("10.00");
        assertThat(e.budgetRemaining()).isEqualByComparingTo("10.00");
        assertThat(e.burnRate()).isEqualByComparingTo("0.0000");
    }

    @Test
    @DisplayName("评估：预算剩余不足 25% 判 AT_RISK")
    void evaluateAtRisk() {
        Evaluation e = evaluate(slo("AVAILABILITY", "99.9"), 10000, 9991);

        assertThat(e.status()).isEqualTo("AT_RISK");
        assertThat(e.budgetRemaining()).isEqualByComparingTo("1.00");
        assertThat(e.budgetRemainingPercent()).isEqualByComparingTo("10.00");
        assertThat(e.burnRate()).isEqualByComparingTo("0.9000");
    }

    @Test
    @DisplayName("评估：低于目标判 BREACHED（优先于预算状态）")
    void evaluateBreached() {
        Evaluation e = evaluate(slo("AVAILABILITY", "99.9"), 10000, 9980);

        assertThat(e.status()).isEqualTo("BREACHED");
        assertThat(e.ratioPercent()).isEqualByComparingTo("99.8000");
        assertThat(e.badEvents()).isEqualTo(20);
        assertThat(e.budgetRemaining()).isEqualByComparingTo("-10.00");
    }

    @Test
    @DisplayName("评估：没有快照判 NO_DATA")
    void evaluateNoData() {
        when(repo.aggregate(any(), any())).thenReturn(Map.of(
                "total", 0L, "good", 0L, "samples", 0L));

        Evaluation e = service.evaluate(slo("AVAILABILITY", "99.9"));

        assertThat(e.status()).isEqualTo("NO_DATA");
        assertThat(e.sampleCount()).isZero();
    }

    @Test
    @DisplayName("SLO：延迟类必须提供阈值，非延迟类不能带阈值")
    void upsertValidatesLatency() {
        assertThatThrownBy(() -> service.upsert(req("LATENCY", null), "ops01", null, null))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.OPS_SLO_INVALID);

        assertThatThrownBy(() -> service.upsert(req("AVAILABILITY", 200), "ops01", null, null))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.OPS_SLO_INVALID);
    }

    @Test
    @DisplayName("快照：good 不能大于 total，total 必须大于 0")
    void snapshotValidatesEvents() {
        when(repo.findSlo(SLO_ID)).thenReturn(Optional.of(slo("AVAILABILITY", "99.9")));
        when(repo.findSnapshotByIdempotency(any())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.recordSnapshot(SLO_ID,
                snapshotReq("k1", 10, 11), "ops01", null, null))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.OPS_SLO_SNAPSHOT_INVALID);

        assertThatThrownBy(() -> service.recordSnapshot(SLO_ID,
                snapshotReq("k2", 0, 0), "ops01", null, null))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.OPS_SLO_SNAPSHOT_INVALID);
    }

    @Test
    @DisplayName("快照：同一幂等键相同内容直接返回，不同内容拒绝改写")
    void snapshotIdempotency() {
        when(repo.findSlo(SLO_ID)).thenReturn(Optional.of(slo("AVAILABILITY", "99.9")));
        Snapshot existing = new Snapshot(UUID.randomUUID(), SLO_ID, NOW.minusSeconds(3600), NOW,
                100, 99, "EXPORTER", "k3", null, "exporter", NOW);
        when(repo.findSnapshotByIdempotency("k3")).thenReturn(Optional.of(existing));

        Snapshot replay = service.recordSnapshot(SLO_ID, snapshotReq("k3", 100, 99),
                "ops01", null, null);
        assertThat(replay.id()).isEqualTo(existing.id());
        verify(repo, never()).insertSnapshot(any());

        assertThatThrownBy(() -> service.recordSnapshot(SLO_ID, snapshotReq("k3", 100, 90),
                "ops01", null, null))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.OPS_SLO_SNAPSHOT_CONFLICT);
    }

    @Test
    @DisplayName("看板：统计健康/风险/违约/无数据与平均达标率")
    void dashboardCounts() {
        Slo healthy = slo("AVAILABILITY", "99.9");
        Slo breached = new Slo(UUID.randomUUID(), "SLO-2", "错误率", "platform-gateway",
                "ERROR_RATE", new BigDecimal("99.5"), 30, null, "ops01", null, "ACTIVE",
                0, "ops01", NOW, NOW);
        when(repo.listSlos(true)).thenReturn(List.of(healthy, breached));
        when(repo.aggregate(eq(healthy.id()), any())).thenReturn(agg(10000, 10000, 1));
        when(repo.aggregate(eq(breached.id()), any())).thenReturn(agg(1000, 990, 1));

        Dashboard dashboard = service.dashboard();

        assertThat(dashboard.total()).isEqualTo(2);
        assertThat(dashboard.healthy()).isEqualTo(1);
        assertThat(dashboard.breached()).isEqualTo(1);
        assertThat(dashboard.exhausted()).isEqualTo(1);
        assertThat(dashboard.avgCompliance()).isEqualByComparingTo("99.50");
    }

    // ------------------------------------------------------------------ 构造

    private Evaluation evaluate(Slo slo, long total, long good) {
        when(repo.aggregate(eq(slo.id()), any())).thenReturn(agg(total, good, 1));
        return service.evaluate(slo);
    }

    private static Map<String, Object> agg(long total, long good, int samples) {
        return Map.of("total", total, "good", good, "samples", (long) samples,
                "window_start", Timestamp.from(NOW.minusSeconds(86400)),
                "window_end", Timestamp.from(NOW));
    }

    private static Slo slo(String indicator, String target) {
        return new Slo(SLO_ID, "SLO-GATEWAY", "网关可用性", "platform-gateway", indicator,
                new BigDecimal(target), 30, "LATENCY".equals(indicator) ? 300 : null,
                "ops01", null, "ACTIVE", 0, "ops01", NOW, NOW);
    }

    private static OpsDtos.SloUpsertReq req(String indicator, Integer latency) {
        return new OpsDtos.SloUpsertReq("SLO-T1", "测试", "platform-gateway", indicator,
                new BigDecimal("99.9"), 30, latency, "ops01", null, "ACTIVE");
    }

    private static OpsDtos.SloSnapshotReq snapshotReq(String key, long total, long good) {
        return new OpsDtos.SloSnapshotReq(NOW.minusSeconds(3600), NOW, total, good,
                "EXPORTER", key, null);
    }
}
