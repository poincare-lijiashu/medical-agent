package com.medassist.insurance.config;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicLong;

/**
 * 医保域业务打点（第四轮 SF-21 / §291）。
 *
 * <p>只统计"动作结果"，不记录患者/批次等业务标识，避免指标基数爆炸与 PHI 泄漏。
 * 指标名用下划线风格，与既有 {@code medassist_auth_login_total} 一致，
 * Prometheus 侧会自动暴露为 {@code medassist_insurance_catalog_import_total}。
 */
@Component
public class InsuranceMetrics {

    public static final String CATALOG_IMPORT = "medassist.insurance.catalog_import";
    public static final String EXPORT_ACTION = "medassist.insurance.export_action";
    public static final String RECON_ACTION = "medassist.insurance.recon_action";
    public static final String RECON_OPEN_DIFFS = "medassist.insurance.recon_open_diffs";
    public static final String PAYER_ACTION = "medassist.insurance.payer_action";

    private final MeterRegistry registry;
    /** 最近一次对账/处理后的未处理差异数（无标签，避免按批次拆分导致基数增长）。 */
    private final AtomicLong openDiffs = new AtomicLong(0);

    public InsuranceMetrics(MeterRegistry registry) {
        this.registry = registry;
        Gauge.builder(RECON_OPEN_DIFFS, openDiffs, AtomicLong::get)
                .description("最近一次医保对账的未处理差异数（0 表示无待办）")
                .register(registry);
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

    /** 对账动作（action=compute/resolve/close，result=ok/failed）。 */
    public void reconAction(String action, boolean ok) {
        counter(RECON_ACTION, "action", action, "result", ok ? "ok" : "failed");
    }

    /** 支付方分摊动作（action=create_payer/update_payer/allocate/refund…）。 */
    public void payerAction(String action, boolean ok) {
        counter(PAYER_ACTION, "action", action, "result", ok ? "ok" : "failed");
    }

    /** 更新"当前未处理差异数"（批次计算/处理/关闭后调用）。 */
    public void recordOpenDiffs(int count) {
        openDiffs.set(Math.max(0, count));
    }

    private void counter(String name, String tag1, String value1, String tag2, String value2) {
        Counter.builder(name).tag(tag1, value1).tag(tag2, value2)
                .register(registry).increment();
    }
}
