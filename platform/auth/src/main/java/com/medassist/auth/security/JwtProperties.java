package com.medassist.auth.security;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * JWT 签发配置（auth 服务为唯一签发方）。
 *
 * @param issuer 签发方标识，网关校验 iss 时使用，必须与网关配置一致
 * @param secret HMAC 密钥，经环境变量 {@code JWT_SECRET} 注入；长度不得少于 32 字节
 * @param ttl    令牌有效期，默认 8 小时（覆盖单班次；生产可按安全等级调短并配套刷新机制）
 */
@ConfigurationProperties(prefix = "medassist.auth.jwt")
public record JwtProperties(
        @DefaultValue("medassist-auth") String issuer,
        String secret,
        @DefaultValue("PT8H") Duration ttl
) {
}
