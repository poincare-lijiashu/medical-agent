package com.medassist.ops.web;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 运营 API 请求体。
 *
 * <p>报告人（reporter）不来自请求体，而来自网关注入的 {@code X-Actor}：
 * 否则任何人都能冒名替别人建单，工单的"谁报的"就失去意义。
 */
public final class OpsDtos {

    private OpsDtos() {
    }

    public record ServiceReq(
            @NotBlank @Size(max = 64) String serviceCode,
            @NotBlank @Size(max = 128) String name,
            @NotBlank @Pattern(regexp = "PLATFORM|INFRA|SECURITY|INTEGRATION|OTHER") String domain,
            @Size(max = 64) String owner,
            @NotBlank @Size(max = 64) String supportGroup,
            @Size(max = 500) String description,
            @Pattern(regexp = "ACTIVE|INACTIVE") String status,
            @Min(1) @Max(9999) Integer sortNo) {
    }

    public record CalendarReq(
            @NotBlank @Size(max = 64) String code,
            @NotBlank @Size(max = 128) String name,
            @Size(max = 64) String timezone,
            @NotEmpty List<@Min(1) @Max(7) Integer> workdays,
            LocalTime startTime,
            LocalTime endTime,
            List<LocalDate> holidays) {
    }

    public record SlaPolicyReq(
            @NotBlank @Size(max = 64) String serviceCode,
            @NotBlank @Pattern(regexp = "P1|P2|P3|P4") String priority,
            @Min(1) @Max(525600) int responseMinutes,
            @Min(1) @Max(525600) int resolveMinutes,
            @Min(0) @Max(525600) Integer escalationMinutes,
            Boolean businessHours,
            @Size(max = 64) String calendarCode,
            @Pattern(regexp = "ACTIVE|INACTIVE") String status) {
    }

    public record TicketCreateReq(
            @NotBlank @Size(max = 200) String title,
            @Size(max = 4000) String description,
            @NotBlank @Size(max = 64) String serviceCode,
            @NotBlank @Pattern(regexp = "P1|P2|P3|P4") String priority,
            @Size(max = 64) String reporterDept,
            @Pattern(regexp = "PHONE|EMAIL|PORTAL|MONITOR|ONCALL|OTHER") String source,
            List<@Size(max = 32) String> tags) {
    }

    public record AssignReq(
            @NotBlank @Size(max = 64) String assignee,
            @Size(max = 64) String assigneeGroup) {
    }

    public record NoteReq(
            @NotBlank @Size(min = 2, max = 2000) String note) {
    }

    public record PendingReq(
            @NotBlank @Size(min = 2, max = 500) String reason) {
    }

    public record ResolveReq(
            @NotBlank @Size(min = 2, max = 2000) String resolution) {
    }

    public record CloseReq(
            @Min(1) @Max(5) Integer satisfaction,
            @Size(max = 500) String note) {
    }

    public record ReopenReq(
            @NotBlank @Size(min = 2, max = 500) String reason) {
    }

    public record CancelReq(
            @NotBlank @Size(min = 2, max = 500) String reason) {
    }

    public record PriorityReq(
            @NotBlank @Pattern(regexp = "P1|P2|P3|P4") String priority,
            @NotBlank @Size(min = 2, max = 500) String reason) {
    }

    // ------------------------------------------------------------------ 值班

    public record RotationReq(
            @NotBlank @Size(max = 64) String groupCode,
            @NotBlank @Size(max = 128) String name,
            @Size(max = 64) String timezone,
            @NotNull LocalDate startDate,
            @Min(1) @Max(90) int cycleDays,
            @NotEmpty List<@NotBlank @Size(max = 64) String> members,
            LocalTime handoverTime) {
    }

    public record ShiftOverrideReq(
            @NotBlank @Size(max = 64) String primaryUser,
            @Size(max = 64) String secondaryUser,
            @NotBlank @Size(min = 2, max = 500) String reason) {
    }

    // ------------------------------------------------------------------ 事件

