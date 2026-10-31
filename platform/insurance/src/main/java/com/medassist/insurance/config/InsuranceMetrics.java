package com.medassist.insurance.config;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/**
 * 医保域业务打点（第四轮 SF-21）。
 *
 * <p>只统计"动作结果"，不记录患者/批次等业务标识，避免指标基数爆炸与 PHI 泄漏。
 * 指标名用下划线风格，与既有 {@code medassist_auth_login_total} 一致，
 * Prometheus 侧会自动暴露为 {@code medassist_insurance_catalog_import_total}。
 */
@Component
public class InsuranceMetrics {

    public static final String CATALOG_IMPORT = "medassist.insurance.catalog_import";
    public static final String EXPORT_ACTION = "medassist.insurance.export_action";

    private final MeterRegistry registry;

    public InsuranceMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    /** 目录文件导入（mode=preview/apply，result=ok/failed）。 */
    public void catalogImport(String mode, boolean ok) {
        counter(CATALOG_IMPORT, "mode", mode == null ? "unknown" : mode.toLowerCase(),
                "result", ok ? "ok" : "failed");
    }

    /** 报盘动作（action=create/receipt/cancel/download，result=ok/failed）。 */
    public void exportAction(String action, boolean ok) {
        counter(EXPORT_ACTION, "action", action, "result", ok ? "ok" : "failed");
    }

    private void counter(String name, String tag1, String value1, String tag2, String value2) {
        Counter.builder(name).tag(tag1, value1).tag(tag2, value2)
                .register(registry).increment();
    }
}
