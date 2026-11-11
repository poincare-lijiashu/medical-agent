package com.medassist.clinical.integration;

import com.medassist.clinical.domain.IntegrationInboundMessage;
import com.medassist.clinical.domain.IntegrationMessage;
import com.medassist.clinical.repo.IntegrationInboundRepository;
import com.medassist.clinical.repo.IntegrationRepository;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * 集成通道指标（第 258 批）。
 *
 * <p>为什么必须导出指标：接口失败如果只在页面台账里，值班人员不会主动看；
 * Prometheus 告警需要"队列深度 / 失败数 / 入站积压"这类可直接表达健康度的数值。
 *
 * <p>队列深度用定时刷新的 gauge（30 秒一次单条聚合 SQL），而不是每次抓取都查库：
 * 抓取频率远高于业务变化，按抓取查库会把监控变成数据库压力源。
 */
@Component
public class IntegrationMetrics {

    private final MeterRegistry registry;
    private final IntegrationRepository outbound;
    private final IntegrationInboundRepository inbound;

    private final AtomicInteger pending = new AtomicInteger();
    private final AtomicInteger failed = new AtomicInteger();
    private final AtomicInteger dead = new AtomicInteger();
    private final AtomicInteger inboundReceived = new AtomicInteger();
    private final AtomicInteger inboundFailed = new AtomicInteger();

    public IntegrationMetrics(MeterRegistry registry, IntegrationRepository outbound,
                              IntegrationInboundRepository inbound) {
        this.registry = registry;
        this.outbound = outbound;
        this.inbound = inbound;
        gauge("medassist.integration.queue", "PENDING", pending);
        gauge("medassist.integration.queue", "FAILED", failed);
        gauge("medassist.integration.queue", "DEAD", dead);
        gauge("medassist.integration.inbound.queue", "RECEIVED", inboundReceived);
        gauge("medassist.integration.inbound.queue", "FAILED", inboundFailed);
    }

    private void gauge(String name, String status, AtomicInteger value) {
        Gauge.builder(name, value, AtomicInteger::get).tag("status", status).register(registry);
    }

    /** 出站结果计数：acked / failed / dead。 */
    public void outbound(String result) {
        registry.counter("medassist.integration.outbound", "result", result).increment();
    }

    /** 入站结果计数：accepted / duplicate / conflict / failed。 */
    public void inbound(String result, String systemCode) {
        registry.counter("medassist.integration.inbound", "result", result,
                "system", systemCode == null ? "unknown" : systemCode).increment();
    }

    /** 每 30 秒刷新一次队列深度（一次聚合查询，成本可预期）。 */
    @Scheduled(fixedDelayString = "${medassist.integration.metrics-refresh-ms:30000}",
            initialDelayString = "${medassist.integration.metrics-initial-ms:15000}")
    public void refreshQueueDepth() {
        try {
            int p = 0;
            int f = 0;
            int d = 0;
            for (IntegrationRepository.StatusCount row : outbound.countByStatus()) {
                switch (row.status()) {
                    case IntegrationMessage.PENDING, IntegrationMessage.SENDING -> p += row.count();
                    case IntegrationMessage.FAILED -> f += row.count();
                    case IntegrationMessage.DEAD -> d += row.count();
                    default -> {
                        // ACKED 不进入队列
                    }
                }
            }
            pending.set(p);
            failed.set(f);
            dead.set(d);

            int received = 0;
            int inboundErrors = 0;
            for (IntegrationInboundRepository.StatusCount row : inbound.countByStatus()) {
                if (IntegrationInboundMessage.RECEIVED.equals(row.status())) {
                    received = row.count();
                } else if (IntegrationInboundMessage.FAILED.equals(row.status())) {
                    inboundErrors = row.count();
                }
            }
            inboundReceived.set(received);
            inboundFailed.set(inboundErrors);
        } catch (RuntimeException ignored) {
            // 指标刷新失败不能影响业务线程；保留上一次值
        }
    }
}
