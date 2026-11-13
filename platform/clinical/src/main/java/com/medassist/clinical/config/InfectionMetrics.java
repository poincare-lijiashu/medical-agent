package com.medassist.clinical.config;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicLong;

/**
 * 传染病上报业务打点（§294）。
 *
 * <p>只记录动作结果与待办规模，不携带患者/病种标签，避免指标基数爆炸与 PHI 泄漏。
 */
@Component
public class InfectionMetrics {

    public static final String ACTION = "medassist.clinical.infection_action";
    public static final String PENDING = "medassist.clinical.infection_pending";
    public static final String OVERDUE = "medassist.clinical.infection_overdue";

    private final MeterRegistry registry;
    private final AtomicLong pending = new AtomicLong(0);
    private final AtomicLong overdue = new AtomicLong(0);

    public InfectionMetrics(MeterRegistry registry) {
        this.registry = registry;
        Gauge.builder(PENDING, pending, AtomicLong::get)
                .description("当前待上报传染病报告卡数")
                .register(registry);
        Gauge.builder(OVERDUE, overdue, AtomicLong::get)
                .description("当前已过上报截止时间的报告卡数")
                .register(registry);
    }

    /** action=detect/submit/accept/return/void/manual，result=ok/failed。 */
    public void action(String action, boolean ok) {
        Counter.builder(ACTION).tag("action", action).tag("result", ok ? "ok" : "failed")
                .register(registry).increment();
    }

    public void recordPending(long value) {
        pending.set(Math.max(0, value));
    }

    public void recordOverdue(long value) {
        overdue.set(Math.max(0, value));
    }
}
