package com.medassist.clinical.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * 入站集成消息（第 258 批）。
 *
 * <p>{@code (systemCode, externalId)} 是幂等键；{@code payloadHash} 用来区分
 * "同一消息重复推送"与"同一控制号却换了内容"（后者必须拒绝，不能静默覆盖）。
 */
public record IntegrationInboundMessage(UUID id, String systemCode, String messageType,
                                        String externalId, String businessKey, String payload,
                                        String payloadHash, String status, String error,
                                        String receivedBy, Instant processedAt,
                                        Instant createdAt, long version) {

    public static final String RECEIVED = "RECEIVED";
    public static final String PROCESSED = "PROCESSED";
    public static final String FAILED = "FAILED";
}
