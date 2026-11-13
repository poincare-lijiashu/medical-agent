package com.medassist.masterdata.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 数据质量模型（§299，对应 mdm_svc.md_dq_* 表）。
 */
public final class DataQualityModel {

    public static final String STATUS_OPEN = "OPEN";
    public static final String STATUS_ACKNOWLEDGED = "ACKNOWLEDGED";
    public static final String STATUS_RESOLVED = "RESOLVED";
    public static final String STATUS_WAIVED = "WAIVED";

    private DataQualityModel() {
    }

    /** 质量规则（SQL 探针）。 */
    public record Rule(
            UUID id,
            String code,
            String name,
            String domain,
            String dimension,
            String sqlText,
            String comparator,
            BigDecimal threshold,
            BigDecimal threshold2,
            String unit,
            String severity,
            String owner,
            String status,
            long version,
            String createdBy,
            Instant createdAt,
            Instant updatedAt) {
    }

    /** 执行记录。 */
    public record Run(
            UUID id,
            String runNo,
            UUID ruleId,
            String ruleCode,
            String status,
            BigDecimal observedValue,
            boolean passed,
            String errorMessage,
            String runMode,
            String triggeredBy,
            int durationMs,
            Instant startedAt,
            Instant finishedAt) {
    }

    /** 质量问题（失败自动开单，重复失败累加次数）。 */
    public record Issue(
            UUID id,
            String issueNo,
            UUID ruleId,
            String ruleCode,
            String ruleName,
            String severity,
            String status,
            String owner,
            UUID firstRunId,
            UUID lastRunId,
            int occurrenceCount,
            BigDecimal observedValue,
            String acknowledgedBy,
            Instant acknowledgedAt,
            String resolution,
            Instant resolvedAt,
            String waiverReason,
            String waiverBy,
            Instant waiverAt,
            long version,
            Instant createdAt,
            Instant updatedAt) {
    }

    /** 看板。 */
    public record Dashboard(
            Instant generatedAt,
            long activeRules,
            long runs24h,
            long failedRuns24h,
            long errorRuns24h,
            double passRate24h,
            long openIssues,
            long blockers,
            List<RuleFailure> topFailures) {
    }

    /** 近 24 小时失败次数排行。 */
    public record RuleFailure(String ruleCode, String ruleName, long failures) {
    }
}
