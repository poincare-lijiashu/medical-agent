package com.medassist.auth.web.dto;

import com.medassist.auth.domain.OAuthClient;

import java.util.List;
import java.util.Map;

/** 开放平台客户端 API 出入参（对应前端 `api/oauth.ts`）。 */
public final class OAuthDtos {

    private OAuthDtos() {
    }

    public record CreateReq(
            String name,
            String description,
            List<String> scopes,
            Integer tokenTtlSeconds,
            String expiresAt
    ) {
    }

    /** 创建/轮换结果：secret 只在这里出现一次。 */
    public record SecretResp(OAuthClient client, String secret) {
    }

    public record StatusReq(Boolean enabled) {
    }

    public record ScopesReq(List<String> scopes) {
    }

    public record ScopeCatalog(Map<String, String> scopes) {
    }

    /**
     * RFC 6749 §5.1 令牌响应（字段名保持 snake_case，第三方 SDK 直接可用）。
     */
    public record TokenResp(String access_token, String token_type, long expires_in, String scope) {
    }
}
