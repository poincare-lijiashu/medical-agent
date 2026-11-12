package com.medassist.auth.domain;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 开放平台客户端（第三方系统身份）。
 *
 * <p>与用户账号的区别：客户端没有科室/院区范围，也不能登录控制台；
 * 它的"权限"完全由 {@code scopes} 决定，而 scopes 只允许只读权限点
 * （见 {@code OAuthClientService.SCOPE_CATALOG}）。
 */
public record OAuthClient(
        UUID id,
        String clientId,
        String name,
        String description,
        String secretHint,
        List<String> scopes,
        String status,
        int tokenTtlSeconds,
        Instant expiresAt,
        Instant lastUsedAt,
        String createdBy,
        Instant createdAt,
        String updatedBy,
        Instant updatedAt
) {
    public boolean usable() {
        return "ACTIVE".equals(status) && (expiresAt == null || expiresAt.isAfter(Instant.now()));
    }
}
