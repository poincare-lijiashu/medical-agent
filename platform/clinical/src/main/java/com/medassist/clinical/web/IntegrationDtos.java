package com.medassist.clinical.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 集成消息台账 DTO（第 257 批）。
 */
public final class IntegrationDtos {

    private IntegrationDtos() {
    }

    /**
     * 投递一条出站消息。
     *
     * @param systemCode  HIS / LIS / PACS / EMR / INSURANCE / CA
     * @param messageType 业务报文类型（如 ADT_A01 / ORM_O01 / SETTLEMENT / SIGN）
     * @param businessKey 业务号（患者号/就诊号/结算号），用于对账
     * @param payload     原始报文（HL7 v2 / JSON / XML），不做结构校验
     */
    public record IntegrationEnqueueReq(
            @NotBlank @Size(max = 32) String systemCode,
            @NotBlank @Size(max = 64) String messageType,
            @Size(max = 128) String businessKey,
            @NotBlank @Size(max = 100_000) String payload) {
    }

    /**
     * 消息视图。
     *
     * @param retryable 只有 FAILED/DEAD 可人工重投
     */
    public record IntegrationMessageView(UUID id, String systemCode, String messageType,
                                         String businessKey, String status, int attempts,
                                         Instant nextRetryAt, String lastError, String externalId,
                                         String createdBy, Instant createdAt, boolean retryable,
                                         String payloadPreview) {
    }

    /**
     * 状态汇总（运维看板）。
     *
     * @param pending 待发送（含已认领未回执）
     * @param failed  失败待重试
     * @param dead    超过重试上限，需人工处理
     * @param acked   已确认
     */
    public record IntegrationSummary(int pending, int failed, int dead, int acked,
                                     List<SystemCount> bySystem) {
    }

    /** 按对端系统分组的待处理量。 */
    public record SystemCount(String systemCode, int pending, int failed, int dead) {
    }
}
