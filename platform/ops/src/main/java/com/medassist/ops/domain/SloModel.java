package com.medassist.ops.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * SLO 与错误预算模型（§300，对应 ops_svc.ops_slo* 表）。
 */
public final class SloModel {

    public static final String HEALTHY = "HEALTHY";
    public static final String AT_RISK = "AT_RISK";
    public static final String BREACHED = "BREACHED";
    public static final String NO_DATA = "NO_DATA";

    private SloModel() {
    }

    /** SLO 定义。 */
    public record Slo(
            UUID id,
            String code,
            String name,
            String serviceCode,
            String indicator,
            BigDecimal targetPercent,
            int windowDays,
            Integer latencyThresholdMs,
            String owner,
            String description,
            String status,
            long version,
            String createdBy,
            Instant createdAt,
            Instant updatedAt) {
    }

    /** 结算快照（窗口内 total/good 计数）。 */
    public record Snapshot(
            UUID id,
            UUID sloId,
            Instant windowStart,
            Instant windowEnd,
            long totalEvents,
            long goodEvents,
            String source,
            String idempotencyKey,
            String note,
            String reportedBy,
            Instant createdAt) {
    }

    /** 滚动窗口评估结果。 */
    public record Evaluation(
            String status,
            long totalEvents,
            long goodEvents,
            long badEvents,
            BigDecimal ratioPercent,
            BigDecimal errorBudgetEvents,
            BigDecimal budgetRemaining,
            BigDecimal budgetRemainingPercent,
            BigDecimal burnRate,
            Instant windowStart,
            Instant windowEnd,
            int sampleCount) {
    }

    /** SLO 详情（定义 + 评估 + 最近快照）。 */
    public record Detail(
            Slo slo,
            Evaluation evaluation,
            List<Snapshot> snapshots) {
    }

    /** 看板。 */
    public record Dashboard(
            Instant generatedAt,
            long total,
            long healthy,
            long atRisk,
            long breached,
            long noData,
            long exhausted,
            BigDecimal avgCompliance) {
    }
}
