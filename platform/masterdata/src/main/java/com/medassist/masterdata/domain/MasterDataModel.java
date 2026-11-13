package com.medassist.masterdata.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 主数据治理领域模型（不可变记录）。
 *
 * <p>约定：
 * <ul>
 *   <li>所有 JSONB 字段在 Java 侧用 {@code Map<String,Object>} 承载，写入前做规范化；</li>
 *   <li>状态一律用字符串常量 + 数据库 CHECK 双保险；</li>
 *   <li>条目 {@code version} 为乐观锁版本号，更新必须带 CAS；</li>
 *   <li>映射不物理删除：停用即 {@code status=INACTIVE}，历史保留。</li>
 * </ul>
 */
public final class MasterDataModel {

    private MasterDataModel() {
    }

    /** 主数据域注册。 */
    public record Domain(
            String domainCode,
            String name,
            String category,
            String ownerService,
            String sourceSchema,
            String sourceTable,
            String codeColumn,
            String nameColumn,
            String syncAdapter,
            boolean syncable,
            List<String> requiredSystems,
            String description,
            int sortNo,
            boolean enabled,
            Instant lastSyncedAt,
            String lastSyncStatus,
            Map<String, Object> lastSyncSummary) {
    }

    /** 主数据条目（治理索引）。 */
    public record Item(
            UUID id,
            String domainCode,
            String localCode,
            String name,
            String alias,
            String category,
            String spec,
            String unit,
            String status,
            String sourceSystem,
            String sourceRef,
            LocalDate effectiveFrom,
            LocalDate effectiveTo,
            Map<String, Object> attributes,
            String fingerprint,
            long version,
            Instant createdAt,
            Instant updatedAt) {

        public static final String ACTIVE = "ACTIVE";
        public static final String INACTIVE = "INACTIVE";
        public static final String MISSING_IN_SOURCE = "MISSING_IN_SOURCE";

        public static final String SOURCE_IN_HOUSE = "IN_HOUSE";
        public static final String SOURCE_MANUAL = "MANUAL";
        public static final String SOURCE_IMPORT = "IMPORT";

        public boolean active() {
            return ACTIVE.equals(status);
        }
    }

    /** 条目版本（追加式，带哈希链）。 */
    public record ItemVersion(
            long id,
            UUID itemId,
            String domainCode,
            String localCode,
            long versionNo,
            String changeType,
            Map<String, Object> snapshot,
            Map<String, Object> diff,
            String reason,
            String refType,
            String refId,
            String actor,
            String prevChecksum,
            String checksum,
            Instant createdAt) {
    }

    /** 外部编码体系。 */
    public record ExternalSystem(
            String systemCode,
            String name,
            String systemType,
            String vendor,
            String endpoint,
            String contact,
            String status,
            int sortNo) {

        public static final String ACTIVE = "ACTIVE";
    }

    /** 外部编码映射。 */
    public record ExternalMap(
            UUID id,
            String domainCode,
            String localCode,
            String localName,
            String systemCode,
            String systemName,
            String externalCode,
            String externalName,
            String mapType,
            boolean primary,
            String status,
            LocalDate effectiveFrom,
            LocalDate effectiveTo,
            String remark,
            long version,
            Instant createdAt,
            Instant updatedAt) {

        public static final String ACTIVE = "ACTIVE";
        public static final String INACTIVE = "INACTIVE";
        public static final String EXACT = "EXACT";
        public static final String EQUIVALENT = "EQUIVALENT";
        public static final String GROUP = "GROUP";
    }

    /** 映射版本（追加式）。 */
    public record MappingVersion(
            long id,
            UUID mappingId,
            String domainCode,
            String localCode,
            String systemCode,
            String externalCode,
            long versionNo,
            String changeType,
            Map<String, Object> snapshot,
            String reason,
            String refType,
            String refId,
            String actor,
            Instant createdAt) {
    }

