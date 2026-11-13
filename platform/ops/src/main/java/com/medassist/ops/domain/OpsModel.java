package com.medassist.ops.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 运营域模型（与 ops_svc 表对应）。
 *
 * <p>工单的状态是"当前事实"，事件是"完整轨迹"；两者分开，避免为了看历史去解析状态变更。
 */
public final class OpsModel {

    public static final String NEW = "NEW";
    public static final String ASSIGNED = "ASSIGNED";
    public static final String IN_PROGRESS = "IN_PROGRESS";
    public static final String PENDING = "PENDING";
    public static final String RESOLVED = "RESOLVED";
    public static final String CLOSED = "CLOSED";
    public static final String REOPENED = "REOPENED";
    public static final String CANCELLED = "CANCELLED";

    public static final String PRIORITY_P1 = "P1";
    public static final String PRIORITY_P2 = "P2";
    public static final String PRIORITY_P3 = "P3";
    public static final String PRIORITY_P4 = "P4";

    private OpsModel() {
    }

    /** 营业日历。 */
    public record Calendar(
            String code,
            String name,
            String timezone,
            List<Integer> workdays,
            LocalTime startTime,
            LocalTime endTime,
            List<LocalDate> holidays,
            String status,
            long version) {
    }

    /** 服务目录。 */
    public record ServiceCatalog(
            UUID id,
            String serviceCode,
            String name,
            String domain,
            String owner,
            String supportGroup,
            String description,
            String status,
            int sortNo,
            long version) {
    }

    /** SLA 策略。 */
    public record SlaPolicy(
            UUID id,
            String serviceCode,
            String priority,
            int responseMinutes,
            int resolveMinutes,
            int escalationMinutes,
            boolean businessHours,
            String calendarCode,
            String status,
            long version) {
    }

    /** 工单。 */
    public record Ticket(
            UUID id,
            String ticketNo,
            String title,
            String description,
            String serviceCode,
            String priority,
            String reporter,
            String reporterDept,
            String assignee,
            String assigneeGroup,
            String status,
            String source,
            UUID slaPolicyId,
            Instant responseDueAt,
            Instant resolveDueAt,
            Instant firstResponseAt,
            Instant resolvedAt,
            Instant closedAt,
            Instant reopenedAt,
            int reopenCount,
            String pendingReason,
            Instant slaPausedAt,
            int slaPausedTotalMinutes,
            Integer satisfaction,
            String resolution,
            List<String> tags,
            boolean responseBreached,
            boolean resolveBreached,
            long version,
            Instant createdAt,
            Instant updatedAt,
            List<TicketEvent> events,
            String responseState,
            String resolveState) {

        /** 只替换两个计算态（列表/详情返回时按当前时间重算，不落库）。 */
        public Ticket withStates(String responseState, String resolveState) {
            return new Ticket(id, ticketNo, title, description, serviceCode, priority, reporter,
                    reporterDept, assignee, assigneeGroup, status, source, slaPolicyId,
                    responseDueAt, resolveDueAt, firstResponseAt, resolvedAt, closedAt,
                    reopenedAt, reopenCount, pendingReason, slaPausedAt, slaPausedTotalMinutes,
                    satisfaction, resolution, tags, responseBreached, resolveBreached, version,
                    createdAt, updatedAt, events, responseState, resolveState);
        }
    }

    /** 工单轨迹。 */
    public record TicketEvent(
            long id,
            UUID ticketId,
            Instant ts,
            String actor,
            String action,
            String fromStatus,
            String toStatus,
            String note,
            Map<String, Object> detail) {
    }

    /** 值班轮换。 */
    public record OnCallRotation(
            UUID id,
            String groupCode,
            String name,
            String timezone,
            LocalDate startDate,
            int cycleDays,
            List<String> members,
            LocalTime handoverTime,
            String status,
            long version) {
    }

    /** 值班班次。 */
    public record OnCallShift(
            UUID id,
            UUID rotationId,
            LocalDate shiftDate,
            String primaryUser,
            String secondaryUser,
            String note) {
    }

    /** 重大事件。 */
    public record Incident(
            UUID id,
            String incidentNo,
            String title,
            String severity,
            String status,
            String commander,
            Instant detectedAt,
            Instant startedAt,
            Instant resolvedAt,
            Instant closedAt,
            List<String> affectedServices,
            UUID ticketId,
            String summary,
            String rootCause,
            String impact,
            long version,
            Instant createdAt,
            Instant updatedAt,
            List<IncidentEvent> events) {
    }

    /** 事件轨迹。 */
    public record IncidentEvent(
            long id,
            UUID incidentId,
            Instant ts,
            String actor,
            String action,
            String fromStatus,
            String toStatus,
            String note,
            Map<String, Object> detail) {
    }

    /** 事件复盘。 */
    public record Postmortem(
            UUID id,
            UUID incidentId,
            String author,
            String status,
            List<Map<String, Object>> timeline,
            String rootCause,
            List<String> contributingFactors,
            List<Map<String, Object>> correctiveActions,
            String approvedBy,
            Instant approvedAt,
            long version,
            Instant createdAt,
            Instant updatedAt) {
    }

    /** 备份策略。 */
    public record BackupPolicy(
            UUID id,
            String code,
            String name,
            String scopeType,
            String scopeRef,
            String backupType,
            String scheduleCron,
            int retentionDays,
            String encryption,
            String storageTarget,
            int rpoMinutes,
            String owner,
            String status,
            Instant lastRunAt,
            long version,
            Instant createdAt,
            Instant updatedAt) {
    }

    /** 备份运行事实。 */
    public record BackupRun(
            UUID id,
            String runNo,
            UUID policyId,
            String policyCode,
            String backupType,
            String status,
            String idempotencyKey,
            Long sizeBytes,
            String checksum,
            String storageLocation,
            Instant startedAt,
            Instant finishedAt,
            String errorMessage,
            String reportedBy,
            long version,
            Instant createdAt,
            Instant updatedAt) {
    }

    /** 演练步骤。 */
    public record DrDrillStep(
            UUID id,
            UUID drillId,
            int stepNo,
            String name,
            String status,
            boolean required,
            String evidence,
            String note,
            String actor,
            Instant startedAt,
            Instant completedAt) {
    }

    /** 灾备演练。 */
    public record DrDrill(
            UUID id,
            String drillNo,
            String title,
            String scope,
            String environment,
            String status,
            UUID backupRunId,
            int rtoTargetMinutes,
            Integer rtoActualMinutes,
            int rpoTargetMinutes,
            Integer rpoActualMinutes,
            Integer dataLossMinutes,
            boolean consistencyVerified,
            String executor,
            String verifier,
            Instant verifiedAt,
            String evidence,
            String note,
            Instant startedAt,
            Instant completedAt,
            long version,
            Instant createdAt,
            Instant updatedAt,
            List<DrDrillStep> steps) {

        public DrDrill withSteps(List<DrDrillStep> steps) {
            return new DrDrill(id, drillNo, title, scope, environment, status, backupRunId,
                    rtoTargetMinutes, rtoActualMinutes, rpoTargetMinutes, rpoActualMinutes,
                    dataLossMinutes, consistencyVerified, executor, verifier, verifiedAt,
                    evidence, note, startedAt, completedAt, version, createdAt, updatedAt, steps);
        }
    }

    /** 运营审计。 */
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
