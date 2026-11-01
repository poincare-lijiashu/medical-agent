package com.medassist.insurance.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * 医保域配置。
 *
 * @param empi     EMPI 内网接口（参保登记前校验患者）
 * @param clinical 临床域内网接口（拉取收费明细与诊断，作为结算/分组的数据源）
 * @param emr      病历域内网接口（DRG 模拟分组读取病案首页手术操作）
 */
@ConfigurationProperties("insurance")
public record InsuranceProperties(Empi empi, Clinical clinical, Emr emr) {

    public record Empi(String baseUrl, String internalToken, Duration timeout) {
    }

    public record Clinical(String baseUrl, String internalToken, Duration timeout) {
    }

    public record Emr(String baseUrl, String internalToken, Duration timeout) {
    }
}
