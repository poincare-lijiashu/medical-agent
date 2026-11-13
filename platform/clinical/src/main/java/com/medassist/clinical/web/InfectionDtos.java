package com.medassist.clinical.web;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;
import java.util.UUID;

/** 传染病上报请求契约。 */
public final class InfectionDtos {

    private InfectionDtos() {
    }

    public record SubmitReq(
            @NotBlank @Size(min = 2, max = 64) String reporter,
            @Size(max = 64) String reporterDept,
            @NotNull @Min(1) Long version) {
    }

    public record AcceptReq(@NotBlank @Size(min = 2, max = 64) String receiptNo,
                            @NotNull @Min(1) Long version) {
    }

    public record ReasonReq(@NotBlank @Size(min = 2, max = 256) String reason,
                            @NotNull @Min(1) Long version) {
    }

    /** 手工补录：病种编码必须存在于法定目录，ICD 仅作展示。 */
    public record ManualCreateReq(
            @NotNull UUID visitId,
            @NotBlank @Size(max = 16) String diseaseCode,
            LocalDate onsetDate,
            @Size(max = 32) String icdCode,
            @Size(max = 128) String icdName,
            @Size(max = 64) String reporterDept) {
    }
}
