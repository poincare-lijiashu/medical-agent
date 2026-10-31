package com.medassist.emr.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * 病历配置。
 *
 * @param empi EMPI 内网接口（建病历前校验患者）
 */
@ConfigurationProperties("emr")
public record EmrProperties(Empi empi) {

    public record Empi(String baseUrl, String internalToken, Duration timeout) {
    }
}
