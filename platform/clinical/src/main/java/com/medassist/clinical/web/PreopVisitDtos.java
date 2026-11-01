package com.medassist.clinical.web;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.Instant;

/** 术前访视与麻醉评估请求体。 */
public final class PreopVisitDtos {

    private PreopVisitDtos() {
    }

    /**
     * 访视单（首次保存不带 version；再次保存必须带 version 做 CAS）。
     *
     * <p>结构化字段用正则约束取值范围：ASA、Mallampati、麻醉方式、结论四类枚举必须与数据库 CHECK 一致，
     * 否则会在落库时才报错（对使用者不友好）。
     */
    public record PreopVisitReq(
            Instant visitedAt,
            @Size(max = 64) String visitedBy,
            @Pattern(regexp = "GENERAL|REGIONAL|LOCAL|SEDATION|NONE", message = "麻醉方式不合法")
            String anesthesiaPlan,
            @Pattern(regexp = "I|II|III|IV|V|VI", message = "ASA 分级不合法") String asaGrade,
            @Pattern(regexp = "I|II|III|IV", message = "Mallampati 分级不合法") String mallampati,
            @Size(max = 500) String airwayNote,
            @Size(max = 500) String dentition,
            @Size(max = 1000) String anesthesiaHistory,
            @Size(max = 1000) String allergyHistory,
            @Min(0) @Max(72) Integer fastingSolidHours,
            @Min(0) @Max(24) Integer fastingClearHours,
            @Pattern(regexp = "FIT|FIT_WITH_CAUTION|UNFIT", message = "麻醉结论不合法")
            String conclusion,
            @Size(max = 2000) String riskNote,
            Long version) {
    }
}
