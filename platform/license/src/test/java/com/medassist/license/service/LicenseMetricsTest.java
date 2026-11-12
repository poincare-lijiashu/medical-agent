package com.medassist.license.service;

import com.medassist.license.domain.LicenseModel.ModuleEntitlement;
import com.medassist.license.domain.LicenseModel.Phase;
import com.medassist.license.domain.LicenseModel.Quota;
import com.medassist.license.domain.LicenseModel.State;
import com.medassist.license.domain.LicenseModel.Usage;
import com.medassist.license.web.LicenseDtos.StatusView;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 授权指标（§288）：到期/宽限/配额/模块权益必须能被 Prometheus 看见。
 *
 * <p>指标命名与标签是告警规则的唯一契约，改名即静默失配，用单测锁死。
 */
class LicenseMetricsTest {

    private LicenseService service;
    private SimpleMeterRegistry registry;
    private LicenseMetrics metrics;

    @BeforeEach
    void setUp() {
        service = mock(LicenseService.class);
        registry = new SimpleMeterRegistry();
        metrics = new LicenseMetrics(service, registry);
    }

    @Test
    @DisplayName("阶段/剩余天数/模块权益/配额使用率按实例发布")
    void publishesLicenseMetrics() {
        StatusView status = new StatusView(
                "MEDASSIST-DEV-0001", "ACTIVE",
                new State(Phase.ACTIVE, Instant.parse("2027-12-12T00:00:00Z"), 365L),
                null, null,
                List.of(new ModuleEntitlement("clinical", "临床核心", true, "已授权"),
                        new ModuleEntitlement("ai", "AI 中台", false, "授权未包含该模块")),
                List.of(new Quota("beds", "开放床位", 500, 450, 0.9, false),
                        new Quota("aiCalls", "AI 日调用", 1000, 1200, 1.2, true)),
                List.of(), List.of(), true, false, "key-1", "vendor");

        when(service.status()).thenReturn(status);
        metrics.refresh();

        assertThat(registry.get("medassist.license.phase")
                .tags("instance", "MEDASSIST-DEV-0001", "phase", "ACTIVE").gauge().value())
                .isEqualTo(1.0);
        assertThat(registry.get("medassist.license.days.left")
                .tags("instance", "MEDASSIST-DEV-0001").gauge().value()).isEqualTo(365.0);
        assertThat(registry.get("medassist.license.module.entitled")
                .tags("instance", "MEDASSIST-DEV-0001", "module", "clinical").gauge().value())
                .isEqualTo(1.0);
        assertThat(registry.get("medassist.license.module.entitled")
                .tags("instance", "MEDASSIST-DEV-0001", "module", "ai").gauge().value())
                .isZero();
        assertThat(registry.get("medassist.license.quota.utilization")
                .tags("instance", "MEDASSIST-DEV-0001", "quota", "beds").gauge().value())
                .isEqualTo(0.9);
        assertThat(registry.get("medassist.license.quota.exceeded")
                .tags("instance", "MEDASSIST-DEV-0001", "quota", "aiCalls").gauge().value())
                .isEqualTo(1.0);
        assertThat(registry.get("medassist.license.refresh.timestamp.seconds")
                .gauge().value()).isGreaterThan(0);
    }

    @Test
    @DisplayName("无授权（NONE）也发布阶段与 -1 天，告警不会因为缺序列而沉默")
    void publishesNonePhase() {
        StatusView status = new StatusView("MEDASSIST-DEV-0001", "NONE",
                new State(Phase.NONE, null, null), null, null, List.of(), List.of(),
                List.of("尚未导入授权书"), List.of(), true, false, null, "hospital");
        when(service.status()).thenReturn(status);

        metrics.refresh();

        assertThat(registry.get("medassist.license.phase")
                .tags("instance", "MEDASSIST-DEV-0001", "phase", "NONE").gauge().value())
                .isEqualTo(1.0);
        assertThat(registry.get("medassist.license.days.left")
                .tags("instance", "MEDASSIST-DEV-0001").gauge().value()).isEqualTo(-1.0);
    }

    @Test
    @DisplayName("刷新失败只计数，不影响已有指标")
    void refreshFailureIsCounted() {
        when(service.status()).thenThrow(new IllegalStateException("db down"));
        metrics.refresh();

        assertThat(registry.get("medassist.license.refresh.errors").counter().count())
                .isEqualTo(1.0);
    }

    @Test
    @DisplayName("§289：发布 AI 调用数与用量新鲜度，并记录上报结果")
    void publishesUsageFreshnessAndIngestResult() {
        Usage usage = new Usage(java.time.LocalDate.now(java.time.ZoneOffset.UTC), 10, 20, 321,
                "AI_CORE", Instant.now().minusSeconds(90));
        StatusView status = new StatusView("MEDASSIST-DEV-0001", "ACTIVE",
                new State(Phase.ACTIVE, Instant.parse("2027-12-12T00:00:00Z"), 365L),
                null, usage, List.of(), List.of(), List.of(), List.of(),
                false, false, "key-1", "hospital");
        when(service.status()).thenReturn(status);

        metrics.refresh();
        metrics.recordIngest("ok");
        metrics.recordIngest("ok");
        metrics.recordIngest("rejected");

        assertThat(registry.get("medassist.license.usage.ai.calls")
                .tags("instance", "MEDASSIST-DEV-0001").gauge().value()).isEqualTo(321.0);
        assertThat(registry.get("medassist.license.usage.age.seconds")
                .tags("instance", "MEDASSIST-DEV-0001").gauge().value())
                .isBetween(89.0, 600.0);
        assertThat(registry.get("medassist.license.usage.ingest")
                .tag("result", "ok").counter().count()).isEqualTo(2.0);
        assertThat(registry.get("medassist.license.usage.ingest")
                .tag("result", "rejected").counter().count()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("§289：没有用量快照时新鲜度为 -1，不误报告警")
    void publishesNoUsageAsNegativeAge() {
        StatusView status = new StatusView("MEDASSIST-DEV-0001", "NONE",
                new State(Phase.NONE, null, null), null, null, List.of(), List.of(),
                List.of(), List.of(), false, false, null, "hospital");
        when(service.status()).thenReturn(status);

        metrics.refresh();

        assertThat(registry.get("medassist.license.usage.age.seconds")
                .tags("instance", "MEDASSIST-DEV-0001").gauge().value()).isEqualTo(-1.0);
        assertThat(registry.get("medassist.license.usage.ai.calls")
                .tags("instance", "MEDASSIST-DEV-0001").gauge().value()).isZero();
    }
}
