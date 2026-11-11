package com.medassist.gateway.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** 安全响应头配置装配（§267）。 */
@Configuration
@EnableConfigurationProperties(SecurityHeadersProperties.class)
public class SecurityHeadersConfig {
}
