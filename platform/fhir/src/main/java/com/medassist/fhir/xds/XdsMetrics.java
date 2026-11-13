package com.medassist.fhir.xds;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/**
 * XDS 运行指标（无 PHI，只按文档类型与结果计数）。
 *
 * <p>为什么单独一组指标：文档共享是区域平台调用，出问题时最先被问的是
 * "注册成功多少、调阅失败多少、哈希不一致有没有发生"。
 */
@Component
public class XdsMetrics {

    private static final String PROVIDED = "medassist.xds.documents.provided";
    private static final String GENERATED = "medassist.xds.documents.generated";
    private static final String QUERIED = "medassist.xds.registry.queries";
    private static final String RETRIEVED = "medassist.xds.documents.retrieved";
    private static final String DEPRECATED = "medassist.xds.documents.deprecated";
    private static final String HASH_MISMATCH = "medassist.xds.integrity.hash_mismatch";

    private final MeterRegistry meter;

    public XdsMetrics(MeterRegistry meter) {
        this.meter = meter;
    }

    public void provided(String classCode, String outcome) {
        meter.counter(PROVIDED, "class", classCode, "outcome", outcome).increment();
    }

    public void generated(String classCode, String outcome) {
        meter.counter(GENERATED, "class", classCode, "outcome", outcome).increment();
    }

    public void queried(String outcome) {
        meter.counter(QUERIED, "outcome", outcome).increment();
    }

    public void retrieved(String outcome) {
        meter.counter(RETRIEVED, "outcome", outcome).increment();
    }

    public void deprecated(String outcome) {
        meter.counter(DEPRECATED, "outcome", outcome).increment();
    }

    public void hashMismatch() {
        meter.counter(HASH_MISMATCH).increment();
    }
}
