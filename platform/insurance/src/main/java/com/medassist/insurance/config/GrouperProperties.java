package com.medassist.insurance.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.Locale;

/**
 * 分组引擎配置（§263）。
 *
 * <p>LOCAL：平台内置的规则驱动引擎；REMOTE：省局/厂商分组器（HTTP）。
 * 远端失败是否回落本地由 {@code fallback-local} 决定，**默认不回落**——
 * 混用两种口径会让"预分组"和"正式分组"悄悄变成同一份结果。
 *
 * @param engine         LOCAL / REMOTE
 * @param remoteUrl      远端分组器完整地址（REMOTE 必填）
 * @param remoteToken    远端鉴权令牌（可选）
 * @param timeout        远端连接/读取超时
 * @param fallbackLocal  远端失败时是否回落本地
 */
@ConfigurationProperties("insurance.grouper")
public record GrouperProperties(String engine, String remoteUrl, String remoteToken,
                                Duration timeout, Boolean fallbackLocal) {

    public static final String LOCAL = "LOCAL";
    public static final String REMOTE = "REMOTE";

    public String engineOrDefault() {
        if (engine == null || engine.isBlank()) {
            return LOCAL;
        }
        return engine.trim().toUpperCase(Locale.ROOT);
    }

    public String remoteUrlOrNull() {
        return remoteUrl == null || remoteUrl.isBlank() ? null : remoteUrl.trim();
    }

    public boolean fallbackLocalOrDefault() {
        return Boolean.TRUE.equals(fallbackLocal);
    }

    public Duration timeoutOrDefault() {
        return timeout == null || timeout.isNegative() || timeout.isZero()
                ? Duration.ofSeconds(3) : timeout;
    }
}
