package com.medassist.auth.web.dto;

import java.time.Instant;
import java.util.List;

/** 内部令牌校验结果（无效令牌也返回 200 + valid=false，便于排障区分"签名错/过期"与"服务不可用"）。 */
public record VerifyResp(
        boolean valid,
        String userId,
        String username,
        List<String> roles,
        List<String> permissions,
        List<String> deptIds,
        String orgCode,
        Instant issuedAt,
        Instant expiresAt
) {

    public static VerifyResp invalid() {
        return new VerifyResp(false, null, null, List.of(), List.of(), List.of(), null, null, null);
    }
}
