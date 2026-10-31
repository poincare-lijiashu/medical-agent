package com.medassist.tech.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * 医技配置。
 *
 * @param empi                    EMPI 内网接口（开单前校验患者）
 * @param criticalTimeoutMinutes  危急值确认时限（分钟），超过则查询接口标记 overdue
 */
@ConfigurationProperties("tech")
public record TechProperties(Empi empi, Integer criticalTimeoutMinutes) {

    public record Empi(String baseUrl, String internalToken, Duration timeout) {
    }

    public int criticalTimeoutMinutesOrDefault() {
        return criticalTimeoutMinutes == null || criticalTimeoutMinutes <= 0
                ? 30 : criticalTimeoutMinutes;
    }
}
