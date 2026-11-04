package com.medassist.auth.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * 外部统一身份与本地账号的绑定（G42）。
 *
 * <p>{@code provider + subject} 是 IdP 侧的唯一身份；{@code userId} 是本地账号。
 * 一个本地账号在同一 provider 下只允许一个 subject，避免换号后静默接管。
 */
public record SsoIdentity(UUID id, String provider, String subject, UUID userId,
                          String email, String displayName, Instant createdAt, Instant lastLoginAt) {
}
