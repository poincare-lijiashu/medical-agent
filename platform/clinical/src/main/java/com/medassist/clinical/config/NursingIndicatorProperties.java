package com.medassist.clinical.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 护理敏感指标口径（§266）。
 *
 * @param reportWindowHours 事件发生后多长时间内上报算"及时"（默认 24 小时，允许 1~168）
 * @param maxBackDays       允许补录多少天前发生的事件（默认 365，允许 1~3650）
 * @param maxQueryDays      看板/事件列表单次查询最长天数（默认 92，允许 1~366）
 */
@ConfigurationProperties("clinical.nursing.indicator")
public record NursingIndicatorProperties(Integer reportWindowHours, Integer maxBackDays,
                                         Integer maxQueryDays) {

    public int reportWindowHoursOrDefault() {
        return clamp(reportWindowHours, 24, 1, 168);
    }

    public int maxBackDaysOrDefault() {
        return clamp(maxBackDays, 365, 1, 3650);
    }

    public int maxQueryDaysOrDefault() {
        return clamp(maxQueryDays, 92, 1, 366);
    }

    private static int clamp(Integer value, int fallback, int min, int max) {
        if (value == null) {
            return fallback;
        }
        return Math.max(min, Math.min(max, value));
    }
}
