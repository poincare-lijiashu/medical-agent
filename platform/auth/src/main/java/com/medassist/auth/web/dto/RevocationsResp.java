package com.medassist.auth.web.dto;

import java.time.Instant;
import java.util.List;

/**
 * 访问令牌撤销名单（网关增量同步用）。
 *
 * @param revocations 自上次同步以来新增的撤销项（只含未过期 jti）
 * @param serverTime  服务端当前时间（epoch millis），作为下一次增量同步的 since
 */
public record RevocationsResp(
        List<RevocationItem> revocations,
        long serverTime
) {

    public record RevocationItem(String jti, Instant expiresAt) {
    }
}
