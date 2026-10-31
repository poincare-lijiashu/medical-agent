package com.medassist.insurance.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * 医保域配置。
 *
 * @param empi     EMPI 内网接口（参保登记前校验患者）
 * @param clinical 临床域内网接口（拉取收费明细，作为结算唯一数据源）
 */
@ConfigurationProperties("insurance")
public record InsuranceProperties(Empi empi, Clinical clinical) {

    public record Empi(String baseUrl, String internalToken, Duration timeout) {
    }

    public record Clinical(String baseUrl, String internalToken, Duration timeout) {
    }
}
