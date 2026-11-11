package com.medassist.gateway.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 网关安全响应头（§267，等保三级「安全通信网络/安全区域边界」技术项）。
 *
 * <p>为什么放在网关而不是各业务服务：浏览器与扫描器先看到的是网关响应；
 * 下游服务各自加头会出现"有的接口有、有的没有"，测评时无法给出统一证据。
 *
 * @param enabled               总开关（仅排障可关，生产保持 true）
 * @param hstsEnabled           是否输出 HSTS（只在 HTTPS 请求上输出）
 * @param hstsMaxAgeSeconds     HSTS 有效期，默认 1 年
 * @param hstsIncludeSubDomains 是否覆盖子域
 * @param trustForwardedProto   是否信任 {@code X-Forwarded-Proto}（TLS 在 Ingress 终止时必需）
 * @param contentSecurityPolicy CSP 值；API 默认禁止加载任何资源
 * @param referrerPolicy        Referrer-Policy
 * @param permissionsPolicy     Permissions-Policy
 * @param noStoreForAuthPaths   登录/刷新等认证响应是否禁止缓存
 */
@ConfigurationProperties(prefix = "medassist.gateway.security-headers")
public record SecurityHeadersProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("true") boolean hstsEnabled,
        @DefaultValue("31536000") long hstsMaxAgeSeconds,
        @DefaultValue("true") boolean hstsIncludeSubDomains,
        @DefaultValue("true") boolean trustForwardedProto,
        @DefaultValue("default-src 'none'; frame-ancestors 'none'; base-uri 'none'")
        String contentSecurityPolicy,
        @DefaultValue("no-referrer") String referrerPolicy,
        @DefaultValue("camera=(), microphone=(), geolocation=()") String permissionsPolicy,
        @DefaultValue("true") boolean noStoreForAuthPaths
) {
}
