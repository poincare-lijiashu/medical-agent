package com.medassist.auth.web.dto;

import java.time.Instant;

/**
 * 审计事件（跨域汇聚视图）。
 *
 * <p>{@code source} 标识来源域（auth / empi），{@code (source, id)} 唯一确定一条事件；
 * {@code detail} 原样返回 jsonb 文本，避免各域字段差异被强行归一化而丢信息。
 */
public record AuditEventResp(
        String source,
        long id,
        Instant ts,
        String actor,
        String action,
        String targetType,
        String targetId,
        String detail
) {
}
