package com.medassist.release.web;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 发布 API 请求体。
 *
 * <p>版本号只做字符集约束，不强行 semver：院方可能用 {@code 2026.11.1-rc1} 或内部构建号；
 * 真正重要的是"组件 + 版本号唯一 + 不可变"，而不是版本号长得像不像 semver。
 */
public final class ReleaseDtos {

    private ReleaseDtos() {
    }

    public record ComponentReq(
            @NotBlank @Size(max = 64) String code,
            @NotBlank @Size(max = 128) String name,
            @NotBlank @Pattern(regexp = "SERVICE|AI|INFRA|WEB") String kind,
            @NotBlank @Size(max = 64) String ownerGroup,
            @Pattern(regexp = "ACTIVE|INACTIVE") String status,
            @Min(1) @Max(9999) Integer sortNo) {
    }

    public record VersionReq(
            @NotBlank @Size(max = 64) String componentCode,
            @NotBlank @Size(max = 64) @Pattern(regexp = "[0-9A-Za-z._+-]{1,64}") String version,
            @Size(max = 64) String gitCommit,
            @Size(max = 200) String imageDigest,
            @Size(max = 64) String schemaVersion,
            Boolean breakingChanges,
            @Size(max = 4000) String changelog) {
    }

    public record TrainReq(
            @NotBlank @Size(max = 64) String trainCode,
            @NotBlank @Size(max = 128) String name,
            @NotNull LocalDate plannedDate,
            @NotNull Instant freezeAt,
            @Size(max = 1000) String notes) {
    }

    public record TrainItemReq(
            @NotBlank @Size(max = 64) String componentCode,
            @NotNull UUID versionId) {
    }

    public record CompatibilityReq(
            @NotNull UUID versionId,
            @NotBlank @Pattern(regexp = "REQUIRES|CONFLICTS") String kind,
            @NotNull UUID targetVersionId,
            @Size(max = 500) String note) {
    }

    public record PatchReq(
            @NotBlank @Size(max = 200) String title,
            @NotBlank @Pattern(regexp = "CRITICAL|HIGH|MEDIUM|LOW") String severity,
            @NotBlank @Size(max = 64) String componentCode,
            @NotEmpty List<@NotBlank @Size(max = 64) String> affectedVersions,
            @NotNull UUID fixedVersionId,
            @Size(max = 4000) String advisory,
            @Size(max = 64) String cveId) {
    }

    public record WindowReq(
            @NotBlank @Size(max = 128) String name,
            @NotBlank @Pattern(regexp = "FREEZE|MAINTENANCE") String kind,
            @NotNull Instant startAt,
            @NotNull Instant endAt,
            List<@Size(max = 64) String> scope,
            @NotBlank @Size(min = 2, max = 500) String reason) {
    }

    public record DeployReq(
            @NotBlank @Size(max = 64) String componentCode,
            @NotNull UUID versionId,
            @NotBlank @Pattern(regexp = "DEV|STAGING|PROD") String environment,
            @NotBlank @Pattern(regexp = "DEPLOY|ROLLBACK|HOTFIX") String action,
            @NotBlank @Pattern(regexp = "SUCCESS|FAILED") String result,
            Map<String, Object> checklist,
            UUID rollbackOf,
            @Size(max = 500) String reason,
            @NotNull Instant startedAt,
            Instant finishedAt) {
    }

    public record RollbackReq(
            @NotBlank @Size(min = 2, max = 500) String reason) {
    }

    public record NoteReq(
            @Size(max = 500) String note) {
    }
}