    /** 变更申请。 */
    public record ChangeRequest(
            UUID id,
            String requestNo,
            String changeType,
            String domainCode,
            String localCode,
            Map<String, Object> payload,
            String reason,
            String status,
            String submittedBy,
            Instant submittedAt,
            String reviewedBy,
            Instant reviewedAt,
            String reviewComment,
            Long appliedVersion,
            long version,
            Instant createdAt,
            Instant updatedAt) {

        public static final String SUBMITTED = "SUBMITTED";
        public static final String APPLIED = "APPLIED";
        public static final String REJECTED = "REJECTED";
        public static final String CANCELLED = "CANCELLED";

        public static final String ITEM_CREATE = "ITEM_CREATE";
        public static final String ITEM_UPDATE = "ITEM_UPDATE";
        public static final String ITEM_STATUS = "ITEM_STATUS";
        public static final String MAPPING_UPSERT = "MAPPING_UPSERT";
        public static final String MAPPING_DELETE = "MAPPING_DELETE";
    }

    /** 导入批次。 */
    public record ImportBatch(
            UUID id,
            String batchNo,
            String mode,
            String format,
            String domainCode,
            String fileName,
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
            List<ImportRow> rows) {

        public static final String PREVIEW = "PREVIEW";
        public static final String APPLY = "APPLY";
        public static final String PREVIEWED = "PREVIEWED";
        public static final String APPLIED = "APPLIED";
        public static final String FAILED = "FAILED";
    }

    /** 导入逐行结果。 */
    public record ImportRow(
            long id,
            int rowNo,
            String localCode,
            String action,
            String message,
            Map<String, Object> detail) {

        public static final String CREATE = "CREATE";
        public static final String UPDATE = "UPDATE";
        public static final String UNCHANGED = "UNCHANGED";
        public static final String CONFLICT = "CONFLICT";
        public static final String ERROR = "ERROR";
    }

    /** 条目详情（含映射与最近版本）。 */
    public record ItemDetail(
            Item item,
            List<ExternalMap> mappings,
            List<ItemVersion> versions) {
    }

    /** 域清单 + 规模统计。 */
    public record DomainSummary(
            Domain domain,
            long totalItems,
            long activeItems,
            long inactiveItems,
            long missingItems,
            long mappedActive,
            long unmappedActive,
            long missingRequiredMapping) {
    }

    /** 映射反查结果：多义时返回全部候选，由调用方决定是否继续。 */
    public record MappingLookup(
            String systemCode,
            String externalCode,
            List<ExternalMap> candidates,
            boolean ambiguous) {
    }

    /** 源表同步结果。 */
    public record SyncResult(
            String domainCode,
            boolean dryRun,
            int sourceCount,
            int created,
            int updated,
            int restored,
            int missing,
            int unchanged,
            List<String> conflicts,
            List<SyncChange> sample,
            Instant generatedAt) {
    }

    /** 同步的逐条变化（只保留样例行，避免一次响应过大）。 */
    public record SyncChange(String localCode, String action, String name) {
    }

    /** 版本链校验结果。 */
    public record VersionChainResult(
            String domainCode,
            String localCode,
            long checked,
            boolean ok,
            Long brokenAtVersion,
            String message) {
    }

    /** 质量看板：按域汇总 + 问题清单。 */
    public record QualityReport(
            int staleDays,
            List<DomainQuality> domains,
            List<QualityIssue> issues,
            Instant generatedAt) {
    }

    /** 单个域的质量指标。 */
    public record DomainQuality(
            String domainCode,
            String name,
            long total,
            long active,
            long inactive,
            long missingInSource,
            long mappedActive,
            long unmappedActive,
            long missingRequiredMapping,
            long duplicateNameGroups,
            long ambiguousExternalCodes,
            Instant lastSyncedAt,
            String lastSyncStatus,
            boolean syncStale,
            List<String> requiredSystems) {
    }

