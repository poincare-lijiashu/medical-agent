package com.medassist.ops.domain;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * 告警接入模型（§276）。
 *
 * <p>与工单/事件模型分开：接入源与告警状态是"机器视角"，
 * 工单是"人的处理过程"，两者通过 {@code ticketId} 关联而不混表。
 */
public final class OpsIntegrationModel {

    private OpsIntegrationModel() {
    }

    /** 告警接入源；令牌哈希不进入 API 响应。 */
    public record AlertSource(
            UUID id,
            String code,
            String name,
            String kind,
            String tokenHint,
            String defaultService,
            Map<String, String> severityMap,
            boolean autoResolve,
            int repeatCommentMinutes,
            String status,
            Instant lastUsedAt,
            String createdBy,
            long version,
            Instant createdAt,
            Instant updatedAt) {
    }

    /** 某条告警的当前状态；firing 重复通知靠它判断是否已在处理。 */
    public record AlertState(
            String sourceCode,
            String fingerprint,
            String status,
            String alertname,
            String severity,
            String priority,
            String serviceCode,
            String title,
            UUID ticketId,
            Instant startsAt,
            Instant lastSeenAt,
            Instant resolvedAt,
            Map<String, Object> labels,
            Map<String, Object> annotations,
            long version) {
    }

    /** 入站事件留痕（追加式）。 */
    public record HookEvent(
            long id,
            Instant ts,
            String sourceCode,
            String fingerprint,
            String action,
            UUID ticketId,
            String note,
            Map<String, Object> payload,
            String traceId) {
    }

    /** 创建/轮换接入源时的一次性返回：明文令牌不再可查。 */
    public record SourceCreated(AlertSource source, String token) {
    }
}
