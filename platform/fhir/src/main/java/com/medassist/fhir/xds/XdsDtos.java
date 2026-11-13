package com.medassist.fhir.xds;

import java.time.Instant;
import java.util.List;

/**
 * XDS 对外契约（请求/响应）。
 *
 * <p>字段命名对齐 XDS.b 的 ebXML 元数据槽位，但传输用 JSON：区域平台联调时
 * 由适配器把 SOAP/ebXML 转成这里的字段，避免把信封解析散落在服务内部。
 */
public final class XdsDtos {

    private XdsDtos() {
    }

    /** ITI-41 Provide and Register（单文档子集）。 */
    public record ProvideRequest(
            String submissionSetUid,
            String sourceId,
            String patientId,
            String uniqueId,
            String logicalId,
            Integer documentVersion,
            String classCode,
            String classDisplay,
            String typeCode,
            String typeDisplay,
            String formatCode,
            String formatDisplay,
            List<String> eventCodes,
            String creationTime,
            String serviceStartTime,
            String serviceStopTime,
            String facilityTypeCode,
            String practiceSettingCode,
            String authorPerson,
            String authorInstitution,
            String authorRole,
            String title,
            String comments,
            String confidentialityCode,
            String languageCode,
            String mimeType,
            String contentBase64,
            String hashSha256,
            Integer sizeBytes,
            String campusId) {
    }

    public record ProvideResult(
            String submissionSetId,
            String submissionSetUid,
            String documentId,
            String uniqueId,
            String hashSha256,
            int sizeBytes,
            String availabilityStatus) {
    }

    /** 注册表检索条目；患者号按 {@code fhir:xds:phi} 决定明文或掩码。 */
    public record DocumentSummary(
            String documentId,
            String uniqueId,
            String logicalId,
            int documentVersion,
            String patientId,
            String patientNo,
            String classCode,
            String classDisplay,
            String typeCode,
            String typeDisplay,
            String formatCode,
            String formatDisplay,
            List<String> eventCodes,
            String title,
            String comments,
            String authorPerson,
            String authorInstitution,
            String confidentialityCode,
            String languageCode,
            String mimeType,
            int sizeBytes,
            String hashSha256,
            String availabilityStatus,
            Instant creationTime,
            Instant serviceStartTime,
            Instant serviceStopTime,
            Instant registeredAt,
            long rowVersion) {
    }

    public record QueryResult(List<DocumentSummary> items, long total, int limit, int offset) {
    }

    /** 调阅结果（只在服务内部传递，HTTP 层直接写字节流）。 */
    public record Retrieval(String uniqueId, String title, String mimeType, String hashSha256,
                            int sizeBytes, String availabilityStatus, Instant creationTime,
                            byte[] content) {
    }

    public record GenerateDischargeRequest(String visitId) {
    }

    public record GenerateLabRequest(String reportId) {
    }

    public record DeprecateRequest(String reason, Long rowVersion) {
    }

    public record XdsSummary(long total,
                             long approved,
                             long deprecated,
                             long dischargeSummaries,
                             long labReports,
                             long todayRegistered,
                             long todayRetrieved) {
    }
}