    /** 具体质量问题。 */
    public record QualityIssue(
            String type,
            String domainCode,
            String localCode,
            String name,
            String detail) {

        public static final String UNMAPPED = "UNMAPPED";
        public static final String MISSING_REQUIRED_MAPPING = "MISSING_REQUIRED_MAPPING";
        public static final String DUPLICATE_NAME = "DUPLICATE_NAME";
        public static final String AMBIGUOUS_EXTERNAL_CODE = "AMBIGUOUS_EXTERNAL_CODE";
        public static final String MISSING_IN_SOURCE = "MISSING_IN_SOURCE";
        public static final String SYNC_STALE = "SYNC_STALE";
    }

    /** 审计日志。 */
    public record AuditLog(
            long id,
            Instant ts,
            String actor,
            String action,
            String domainCode,
            String localCode,
            String refId,
            Map<String, Object> detail,
            String clientIp,
            String traceId) {
    }

    // ---------------------------------------------------------------- 指标字典（§293）

    /**
     * 指标定义（head）。
     *
     * <p>只有 DRAFT 可编辑；PUBLISHED 必须 revise 出新版本后再发布，
     * 避免"口径被静默改掉、报表历史无法解释"。
     */
    public record MetricDefinition(
            String code,
            String name,
            String domain,
            String definition,
            String formula,
            String aggregation,
            String unit,
            String timeWindow,
            String granularity,
            String sourceSchema,
            List<String> sourceTables,
            String ownerDept,
            String ownerRole,
            String status,
            long version,
            Long publishedVersion,
            Instant publishedAt,
            String publishedBy,
            String createdBy,
            String updatedBy,
            Instant createdAt,
            Instant updatedAt) {

        public static final String DRAFT = "DRAFT";
        public static final String PUBLISHED = "PUBLISHED";
        public static final String DISABLED = "DISABLED";

        public boolean draft() {
            return DRAFT.equals(status);
        }

        public boolean published() {
            return PUBLISHED.equals(status);
        }

        /** 已发布过但当前 head 是修订草稿。 */
        public boolean revisionInProgress() {
            return DRAFT.equals(status) && publishedVersion != null;
        }
    }

    /** 指标版本事件（追加式，带快照与 SHA-256）。 */
    public record MetricVersion(long id, String metricCode, long versionNo, String changeType,
                                Map<String, Object> snapshot, String checksum, String actor,
                                Instant createdAt) {

        public static final String CREATE = "CREATE";
        public static final String UPDATE = "UPDATE";
        public static final String PUBLISH = "PUBLISH";
        public static final String REVISE = "REVISE";
        public static final String DISABLE = "DISABLE";
    }

    /** 指标血缘：UPSTREAM 源表/接口，DOWNSTREAM 报表/看板/派生指标。 */
    public record MetricLineage(UUID id, String metricCode, String direction, String refType,
                                String refCode, String description, String createdBy,
                                Instant createdAt) {

        public static final String UPSTREAM = "UPSTREAM";
        public static final String DOWNSTREAM = "DOWNSTREAM";
    }

    /** 指标详情：定义 + 版本链 + 血缘。 */
    public record MetricDetail(MetricDefinition metric, List<MetricVersion> versions,
                               List<MetricLineage> lineage) {
    }

    /** 指标影响分析：上下游数量、版本数与修订状态。 */
    public record MetricImpact(String metricCode, String name, String status, long version,
                               Long publishedVersion, boolean revisionInProgress,
                               int upstreamCount, int downstreamCount, int versionCount,
                               Instant lastPublishedAt) {
    }

    /** 指标字典汇总。 */
    public record MetricSummary(long total, long draft, long published, long disabled,
                                long revisionInProgress, List<MetricDomainCount> domains) {
    }

    /** 按域统计。 */
    public record MetricDomainCount(String domain, long total, long published) {
    }
}
