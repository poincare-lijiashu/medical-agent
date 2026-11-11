package com.medassist.clinical.integration;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.Map;

/**
 * 集成通道配置（第 257 批）。
 *
 * <p>示例（application-dev.yml 或环境变量）：
 * <pre>
 * medassist:
 *   integration:
 *     systems:
 *       HIS: { host: 10.0.0.21, port: 2575, timeout-ms: 5000 }
 *       LIS: { host: 10.0.0.22, port: 2575 }
 *     max-attempts: 8
 *     base-backoff-seconds: 30
 *     max-backoff-seconds: 1800
 * </pre>
 *
 * <p>没有配置的系统不会被伪造成功：消息会保留在台账里并标记失败原因，
 * 由运维补齐地址后人工/自动重投。
 */
@ConfigurationProperties(prefix = "medassist.integration")
public record IntegrationProperties(Map<String, Endpoint> systems, int maxAttempts,
                                    long baseBackoffSeconds, long maxBackoffSeconds,
                                    int sendTimeoutMs) {

    public record Endpoint(String host, Integer port, Integer timeoutMs) {
    }

    public IntegrationProperties {
        systems = systems == null ? Map.of() : Map.copyOf(systems);
        maxAttempts = maxAttempts > 0 ? maxAttempts : 8;
        baseBackoffSeconds = baseBackoffSeconds > 0 ? baseBackoffSeconds : 30;
        maxBackoffSeconds = maxBackoffSeconds > 0 ? maxBackoffSeconds : 1800;
        sendTimeoutMs = sendTimeoutMs > 0 ? sendTimeoutMs : 5000;
    }
}
