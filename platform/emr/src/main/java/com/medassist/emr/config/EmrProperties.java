package com.medassist.emr.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * 病历配置。
 *
 * @param empi     EMPI 内网接口（建病历前校验患者）
 * @param clinical 临床域内网接口（读取入院时间，用于书写时限质控）
 * @param internal 供下游（医保分组等）读取首页数据的内部接口令牌
 */
@ConfigurationProperties("emr")
public record EmrProperties(Empi empi, Clinical clinical, Internal internal) {

    public record Empi(String baseUrl, String internalToken, Duration timeout) {
    }

    public record Clinical(String baseUrl, String internalToken, Duration timeout) {
    }

    public record Internal(String token) {
    }
}
