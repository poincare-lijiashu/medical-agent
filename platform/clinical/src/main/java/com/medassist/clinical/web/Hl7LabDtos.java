package com.medassist.clinical.web;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.UUID;

/**
 * HL7 v2 联调测试台请求体（§301）。
 */
public final class Hl7LabDtos {

    private Hl7LabDtos() {
    }

    public record EndpointReq(
            @NotBlank @Pattern(regexp = "[A-Za-z0-9:_-]{2,64}") String code,
            @NotBlank @Size(max = 128) String name,
            @NotBlank @Size(max = 255) String host,
            @Min(1) @Max(65535) int port,
            @Min(500) @Max(60000) Integer timeoutMs,
            @Size(max = 1000) String description,
            @Pattern(regexp = "ACTIVE|INACTIVE") String status) {
    }

    public record CaseReq(
            @NotBlank @Pattern(regexp = "[A-Za-z0-9:_-]{2,64}") String code,
            @NotBlank @Size(max = 128) String name,
            @NotBlank @Size(max = 16) String messageType,
            @NotBlank @Size(max = 16) String triggerEvent,
            @NotBlank @Size(max = 20000) String sampleMessage,
            @NotBlank @Pattern(regexp = "AA|AE|AR") String expectedAckCode,
            List<@Size(max = 16) String> requiredSegments,
            @Pattern(regexp = "ACTIVE|INACTIVE") String status) {
    }

    public record StatusReq(
            @NotBlank @Pattern(regexp = "ACTIVE|INACTIVE") String status) {
    }

    public record RunReq(
            @NotNull UUID caseId,
            @NotNull UUID endpointId) {
    }
}
