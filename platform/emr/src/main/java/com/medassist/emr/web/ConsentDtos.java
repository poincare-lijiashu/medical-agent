package com.medassist.emr.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.UUID;

/**
 * 知情同意书契约（G44）。
 */
public final class ConsentDtos {

    private ConsentDtos() {
    }

    public record CreateTemplateReq(@NotBlank @Size(max = 64) String templateCode,
                                    @NotBlank @Size(max = 128) String name,
                                    @NotBlank @Size(max = 64) String procedureType,
                                    @NotBlank @Size(max = 20000) String content,
                                    @Size(max = 500) String remark) {
    }

    public record TemplateView(UUID id, String templateCode, String name, String procedureType,
                               String content, int templateVersion, String status,
                               String createdBy, Instant createdAt, Instant updatedAt,
                               long version) {
    }

    public record CreateConsentReq(@NotNull UUID patientId, @NotNull UUID visitId,
                                   @NotBlank @Size(max = 64) String templateCode,
                                   @NotBlank @Size(max = 256) String procedureName) {
    }

    /** 签署：签署人/关系由服务层校验，以返回同意书专用错误码。 */
    public record SignReq(@Size(max = 64) String patientSigner,
                          @Size(max = 32) String patientRelation,
                          @Size(max = 64) String witnessName,
                          @Size(max = 64) String doctorName,
                          Boolean caSign,
                          Long version) {
    }

    /** 撤销原因由服务层校验，保证返回 CONSENT_REVOKE_REASON_REQUIRED。 */
    public record RevokeReq(@Size(max = 500) String reason, Long version) {
    }

    public record ConsentView(UUID id, String consentNo, UUID templateId, String templateCode,
                              int templateVersion, String templateName, UUID patientId,
                              String patientNo, String patientName, UUID visitId,
                              String procedureName, String contentText, String contentHash,
                              String status, String patientSigner, String patientRelation,
                              String witnessName, String doctorName, Instant signedAt,
                              String signMethod, String certificateSerial, String signatureValue,
                              String algorithm, String revokeReason, String revokedBy,
                              Instant revokedAt, String createdBy, Instant createdAt,
                              Instant updatedAt, long version) {
    }

    public record BoardView(long drafts, long signedToday, long revokedTotal) {
    }

    public record VersionReq(@NotNull Long version) {
    }

    public record VerifyView(boolean hashValid, Boolean signatureValid, String status) {
    }
}
