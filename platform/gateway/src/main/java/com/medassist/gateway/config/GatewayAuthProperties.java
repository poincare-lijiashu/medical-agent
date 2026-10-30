package com.medassist.gateway.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.util.List;

/**
 * 网关鉴权配置。
 *
 * @param enabled           总开关（关闭后仍会剥离客户端伪造的身份头，仅用于排障）
 * @param issuer            期望的令牌签发方，必须与 auth 服务一致
 * @param secret            HMAC 密钥（与 auth 同源，经环境变量 {@code JWT_SECRET} 注入）
 * @param whitelist     免鉴权路径（登录、健康检查等），PathPattern 语法
 * @param protectedPaths 受保护路径（PathPattern 语法，默认 {@code /api/v2/**}）；
 *                       未命中的路径（如 v1 兼容层）由下游自行鉴权
 * @param denyPaths      直接拒绝的内部路径（防止 {@code /internal/**} 经网关外泄），统一返回 404
 */
@ConfigurationProperties(prefix = "medassist.gateway.auth")
public record GatewayAuthProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("medassist-auth") String issuer,
        String secret,
        @DefaultValue({"/api/v2/auth/login", "/actuator/health", "/actuator/info"}) List<String> whitelist,
        @DefaultValue("/api/v2/**") List<String> protectedPaths,
        @DefaultValue("/api/v2/*/internal/**") List<String> denyPaths
) {
}
