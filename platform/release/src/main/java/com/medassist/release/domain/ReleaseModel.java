package com.medassist.release.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 发布域模型（与 release_svc 表对应）。
 */
public final class ReleaseModel {

    public static final String DRAFT = "DRAFT";
    public static final String RELEASED = "RELEASED";
    public static final String DEPRECATED = "DEPRECATED";
    public static final String YANKED = "YANKED";

    public static final String PLANNED = "PLANNED";
    public static final String FROZEN = "FROZEN";
    public static final String RELEASING = "RELEASING";
    public static final String TRAIN_RELEASED = "RELEASED";
    public static final String CANCELLED = "CANCELLED";

    private ReleaseModel() {
    }

    public record Component(
            String code,
            String name,
            String kind,
            String ownerGroup,
            String status,
            int sortNo) {
    }

    public record Version(
            UUID id,
            String componentCode,
            String version,
            String gitCommit,
            String imageDigest,
            String schemaVersion,
            String status,
            boolean breakingChanges,
            String changelog,
            Instant releasedAt,
            String createdBy,
            long lockVersion,
            Instant createdAt,
            Instant updatedAt) {
    }

    public record Train(
            UUID id,
            String trainCode,
            String name,
            LocalDate plannedDate,
            Instant freezeAt,
            String status,
            String notes,
            Instant releasedAt,
            String createdBy,
            long version,
            Instant createdAt,
            Instant updatedAt,
            List<TrainItem> items) {
    }

    public record TrainItem(
            UUID id,
            UUID trainId,
            String componentCode,
            UUID versionId,
            String addedBy,
            String versionNo) {
    }

    public record Compatibility(
            UUID id,
            UUID versionId,
            String kind,
            UUID targetVersionId,
            String note,
            String createdBy,
            Instant createdAt) {
    }

    public record Patch(
            UUID id,
            String patchNo,
            String title,
            String severity,
            String componentCode,
            List<String> affectedVersions,
            UUID fixedVersionId,
            String advisory,
            String cveId,
            String status,
            String requestedBy,
            String approvedBy,
            Instant approvedAt,
            Instant releasedAt,
            long version,
            Instant createdAt,
            Instant updatedAt) {
    }

    public record ChangeWindow(
            UUID id,
            String name,
            String kind,
            Instant startAt,
            Instant endAt,
            List<String> scope,
            String reason,
            String status,
            String createdBy,
            long version) {
    }

    public record Deployment(
            UUID id,
            String componentCode,
            UUID versionId,
            String environment,
            String action,
            String result,
            Map<String, Object> checklist,
            UUID rollbackOf,
            String reason,
            String actor,
            Instant startedAt,
            Instant finishedAt,
            Instant createdAt) {
    }

    public record AuditLog(
            long id,
            Instant ts,
            String actor,
            String action,
            String targetRef,
            Map<String, Object> detail,
            String clientIp,
            String traceId) {
    }
}
