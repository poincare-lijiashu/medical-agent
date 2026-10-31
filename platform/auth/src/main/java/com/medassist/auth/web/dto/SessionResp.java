package com.medassist.auth.web.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * 活跃会话视图（= 一条未撤销、未过期的刷新令牌，对应一台设备/一次登录）。
 *
 * <p>{@code userAgent} 由登录时的请求头截断保存，仅用于让用户辨认"哪台设备"；
 * {@code id} 用于精确踢出（后端按 {@code user_id + id} 双条件更新，不能跨账号操作）。
 */
public record SessionResp(
        UUID id,
        String clientIp,
        String userAgent,
        Instant issuedAt,
        Instant lastUsedAt,
        Instant expiresAt
) {
}
