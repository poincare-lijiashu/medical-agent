package com.medassist.auth.web.dto;

import java.time.Instant;

/**
 * 登录结果（P1：增加刷新令牌）。
 *
 * @param accessToken      访问令牌（Bearer，短 TTL）
 * @param tokenType        固定 {@code Bearer}
 * @param expiresIn        访问令牌剩余秒数
 * @param expiresAt        访问令牌过期时刻（UTC）
 * @param refreshToken     刷新令牌（不透明字符串，仅存哈希；每次刷新轮换）
 * @param refreshExpiresAt 刷新令牌过期时刻（UTC）
 * @param user             当前登录人信息与权限快照
 */
public record LoginResp(
        String accessToken,
        String tokenType,
        long expiresIn,
        Instant expiresAt,
        String refreshToken,
        Instant refreshExpiresAt,
        MeResp user
) {
}