    public record IncidentCreateReq(
            @NotBlank @Size(max = 200) String title,
            @NotBlank @Pattern(regexp = "SEV1|SEV2|SEV3|SEV4") String severity,
            @Size(max = 64) String commander,
            @NotEmpty List<@NotBlank @Size(max = 64) String> affectedServices,
            UUID ticketId,
            @Size(max = 2000) String summary,
            @Size(max = 2000) String impact) {
    }

    public record IncidentStatusReq(
            @NotBlank @Pattern(regexp = "OPEN|MITIGATING|MONITORING|RESOLVED|CLOSED|REOPENED")
            String status,
            @NotBlank @Size(min = 2, max = 1000) String note) {
    }

    public record IncidentCommentReq(
            @NotBlank @Size(min = 2, max = 2000) String note) {
    }

    public record CommanderReq(
            @NotBlank @Size(max = 64) String commander,
            @NotBlank @Size(min = 2, max = 500) String reason) {
    }

    public record PostmortemReq(
            List<Map<String, Object>> timeline,
            @Size(max = 4000) String rootCause,
            List<@Size(max = 200) String> contributingFactors,
            List<Map<String, Object>> correctiveActions) {
    }

    public record PostmortemApproveReq(
            @Size(max = 1000) String note) {
    }

    // ------------------------------------------------------------------ 备份与灾备（§297）

    public record BackupPolicyReq(
            @NotBlank @Size(max = 64) String code,
            @NotBlank @Size(max = 128) String name,
            @NotBlank @Pattern(regexp = "ALL|COMPONENT|SCHEMA") String scopeType,
            @Size(max = 128) String scopeRef,
            @NotBlank @Pattern(regexp = "FULL|INCREMENTAL|WAL|LOGICAL") String backupType,
            @NotBlank @Size(max = 64) String scheduleCron,
            @Min(1) @Max(3650) Integer retentionDays,
            @Pattern(regexp = "NONE|AES256|KMS") String encryption,
            @NotBlank @Size(max = 512) String storageTarget,
            @Min(1) @Max(43200) int rpoMinutes,
            @NotBlank @Size(max = 64) String owner,
            @Pattern(regexp = "ACTIVE|INACTIVE") String status) {
    }

    /**
     * 备份运行上报。
     *
     * <p>{@code idempotencyKey} 由备份平台生成（同一作业重试必须复用），
     * 服务端据此保证"重试不重复记账"。
     */
    public record BackupRunReq(
            @NotBlank @Size(max = 64) String policyCode,
            @NotBlank @Pattern(regexp = "FULL|INCREMENTAL|WAL|LOGICAL") String backupType,
            @NotBlank @Pattern(regexp = "RUNNING|SUCCESS|FAILED") String status,
            @NotBlank @Size(max = 128) String idempotencyKey,
            @NotNull Instant startedAt,
            Instant finishedAt,
            @Min(0) Long sizeBytes,
            @Size(max = 128) String checksum,
            @Size(max = 512) String storageLocation,
            @Size(max = 1000) String errorMessage) {
    }

    public record DrDrillStepReq(
            @NotNull @Min(1) @Max(99) Integer stepNo,
            @NotBlank @Size(max = 200) String name,
            Boolean required) {
    }

    public record DrDrillCreateReq(
            @NotBlank @Size(max = 200) String title,
            @NotBlank @Size(max = 64) String scope,
            @NotBlank @Pattern(regexp = "PROD|STAGING|DRILL") String environment,
            UUID backupRunId,
            @Min(1) @Max(43200) int rtoTargetMinutes,
            @Min(1) @Max(43200) int rpoTargetMinutes,
            List<@NotNull DrDrillStepReq> steps,
            @Size(max = 1000) String note) {
    }

    public record DrDrillStepUpdateReq(
            @NotBlank @Pattern(regexp = "PENDING|PASSED|FAILED|SKIPPED") String status,
            @Size(max = 1000) String evidence,
            @Size(max = 1000) String note) {
    }

