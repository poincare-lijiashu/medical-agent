package com.medassist.clinical.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 手术安全核查约束（§265）。
 *
 * <p>默认开启：开台前必须有麻醉前 + 手术开始前核查，离室前必须有离室核查。
 * 关闭只用于历史数据演练；关闭时接口仍如实返回未完成状态，不伪造证据。
 */
@ConfigurationProperties("clinical.surgery")
public record SurgeryProperties(Boolean safetyChecklistRequired) {

    public boolean safetyChecklistRequiredOrDefault() {
        return safetyChecklistRequired == null || safetyChecklistRequired;
    }
}
