package com.medassist.tech.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;

/**
 * PACS DIMSE 接口的请求/响应模型。
 *
 * <p>响应里只放对账所需的最小信息；患者标识在服务层按 {@code phi:read} 决定是否掩码。
 */
public final class PacsDimseDtos {

    private PacsDimseDtos() {
    }

    public record EchoView(String remoteAe, String host, int port, int statusCode, long latencyMs) {
    }

    /** C-FIND 条件；全部可空，空表示不加该条件。日期为 ISO（yyyy-MM-dd），服务端转 DICOM DA。 */
    public record FindStudiesReq(
            @Size(max = 64) String patientId,
            @Size(max = 64) String patientName,
            @Size(max = 32) String accessionNumber,
            LocalDate studyDateFrom,
            LocalDate studyDateTo,
            @Size(max = 16) String modality) {
    }

    public record StudyView(String studyInstanceUid, String patientId, String patientName,
                            String accessionNumber, String studyDate, String studyTime,
                            String description, String modalities, Integer seriesCount,
                            Integer instanceCount) {
    }

    public record SeriesFindReq(@NotBlank @Size(max = 64) String studyInstanceUid) {
    }

    public record SeriesView(String seriesInstanceUid, String modality, Integer seriesNumber,
                             String description, Integer instanceCount) {
    }

    public record MoveReq(@NotBlank @Size(max = 64) String studyInstanceUid,
                          @NotBlank @Size(max = 16) String destinationAe) {
    }

    public record MoveView(String studyInstanceUid, String destinationAe, int statusCode,
                           int completed, int failed, int warning) {
    }

    public record StoreView(String sopClassUid, String sopInstanceUid, long bytes, int statusCode) {
    }
}