    public record DrDrillCompleteReq(
            @Min(0) @Max(43200) int rtoActualMinutes,
            @Min(0) @Max(43200) int rpoActualMinutes,
            @Min(0) @Max(43200) Integer dataLossMinutes,
            @NotNull Boolean consistencyVerified,
            @NotBlank @Size(max = 1000) String evidence,
            @Size(max = 1000) String note) {
    }

    public record DrDrillReasonReq(
            @NotBlank @Size(min = 2, max = 1000) String reason) {
    }

    // ------------------------------------------------------------------ 实施交付与培训（§298）

    public record ImplTemplateReq(
            @NotBlank @Size(max = 64) String code,
            @NotBlank @Size(max = 128) String name,
            @Size(max = 1000) String description,
            @NotEmpty List<@Valid ImplPhaseReq> phases) {
    }

    public record ImplPhaseReq(
            @Min(1) @Max(20) int phaseNo,
            @NotBlank @Size(max = 128) String name,
            @Min(1) @Max(100) int weight,
            @Size(max = 500) String description,
            @NotEmpty List<@Valid ImplTaskReq> tasks) {
    }

    public record ImplTaskReq(
            @Min(1) @Max(999) int taskNo,
            @NotBlank @Size(max = 200) String title,
            @Size(max = 1000) String description,
            Boolean required,
            @Size(max = 64) String ownerRole,
            @Size(max = 500) String evidenceHint) {
    }

    public record ImplProjectCreateReq(
            @NotBlank @Size(max = 64) String templateCode,
            @Size(max = 64) String tenantCode,
            @NotBlank @Size(max = 128) String hospitalName,
            @Size(max = 64) String owner,
            LocalDate startDate,
            LocalDate targetDate,
            List<@Size(max = 64) String> requiredCourseCodes) {
    }

    public record ImplTaskUpdateReq(
            @NotBlank @Pattern(regexp = "PENDING|IN_PROGRESS|BLOCKED|DONE") String status,
            @Size(max = 64) String assignee,
            LocalDate dueDate,
            @Size(max = 1000) String evidence,
            @Size(max = 1000) String blockedReason,
            @Size(max = 1000) String note) {
    }

    public record ImplWaiverReq(
            @NotBlank @Size(min = 2, max = 1000) String reason) {
    }

    public record ImplApprovalReq(
            @Size(max = 1000) String note) {
    }

    public record ImplStatusReq(
            @NotBlank @Pattern(regexp = "ACTIVE|INACTIVE") String status) {
    }

    public record ImplAcceptReq(
            @NotBlank @Size(min = 2, max = 1000) String evidence,
            @Size(max = 1000) String note) {
    }

    public record ImplCancelReq(
            @NotBlank @Size(min = 2, max = 1000) String reason) {
    }

    public record ImplCourseReq(
            @NotBlank @Size(max = 64) String code,
            @NotBlank @Size(max = 128) String title,
            @NotBlank @Pattern(regexp = "ROLE|SYSTEM|PROCESS") String category,
            Boolean required,
            @Min(1) @Max(1440) Integer durationMinutes,
            @Size(max = 1000) String description,
            @Pattern(regexp = "ACTIVE|INACTIVE") String status,
            List<@Valid ImplQuestionReq> questions) {
    }

    public record ImplQuestionReq(
            @Min(1) @Max(999) int questionNo,
            @NotBlank @Size(max = 1000) String stem,
            @NotEmpty Map<@NotBlank @Size(max = 16) String, @NotBlank @Size(max = 500) String> options,
            @NotBlank @Size(max = 16) String answerKey,
            @Min(1) @Max(100) Integer score) {
    }

    public record ImplExamReq(
            @NotBlank @Size(max = 64) String courseCode,
            @Min(1) @Max(100) Integer passScore,
            @Size(max = 200) String title) {
    }

    public record ImplAttemptReq(
            @NotBlank @Size(max = 64) String trainee,
            @NotEmpty Map<@NotBlank @Size(max = 64) String, @NotBlank @Size(max = 16) String> answers) {
    }
}
