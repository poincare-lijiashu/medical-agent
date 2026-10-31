package com.medassist.common.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * 服务间签名校验配置（MF-07 阶段一）。
 *
 * <pre>
 * medassist:
 *   internal-auth:
 *     mode: off            # off | log | enforce（生产只允许 log / enforce）
 *     clock-skew-seconds: 60
 *     keys-json: '{"active":"gw-2026-11","keys":{"gw-2026-11":"<base64 32B>"}}'
 *     nonce-ttl-seconds: 120
 *     internal-paths-signature-required: false
 *     exempt-paths:
 *       - /actuator/**
 * </pre>
 *
 * <p>未配置密钥且 mode=enforce 时**拒绝启动**：与 EMPI FieldCipher 同规格，
 * 不允许"配错就静默放行"。
 *
 * @param internalPathsSignatureRequired {@code /internal/**}（服务→服务）是否也要求调用方签名。
 *        默认 false：先让全部调用方具备签名能力，再切换为 true（T5 收口）。
 * @param caller 当前服务作为调用方时的标识（clinical/emr/…）；为空则按
 *        {@code spring.application.name} 推导（platform-clinical → clinical），一般无需配置。
 */
@ConfigurationProperties("medassist.internal-auth")
public record InternalAuthProperties(String mode, Integer clockSkewSeconds, String keysJson,
                                     Integer nonceTtlSeconds, Boolean internalPathsSignatureRequired,
                                     List<String> exemptPaths, String caller) {

    public static final String OFF = "off";
    public static final String LOG = "log";
    public static final String ENFORCE = "enforce";

    public String modeOrDefault() {
        return mode == null || mode.isBlank() ? OFF : mode.trim().toLowerCase();
    }

    public long clockSkewSecondsOrDefault() {
        return clockSkewSeconds == null || clockSkewSeconds <= 0 ? 60 : clockSkewSeconds;
    }

    public long nonceTtlSecondsOrDefault() {
        return nonceTtlSeconds == null || nonceTtlSeconds <= 0 ? 120 : nonceTtlSeconds;
    }

    public boolean internalPathsSignatureRequiredOrDefault() {
        return Boolean.TRUE.equals(internalPathsSignatureRequired);
    }

    /**
     * 默认豁免：探针与 Prometheus 抓取。
     *
     * <p>{@code /actuator/prometheus} 必须豁免——它由集群内 Prometheus 直接抓 Pod IP，
     * 不经网关，若强制签名会导致生产监控在 enforce 模式下全部抓取失败。
     * 该端点的网络可达范围由 NetworkPolicy 限制，不依赖签名。
     */
    public List<String> exemptPathsOrDefault() {
        return exemptPaths == null || exemptPaths.isEmpty()
                ? List.of("/actuator/health/**", "/actuator/info", "/actuator/prometheus") : exemptPaths;
    }

    /** 显式配置的调用方标识；为空时由 spring.application.name 推导。 */
    public String callerOrNull() {
        return caller == null || caller.isBlank() ? null : caller.trim().toLowerCase();
    }
}
