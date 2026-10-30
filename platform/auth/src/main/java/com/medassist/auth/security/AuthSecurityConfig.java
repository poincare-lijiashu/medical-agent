package com.medassist.auth.security;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 认证域安全配置：只负责绑定 JWT 配置属性（签发/校验组件见 {@link JwtTokenService}）。
 *
 * <p>说明：auth 服务是令牌<b>签发方</b>而非资源服务器，因此不引入 Spring Security 过滤器链——
 * 鉴权统一在网关注入身份头完成（见计划书 D2.2）。此处用 Spring Security 的 JOSE 组件
 * 只做标准 JWT 编解码，避免自研签名逻辑带来的算法混淆/解析缺陷。
 */
@Configuration
@EnableConfigurationProperties({JwtProperties.class, LoginThrottleProperties.class})
public class AuthSecurityConfig {
}
