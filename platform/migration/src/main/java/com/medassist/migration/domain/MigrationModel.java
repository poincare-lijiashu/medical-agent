package com.medassist.migration.domain;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 迁移域模型（与 mig_svc 表一一对应）。
 *
 * <p>刻意全部使用不可变 record：批次/行在服务层流转时不能被顺手改字段，
 * 状态推进只能走仓储里显式的 SQL。
 */
public final class MigrationModel {

    public static final String PREVIEWED = "PREVIEWED";
    public static final String APPLIED = "APPLIED";
    public static final String ROLLED_BACK = "ROLLED_BACK";
    public static final String FAILED = "FAILED";

    public static final String CREATE = "CREATE";
    public static final String UPDATE = "UPDATE";
    public static final String UNCHANGED = "UNCHANGED";
    public static final String CONFLICT = "CONFLICT";
    public static final String ERROR = "ERROR";

    private MigrationModel() {
    }

    /** 目标注册。 */
    public record Target(
            String code,
            String name,
            String category,
            String targetSchema,
            String targetTable,
            String naturalKey,
            String applyMode,
            int sortNo,
            String description) {
    }

    /** 字段字典。 */
    public record FieldDef(
            String fieldKey,
            String label,
            String dataType,
            boolean required,
            Integer maxLength,
            List<String> enumValues,
            String referenceTarget,
            String description,
            int sortNo) {
    }

    /** 目标详情：目标 + 字段字典 + 直接依赖 + 依赖它的目标。 */
    public record TargetDetail(
            Target target,
            List<FieldDef> fields,
            List<String> dependsOn,
            List<String> requiredBy) {
    }

    /** 列映射规则：源列 → 目标字段（可带转换/常量/映射表）。 */
    public record MappingRule(
            String source,
            String field,
            String transform,
            String value,
            Map<String, String> mapping) {
    }

    /** 可复用映射档案。 */
    public record MappingProfile(
            UUID id,
            String profileCode,
            String name,
            String sourceSystem,
            String targetCode,
            List<MappingRule> mappings,
            String status,
            long version,
            String createdBy,
            Instant createdAt,
            Instant updatedAt) {
    }

    /** 迁移批次。 */
    public record Batch(
            UUID id,
            String batchNo,
            String targetCode,
            String sourceSystem,
            String fileName,
            String profileCode,
            String checksum,
            int rowCount,
            int createCount,
            int updateCount,
            int unchangedCount,
            int conflictCount,
            int errorCount,
            String status,
            Map<String, Object> summary,
            String createdBy,
            Instant createdAt,
            Instant appliedAt,
            Instant rolledBackAt,
            String rolledBackBy,
            List<Row> rows) {

        public boolean blocking() {
            return conflictCount > 0 || errorCount > 0;
        }
    }

    /** 逐行台账。 */
    public record Row(
            long id,
            UUID batchId,
            int rowNo,
            Map<String, Object> raw,
            Map<String, Object> normalized,
            String action,
            UUID targetPk,
            String naturalKey,
            Map<String, Object> beforeImage,
            Map<String, Object> afterImage,
            String message,
            boolean applied,
            boolean rolledBack) {
    }

    /** 审计记录。 */
    public record AuditLog(
            long id,
            Instant ts,
            String actor,
            String action,
            String targetCode,
            String batchNo,
            Map<String, Object> detail,
            String clientIp,
            String traceId) {
    }
}
