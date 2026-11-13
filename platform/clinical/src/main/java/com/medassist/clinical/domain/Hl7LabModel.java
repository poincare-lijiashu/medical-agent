package com.medassist.clinical.domain;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * HL7 v2 联调测试台模型（§301，对应 clinical_svc.hl7_* 表）。
 */
public final class Hl7LabModel {

    public static final String PASSED = "PASSED";
    public static final String FAILED = "FAILED";
    public static final String ERROR = "ERROR";

    private Hl7LabModel() {
    }

    /** 联调端点。 */
    public record Endpoint(
            UUID id,
            String code,
            String name,
            String host,
            int port,
            int timeoutMs,
            String description,
            String status,
            long version,
            String createdBy,
            Instant createdAt,
            Instant updatedAt) {
    }

    /** 测试用例。 */
    public record Case(
            UUID id,
            String code,
            String name,
            String messageType,
            String triggerEvent,
            String sampleMessage,
            String expectedAckCode,
            List<String> requiredSegments,
            String status,
            long version,
            String createdBy,
            Instant createdAt,
            Instant updatedAt) {
    }

    /** 执行记录。 */
    public record Run(
            UUID id,
            String runNo,
            UUID caseId,
            String caseCode,
            UUID endpointId,
            String endpointCode,
            String status,
            String ackCode,
            String ackControlId,
            String ackMessage,
            String requestExcerpt,
            String responseExcerpt,
            String errorMessage,
            String validation,
            int durationMs,
            String triggeredBy,
            Instant startedAt,
            Instant finishedAt) {
    }

    /** 看板。 */
    public record Dashboard(
            Instant generatedAt,
            long endpoints,
            long cases,
            long runs24h,
            long passed24h,
            long failed24h,
            long error24h,
            double passRate24h) {
    }
}
