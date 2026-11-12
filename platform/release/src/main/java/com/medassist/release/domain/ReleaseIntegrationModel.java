package com.medassist.release.domain;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * CI/CD 接入模型（§276）。
 *
 * <p>接入源代表一条流水线；事件表以 {@code (source, eventId)} 唯一，
 * 让"重试"与"重复投递"不会重复落部署记录。
 */
public final class ReleaseIntegrationModel {

    private ReleaseIntegrationModel() {
    }

    public record CiSource(
            UUID id,
            String code,
            String name,
            String kind,
            String tokenHint,
            List<String> allowedComponents,
            String status,
            Instant lastUsedAt,
            String createdBy,
            long version,
            Instant createdAt,
            Instant updatedAt) {
    }

    public record CiEvent(
            long id,
            Instant ts,
            String sourceCode,
            String eventId,
            String kind,
            String componentCode,
            String versionNo,
            String environment,
            String action,
            String result,
            UUID deploymentId,
            String status,
            String message,
            Map<String, Object> payload) {
    }

    /** 创建/轮换时一次性返回明文令牌。 */
    public record CiSourceCreated(CiSource source, String token) {
    }
}
