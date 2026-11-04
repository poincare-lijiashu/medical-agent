package com.medassist.clinical.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.UUID;

/**
 * 收费票据契约（G14 开票/作废；G21 电子票据推送）。
 *
 * <p>开票只接受收费单 id：金额与支付方式由服务端按已结算收费单快照，
 * 避免客户端传入与收费单不一致的票据金额。
 */
public final class ReceiptDtos {

    private ReceiptDtos() {
    }

    public record IssueReceiptReq(@NotNull UUID chargeId) {
    }

    public record VoidReceiptReq(@NotBlank @Size(max = 200) String reason) {
    }

    /** 电子票据推送结果（G21 / G24）。 */
    public record ReceiptPushView(UUID id, UUID receiptId, String platform, String vendor,
                                  String messageType, String templateVersion,
                                  UUID originalPushId, String status, int attempts,
                                  String externalNo, String lastError, Instant pushedAt) {
    }

    /** 红冲电子票据：必须写明原因，红冲报文会携带原平台票据号。 */
    public record RedFlushReq(@NotBlank @Size(max = 200) String reason) {
    }

    /** 电子票据推送期间汇总（G24）。 */
    public record ReceiptPushSummaryView(long total, long sent, long failed, long pending,
                                         long redFlushCount, java.math.BigDecimal sentAmount) {
    }
}
