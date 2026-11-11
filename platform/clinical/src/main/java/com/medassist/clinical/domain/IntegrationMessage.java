package com.medassist.clinical.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * 出站集成消息（第 257 批）。
 *
 * <p>只表达"一条要发给对端系统的报文及其投递状态"，不感知业务语义；
 * 业务侧通过 {@code business_key} 关联患者号/就诊号/结算号，便于按业务号对账。
 */
public record IntegrationMessage(UUID id, String systemCode, String messageType,
                                 String businessKey, String payload, String status,
                                 int attempts, Instant nextRetryAt, String lastError,
                                 String externalId, String createdBy, Instant lockedAt,
                                 Instant createdAt, long version) {

    public static final String PENDING = "PENDING";
    public static final String SENDING = "SENDING";
    public static final String ACKED = "ACKED";
    public static final String FAILED = "FAILED";
    public static final String DEAD = "DEAD";

    /** 只有待发/失败的消息可以人工重投；SENDING/ACKED 不允许。 */
    public boolean retryable() {
        return FAILED.equals(status) || DEAD.equals(status);
    }
}
