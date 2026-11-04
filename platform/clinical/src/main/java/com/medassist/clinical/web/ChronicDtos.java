package com.medassist.clinical.web;

import com.medassist.clinical.domain.ClinicalModel.ChronicFollowup;
import com.medassist.clinical.domain.ClinicalModel.ChronicFollowupPlan;
import com.medassist.clinical.domain.ClinicalModel.ChronicFollowupTask;
import com.medassist.clinical.domain.ClinicalModel.ChronicRecord;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * 随访与慢病契约（G38）。
 *
 * <p>请求只接受业务意图；风险等级、任务状态、下次随访日期与随访记录均由服务端状态机推进。
 */
public final class ChronicDtos {

    private ChronicDtos() {
    }

    public record CreateRecordReq(
            @NotNull UUID patientId,
            @NotNull UUID diseaseId,
            @NotNull LocalDate diagnosisDate,
            @Size(max = 16) String riskLevel,
            UUID managingDeptId,
            @Size(max = 64) String manager,
            @Size(max = 500) String note) {
    }

    public record UpdateRiskReq(@NotBlank @Size(max = 16) String riskLevel) {
    }

    public record CloseRecordReq(@Size(max = 500) String reason) {
    }

    public record CreatePlanReq(int intervalDays, LocalDate nextDate) {
    }

    public record StopPlanReq(@Size(max = 500) String reason) {
    }

    public record CompleteTaskReq(
            LocalDate followupDate,
            @NotBlank @Size(max = 16) String method,
            Integer systolic,
            Integer diastolic,
            BigDecimal fastingGlucose,
            BigDecimal weight,
            @Size(max = 1000) String symptoms,
            @Size(max = 16) String medicationAdherence,
            @Size(max = 1000) String advice) {
    }

    public record RecordSummaryView(UUID id, String recordNo, UUID patientId, String patientNo,
                                    String patientName, String gender, LocalDate birthDate,
                                    String diseaseCode, String diseaseName, String riskLevel,
                                    String status, String manager, Instant createdAt,
                                    LocalDate lastFollowupDate, int followupCount,
                                    LocalDate nextDueDate) {
    }

    public record RecordView(ChronicRecord record, ChronicFollowupPlan plan,
                             ChronicFollowupTask pendingTask, List<ChronicFollowup> followups) {
    }

    public record TaskView(UUID id, String taskNo, UUID chronicId, UUID patientId,
                           String patientNo, String patientName, String diseaseName,
                           LocalDate dueDate, String status, boolean overdue,
                           Instant completedAt, Instant createdAt, long version) {
    }

    public record FollowupView(UUID id, String followupNo, UUID taskId, UUID chronicId,
                               UUID patientId, String patientNo, String patientName,
                               LocalDate followupDate, String method, Integer systolic,
                               Integer diastolic, BigDecimal fastingGlucose, BigDecimal weight,
                               String symptoms, String medicationAdherence, String advice,
                               String createdBy, Instant createdAt) {
    }

    public record BoardView(long activeRecords, long highRisk, long pendingTasks,
                            long overdueTasks, long completedToday, long completedThisMonth) {
    }
}
