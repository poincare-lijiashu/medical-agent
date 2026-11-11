package com.medassist.emr.web;

import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.UUID;

/**
 * CA 电子签名契约（G41）。
 */
public final class CaDtos {

    private CaDtos() {
    }

    /** 撤销原因由服务层校验，保证返回 CA 域专用错误码。 */
    public record RevokeReq(@Size(max = 500) String reason) {
    }

    public record CaSignatureView(UUID id, UUID signatureId, UUID recordId, int revision,
                                  String signer, String signType, String contentHash,
                                  String provider, String certificateSerial, String algorithm,
                                  String signatureValue, Instant signedAt, String status,
                                  int verifyCount, Instant lastVerifiedAt,
                                  Boolean lastVerifyResult, String revokeReason, String revokedBy,
                                  Instant revokedAt, Instant createdAt) {
    }

    public record BoardView(long signedToday, long validTotal, long revokedTotal,
                            long verifyFailuresToday, String providerMode) {
    }

    public record ModeView(String mode) {
    }

    /** RFC 3161 时间戳证据：令牌原文是离线复核的唯一依据，不能只给时间。 */
    public record TimestampView(String tsaUrl, Instant genTime, String token, Instant createdAt) {
    }
}
