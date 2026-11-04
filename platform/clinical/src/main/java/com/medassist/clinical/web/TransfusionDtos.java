package com.medassist.clinical.web;

import com.medassist.clinical.domain.ClinicalModel.BloodCrossmatch;
import com.medassist.clinical.domain.ClinicalModel.TransfusionReaction;
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

    public record CrossmatchReq(
            @NotBlank @Size(max = 64) String bagNo,
            @NotBlank @Size(max = 8) String donorBloodType,
            @NotBlank @Size(max = 16) String donorRh,
            @NotBlank @Size(max = 16) String result,
            @NotBlank @Size(max = 64) String method,
            @NotNull LocalDate bagExpireAt,
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
            @Size(max = 1000) String actionTaken) {
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
                              TransfusionRecord record, TransfusionReaction reaction) {
    }

    public record BoardView(long requested, long crossmatched, long issued, long transfusing,
                            long completedToday, long reactionsToday, long totalToday) {
    }
}
