package com.medassist.pharmacy.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * 药房配置。
 *
 * @param empi           EMPI 内网接口（开方前校验患者）
 * @param nearExpiryDays 近效期预警阈值（剩余天数 ≤ 该值即列出，不拦截发药）
 */
@ConfigurationProperties("pharmacy")
public record PharmacyProperties(Empi empi, Integer nearExpiryDays) {

    public record Empi(String baseUrl, String internalToken, Duration timeout) {
    }

    public int nearExpiryDaysOrDefault() {
        return nearExpiryDays == null || nearExpiryDays <= 0 ? 90 : nearExpiryDays;
    }
}
