package com.medassist.clinical.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * 临床核心服务配置。
 *
 * @param empi {@code empi.base-url} 内网地址（如 http://platform-empi:8082），
 *             {@code empi.internal-token} 服务间令牌（必须与 EMPI 侧一致）
 */
@ConfigurationProperties(prefix = "clinical")
public record ClinicalProperties(Empi empi) {

    public record Empi(String baseUrl, String internalToken, Duration timeout) {
    }
}
