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
            /** 目标 Cell（医院租户）；为空表示平台级部署（ADR-001 之前的历史语义）。 */
            @Size(max = 32) @Pattern(regexp = "[a-z0-9][a-z0-9-]{1,31}")
            String tenantCode,
            /** 灰度批次标签（PILOT/CANARY_10/ALL 等），仅用于演练与审计追溯。 */
            @Size(max = 32) String batchLabel,
            @NotBlank @Pattern(regexp = "DEPLOY|ROLLBACK|HOTFIX") String action,
            @NotBlank @Pattern(regexp = "SUCCESS|FAILED") String result,
            Map<String, Object> checklist,
            UUID rollbackOf,
            @Size(max = 500) String reason,
            @NotNull Instant startedAt,
            Instant finishedAt) {
    }

    /** Cell 内单个组件的发布对齐结论。 */
    public record CellComponentStatus(
            String componentCode,
            String targetVersion,
            String deployedVersion,
            /** UP_TO_DATE / BEHIND / NEVER_DEPLOYED */
            String status,
            Instant deployedAt) {
    }

    /**
     * 某家医院相对"最近一次已发布列车"的对齐情况。
     *
     * <p>{@code state} 是页面直接使用的结论：
     * <ul>
     *   <li>ALIGNED：全部组件与列车一致；</li>
     *   <li>BEHIND：存在落后/未部署组件（黄）；</li>
     *   <li>FAILED：最近一次部署失败（红，优先于落后）；</li>
     *   <li>UNKNOWN：尚无已发布列车，无法判定。</li>
     * </ul>
     */
    public record CellReleaseStatus(
            String tenantCode,
            UUID trainId,
            String trainCode,
            Instant trainReleasedAt,
            String state,
            int totalComponents,
            int behindComponents,
            int failedComponents,
            List<CellComponentStatus> components,
            Instant generatedAt) {
    }

    public record RollbackReq(
            @NotBlank @Size(min = 2, max = 500) String reason) {
    }

    public record NoteReq(
            @Size(max = 500) String note) {
    }
}
