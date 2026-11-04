package com.medassist.emr.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * 病历配置。
 *
 * @param empi     EMPI 内网接口（建病历前校验患者）
 * @param clinical 临床域内网接口（读取入院时间，用于书写时限质控）
 * @param internal 供下游（医保分组等）读取首页数据的内部接口令牌
 * @param ca       CA 电子签名适配层（mock 本地签名 / vendor 厂商 HTTP）
 */
@ConfigurationProperties("emr")
public record EmrProperties(Empi empi, Clinical clinical, Internal internal, Ca ca) {

    public record Empi(String baseUrl, String internalToken, Duration timeout) {
    }

    public record Clinical(String baseUrl, String internalToken, Duration timeout) {
    }

    public record Internal(String token) {
    }

    /**
     * @param mode      mock / vendor
     * @param baseUrl   厂商签名/验签接口根地址（vendor 模式必填）
     * @param apiKey    厂商接口密钥（vendor 模式）
     * @param secret    本地 Mock HMAC 密钥（mock 模式）
     * @param timeout   连接/读取超时
     */
    public record Ca(String mode, String baseUrl, String apiKey, String secret, Duration timeout) {
    }
}
