package com.medassist.masterdata.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/**
 * 数据质量 API 请求体（§299）。
 */
public final class DataQualityDtos {

    private DataQualityDtos() {
    }

    public record RuleReq(
            @NotBlank @Pattern(regexp = "[A-Za-z0-9:_-]{2,64}") String code,
            @NotBlank @Size(max = 128) String name,
            @NotBlank @Pattern(regexp = "MASTER_DATA|METRIC|CLINICAL|INTEGRATION|FINANCE")
            String domain,
            @NotBlank @Pattern(regexp = "COMPLETENESS|UNIQUENESS|TIMELINESS|CONSISTENCY|"
                    + "REFERENTIAL|VALIDITY|VOLUME") String dimension,
            @NotBlank @Size(max = 4000) String sqlText,
            @NotBlank @Pattern(regexp = "LTE|GTE|EQ|BETWEEN") String comparator,
            @NotNull BigDecimal threshold,
            BigDecimal threshold2,
            @NotBlank @Pattern(regexp = "PERCENT|COUNT|MINUTES") String unit,
            @NotBlank @Pattern(regexp = "BLOCKER|HIGH|MEDIUM|LOW") String severity,
            @NotBlank @Size(max = 64) String owner,
            @Pattern(regexp = "ACTIVE|INACTIVE") String status) {
    }

    public record IssueAckReq(
            @Size(max = 1000) String note) {
    }

    public record IssueResolveReq(
            @NotBlank @Size(min = 2, max = 2000) String resolution) {
    }

    public record IssueWaiveReq(
            @NotBlank @Size(min = 2, max = 1000) String reason) {
    }
}
