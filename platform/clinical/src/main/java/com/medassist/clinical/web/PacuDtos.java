package com.medassist.clinical.web;

import com.medassist.clinical.domain.ClinicalModel.PacuObservation;
import com.medassist.clinical.domain.ClinicalModel.PacuStay;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/** 麻醉复苏（PACU）请求体与视图。 */
public final class PacuDtos {

    private PacuDtos() {
    }

    /** 入复苏室：可指定床号与实际入室时间（缺省取当前时刻）。 */
    public record AdmitReq(
            @Size(max = 32) String bedNo,
            Instant admittedAt) {
    }

    /**
     * 复苏观察：Aldrete 五项必填（各 0–2 分），生命体征可选。
     *
     * <p>总分由服务端求和，不接受客户端传入——分值口径只能有一处实现。
     */
    public record ObservationReq(
            @NotNull Instant recordedAt,
            @NotNull @Min(0) @Max(2) Integer activity,
            @NotNull @Min(0) @Max(2) Integer respiration,
            @NotNull @Min(0) @Max(2) Integer circulation,
            @NotNull @Min(0) @Max(2) Integer consciousness,
            @NotNull @Min(0) @Max(2) Integer oxygenation,
            @Min(0) @Max(300) Integer heartRate,
            @Min(0) @Max(300) Integer sbp,
            @Min(0) @Max(200) Integer dbp,
            @Min(0) @Max(100) Integer spo2,
            @DecimalMin("25.0") @DecimalMax("45.0") BigDecimal temperature,
            @Size(max = 256) String note) {
    }

    /** 出复苏室：出室时间缺省取当前时刻，去向必须显式给出。 */
    public record DischargeReq(
            @NotNull Long version,
            Instant dischargedAt,
            @NotBlank @Pattern(regexp = "(?i)WARD|ICU|DISCHARGE",
                    message = "出室去向只能是 WARD / ICU / DISCHARGE") String destination) {
    }

    /** 复苏记录签名（需版本号 CAS）。 */
    public record SignReq(@NotNull Long version) {
    }

    /** 复苏记录视图（停留 + 观察时间线）。 */
    public record PacuView(PacuStay stay, List<PacuObservation> observations) {
    }
}
