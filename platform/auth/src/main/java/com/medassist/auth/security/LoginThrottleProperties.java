package com.medassist.auth.security;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * 登录暴力破解限速配置。
 *
 * @param enabled       总开关（默认开；关闭仅用于压测/排障）
 * @param maxFailures   窗口内同一账号允许的失败次数（达到即锁定该账号登录入口）
 * @param maxIpFailures 窗口内同一来源 IP 允许的失败次数——必须显著高于账号阈值：
 *                      医院内网大量终端共享出口/NAT，IP 阈值过低会把整层楼锁在门外
 *                      （实测踩坑后拆分两个维度，2026-10-30）
 * @param window        统计窗口
 */
@ConfigurationProperties(prefix = "medassist.auth.login-throttle")
public record LoginThrottleProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("5") int maxFailures,
        @DefaultValue("30") int maxIpFailures,
        @DefaultValue("PT5M") Duration window
) {
}
