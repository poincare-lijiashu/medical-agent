package com.medassist.auth.service;

import com.medassist.auth.web.dto.TenantHealthDtos;
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
 * 租户健康度指标（§287）：按院打标签、结论一对一、失败可观测。
 *
 * <p>这些指标是 Alertmanager 按院路由的唯一数据源，命名与标签一旦漂移，
 * 规则会静默失配（没有告警比误报更危险），所以用单测锁死。
 */
class TenantHealthMetricsTest {

    private TenantHealthService service;
    private SimpleMeterRegistry registry;
    private TenantHealthMetrics metrics;

    @BeforeEach
    void setUp() {
        service = mock(TenantHealthService.class);
        registry = new SimpleMeterRegistry();
        metrics = new TenantHealthMetrics(service, registry);
    }

    @Test
    @DisplayName("健康/备份/可达/账号数与归档时间按 tenant 标签发布")
    void publishesTenantScopedMetrics() {
        when(service.list()).thenReturn(new TenantHealthDtos.HealthList(List.of(
                view("tenant-001", "OK", "READY", true, 42, Instant.parse("2026-11-12T14:47:49Z")),
                view("tenant-003", "DOWN", "DEGRADED", false, 8, null)), 2, Instant.now()));

        metrics.refresh();

        assertThat(gauge("medassist.tenant.health.state", "tenant", "tenant-001", "state", "OK"))
                .isEqualTo(1.0);
        assertThat(gauge("medassist.tenant.health.state", "tenant", "tenant-003", "state", "DOWN"))
                .isEqualTo(1.0);
        assertThat(gauge("medassist.tenant.cell.reachable", "tenant", "tenant-003")).isZero();
        assertThat(gauge("medassist.tenant.backup.ready", "tenant", "tenant-001")).isEqualTo(1.0);
        assertThat(gauge("medassist.tenant.backup.ready", "tenant", "tenant-003")).isZero();
        assertThat(gauge("medassist.tenant.backup.state", "tenant", "tenant-003", "state", "DEGRADED"))
                .isEqualTo(1.0);
        assertThat(gauge("medassist.tenant.active.users", "tenant", "tenant-001")).isEqualTo(42.0);
        assertThat(gauge("medassist.tenant.last.archived.timestamp.seconds",
                "tenant", "tenant-001")).isEqualTo(1794494869.0);
        assertThat(gauge("medassist.tenant.last.archived.timestamp.seconds",
                "tenant", "tenant-003")).isZero();
        assertThat(gauge("medassist.tenant.count", "state", "OK")).isEqualTo(1.0);
        assertThat(gauge("medassist.tenant.count", "state", "DOWN")).isEqualTo(1.0);
    }

    @Test
    @DisplayName("刷新成功推进时间戳；失败只加计数、不破坏上一轮的值")
    void refreshFailureIsObservable() {
        when(service.list()).thenReturn(new TenantHealthDtos.HealthList(
                List.of(view("tenant-001", "OK", "READY", true, 1, null)), 1, Instant.now()));
        metrics.refresh();
        double stamp = registry.get("medassist.tenant.health.refresh.timestamp.seconds")
                .gauge().value();
        assertThat(stamp).isGreaterThan(0);

        when(service.list()).thenThrow(new IllegalStateException("db down"));
        metrics.refresh();

        assertThat(registry.get("medassist.tenant.health.refresh.errors").counter().count())
                .isEqualTo(1.0);
        assertThat(registry.get("medassist.tenant.health.refresh.timestamp.seconds")
                .gauge().value()).isEqualTo(stamp);
        // 上一轮已发布的院指标仍在，规则不会因一次刷新失败而丢失序列
        assertThat(gauge("medassist.tenant.cell.reachable", "tenant", "tenant-001")).isEqualTo(1.0);
    }

    private double gauge(String name, String... tags) {
        return registry.get(name).tags(tags).gauge().value();
    }

    private static TenantHealthDtos.HealthView view(String tenant, String state, String backup,
                                                    boolean reachable, long users,
                                                    Instant lastArchived) {
        return new TenantHealthDtos.HealthView(tenant, "演示院", "ACTIVE", "MEDASSIST-X",
                reachable, 12L, "100", true, false, 15, Instant.now(), users, users,
                "SUCCEEDED", Instant.now(), backup, "bucket-" + tenant, null, 7, 1, lastArchived,
                state, List.of(), Instant.now());
    }
}
