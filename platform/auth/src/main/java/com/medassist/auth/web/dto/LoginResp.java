package com.medassist.auth.web.dto;

import java.time.Instant;

/**
 * 登录结果。
 *
 * @param accessToken 访问令牌（Bearer）
 * @param tokenType   固定 {@code Bearer}
 * @param expiresIn   剩余有效秒数（前端可据此决定何时提示续期）
 * @param expiresAt   过期时刻（UTC）
 * @param user        当前登录人信息与权限快照
 */
public record LoginResp(
        String accessToken,
        String tokenType,
        long expiresIn,
        Instant expiresAt,
        MeResp user
) {
}
