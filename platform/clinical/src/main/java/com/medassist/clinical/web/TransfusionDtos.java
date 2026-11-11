package com.medassist.clinical.web;

import com.medassist.clinical.domain.ClinicalModel.BloodCrossmatch;
import com.medassist.clinical.domain.ClinicalModel.TransfusionComponentUsage;
import com.medassist.clinical.domain.ClinicalModel.TransfusionIssue;
import com.medassist.clinical.domain.ClinicalModel.TransfusionObservation;
import com.medassist.clinical.domain.ClinicalModel.TransfusionPeriodStat;
import com.medassist.clinical.domain.ClinicalModel.TransfusionPrecheck;
import com.medassist.clinical.domain.ClinicalModel.TransfusionReaction;
import com.medassist.clinical.domain.ClinicalModel.TransfusionReactionStat;
import com.medassist.clinical.domain.ClinicalModel.TransfusionRecord;
import com.medassist.clinical.domain.ClinicalModel.TransfusionRequest;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * 输血闭环契约（G40）。
 *
 * <p>请求只接受业务意图；血型/成分/数量在服务端校验，状态由状态机推进。
 */
public final class TransfusionDtos {

    private TransfusionDtos() {
    }

    public record CreateRequestReq(
            UUID admissionId,
            String admissionNo,
            @NotNull UUID componentId,
            @NotNull @DecimalMin("0.5") @DecimalMax("50") BigDecimal quantity,
            @NotBlank @Size(max = 8) String bloodType,
            @NotBlank @Size(max = 16) String rh,
            @DecimalMin("20") @DecimalMax("250") BigDecimal hemoglobin,
            Integer platelet,
            @Size(max = 16) String urgency,
            @NotBlank @Size(max = 500) String indication) {
    }

    /**
     * 配血请求：主侧/次侧与抗体筛查可选；填了就必须自洽——
     * 主侧不合时不允许把整体结果写成 COMPATIBLE。
     */
    public record CrossmatchReq(
            @NotBlank @Size(max = 64) String bagNo,
            @NotBlank @Size(max = 8) String donorBloodType,
            @NotBlank @Size(max = 16) String donorRh,
            @NotBlank @Size(max = 16) String result,
            @NotBlank @Size(max = 64) String method,
            @NotNull LocalDate bagExpireAt,
            @Size(max = 500) String note,
            @Size(max = 8) String patientAbo,
            @Size(max = 16) String patientRhd,
            @Size(max = 16) String majorResult,
            @Size(max = 16) String minorResult,
            @Size(max = 16) String antibodyScreen) {
    }

    /** 输血前评估与知情同意（发血前必须具备；评估人取当前登录人）。 */
    public record PrecheckReq(
            @NotBlank @Size(max = 500) String indication,
            @DecimalMin("20") @DecimalMax("250") BigDecimal hemoglobin,
            @DecimalMin("5") @DecimalMax("80") BigDecimal hematocrit,
            Integer platelet,
            @Size(max = 200) String coagulation,
            @Size(max = 8) String patientAbo,
            @Size(max = 16) String patientRhd,
            @Size(max = 16) String antibodyScreen,
            @Size(max = 16) String hbsag,
            @Size(max = 16) String hcv,
            @Size(max = 16) String hiv,
            @Size(max = 16) String syphilis,
            @NotBlank @Size(max = 64) String consentSignedBy,
            @Size(max = 500) String note) {
    }

    /** 发血核对：发血者取当前登录人，核对者与取血人必填。 */
    public record IssueReq(
            @NotBlank @Size(max = 64) String checkedBy,
            @NotBlank @Size(max = 64) String fetchedBy,
            @Size(max = 16) String appearance,
            Boolean bagIntact) {
    }

    /** 输注观察：观察时间缺省取当前时间；反应标记由观察者判断。 */
    public record ObservationReq(
            java.time.Instant observedAt,
            @DecimalMin("30") @DecimalMax("45") BigDecimal temperature,
            Integer pulse,
            Integer systolic,
            Integer diastolic,
            Integer dripRate,
            Boolean reactionFlag,
            @Size(max = 500) String note) {
    }

    public record StartTransfusionReq(
            @NotBlank @Size(max = 64) String checkerA,
            @NotBlank @Size(max = 64) String checkerB,
            @DecimalMin("30") @DecimalMax("45") BigDecimal startTemperature,
            Integer startPulse,
            Integer startSystolic,
            Integer startDiastolic) {
    }

    public record CompleteTransfusionReq(
            @DecimalMin("0.01") BigDecimal volumeMl,
            @DecimalMin("30") @DecimalMax("45") BigDecimal endTemperature,
            Integer endPulse,
            Integer endSystolic,
            Integer endDiastolic) {
    }

    public record ReactionReq(
            @NotBlank @Size(max = 16) String severity,
            @NotBlank @Size(max = 1000) String symptoms,
            @Size(max = 1000) String actionTaken,
            @Size(max = 32) String reactionType,
            @Size(max = 16) String reportedTo) {
    }

    public record CancelReq(@Size(max = 500) String reason) {
    }

    public record RequestSummaryView(UUID id, String requestNo, UUID patientId, String patientNo,
                                     String patientName, String wardName, String bedNo,
                                     String componentName, BigDecimal quantity, String unit,
                                     String bloodType, String rh, String urgency, String status,
                                     String requestedBy, Instant requestedAt,
                                     String completedBy, Instant completedAt) {
    }

    public record RequestView(TransfusionRequest request, List<BloodCrossmatch> crossmatches,
                              TransfusionRecord record, TransfusionReaction reaction,
                              TransfusionPrecheck precheck, TransfusionIssue issue,
                              List<TransfusionObservation> observations) {
    }

    public record BoardView(long requested, long crossmatched, long issued, long transfusing,
                            long completedToday, long reactionsToday, long totalToday) {
    }

    /** 输血报表：期间质量指标 + 成分用量 + 不良反应分布。 */
    public record StatsView(java.time.Instant from, java.time.Instant to,
                            TransfusionPeriodStat period,
                            List<TransfusionComponentUsage> components,
                            List<TransfusionReactionStat> reactions) {
    }
}
