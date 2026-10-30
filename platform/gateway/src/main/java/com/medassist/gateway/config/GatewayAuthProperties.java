package com.medassist.gateway.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;
import java.util.List;

/**
 * 网关鉴权配置。
 *
 * @param enabled           总开关（关闭后仍会剥离客户端伪造的身份头，仅用于排障）
 * @param issuer            期望的令牌签发方，必须与 auth 服务一致
 * @param secret            HMAC 密钥（与 auth 同源，经环境变量 {@code JWT_SECRET} 注入）
 * @param whitelist         免鉴权路径（登录、刷新、健康检查等），PathPattern 语法
 * @param protectedPaths    受保护路径（PathPattern 语法，默认 {@code /api/v2/**}）
 * @param denyPaths         直接拒绝的内部路径（统一 404）
 * @param authUri           auth 服务内网地址（撤销名单增量同步用，不参与请求转发）
 * @param revocationSync    访问令牌撤销名单同步配置
 */
@ConfigurationProperties(prefix = "medassist.gateway.auth")
public record GatewayAuthProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("medassist-auth") String issuer,
        String secret,
        @DefaultValue({"/api/v2/auth/login", "/api/v2/auth/refresh", "/actuator/**"}) List<String> whitelist,
        @DefaultValue("/api/v2/**") List<String> protectedPaths,
        @DefaultValue("/api/v2/*/internal/**") List<String> denyPaths,
        @DefaultValue("http://127.0.0.1:8081") String authUri,
        @DefaultValue RevocationSync revocationSync
) {

    /**
     * 撤销名单同步配置。
     *
     * @param enabled  是否启用（关闭后退化为"无即时撤销"，见 README 已知边界）
     * @param interval 同步周期；越小越快生效，代价是 auth 内网 QPS 略增
     */
    public record RevocationSync(
            @DefaultValue("true") boolean enabled,
            @DefaultValue("PT15S") Duration interval
    ) {
    }
}
