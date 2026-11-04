package com.medassist.clinical.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.UUID;

/**
 * 收费票据契约（G14）。
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
}
