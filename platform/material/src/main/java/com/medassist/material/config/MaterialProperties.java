package com.medassist.material.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 物资耗材配置。
 *
 * @param nearExpiryDays 近效期阈值（剩余天数 ≤ 该值进入预警；不拦截出库）
 */
@ConfigurationProperties(prefix = "material")
public record MaterialProperties(int nearExpiryDays) {

    public int nearExpiryDaysOrDefault() {
        return nearExpiryDays > 0 ? nearExpiryDays : 90;
    }
}
