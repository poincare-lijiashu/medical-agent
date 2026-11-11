package com.medassist.clinical.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 输血闭环约束（§264，WS/T 867—2025）。
 *
 * <p>两项默认开启：发血前必须有输血前评估/知情同意，完成输注前必须有观察记录。
 * 允许显式关闭只用于历史数据补录与演练；关闭时接口仍返回缺失提示，不伪造证据。
 *
 * @param precheckRequired     发血前是否要求输血前评估与知情同意
 * @param observationRequired  完成输注前是否要求至少一条观察记录
 */
@ConfigurationProperties("clinical.transfusion")
public record TransfusionProperties(Boolean precheckRequired, Boolean observationRequired) {

    public boolean precheckRequiredOrDefault() {
        return precheckRequired == null || precheckRequired;
    }

    public boolean observationRequiredOrDefault() {
        return observationRequired == null || observationRequired;
    }
}
