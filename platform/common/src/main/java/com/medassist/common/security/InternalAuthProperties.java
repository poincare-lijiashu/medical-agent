package com.medassist.common.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;
import java.util.Map;

/**
 * 服务间签名校验配置（MF-07 阶段一）。
 *
 * <pre>
 * medassist:
 *   internal-auth:
 *     mode: off            # off | log | enforce（生产只允许 log / enforce）
 *     clock-skew-seconds: 60
 *     keys-json: '{"active":"gw-2026-11","keys":{"gw-2026-11":"<base64 32B>"}}'
 *     nonce-ttl-seconds: 120    # 必须 ≥ 2×clock-skew+1
 *     internal-paths-signature-required: false
 *     body-hash-required: false       # true 时按 v1i2 签发并要求对端也是 v1i2（P0-8）
 *     body-hash-max-bytes: 1048576    # 验签时缓存请求体的上限，超限 413
 *     caller: clinical          # 可省略，默认由 spring.application.name 推导
 *     exempt-paths:
 *       - /actuator/health/**
 *
 *     # ---- 阶段二（网关非对称签名 + 密钥面收敛）----
 *     gateway-signing: ed25519  # hmac(v1，过渡期) | ed25519(v2)
 *     gateway-key-id: gw-v2     # v2 下写入 X-Internal-Key-Id，便于观测（不参与选钥）
 *     gateway-private-key: "..."# 仅网关持有（PKCS#8 base64）
 *     gateway-public-key: "..." # 仅业务服务持有（X.509 base64）
 *     caller-keys:              # 服务→服务通道，按调用方隔离；只挂直接调用方的密钥
 *       clinical: "..."         # 本服务自己的密钥（出站签名）
 *       emr: "..."              # 会调用本服务的调用方（入站验签）
 * </pre>
 *
 * <p>豁免路径只放行**确有必要且已由网络策略限制**的端点：探针与 Prometheus 抓取。
 * 不要写成 {@code /actuator/**}——那会把 metrics/info 等诊断端点一并豁免。
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
                                     List<String> exemptPaths, String caller,
                                     String gatewaySigning, String gatewayKeyId,
                                     String gatewayPrivateKey, String gatewayPublicKey,
                                     Map<String, String> callerKeys,
                                     Boolean bodyHashRequired, Integer bodyHashMaxBytes) {

    public static final String OFF = "off";
    public static final String LOG = "log";
    public static final String ENFORCE = "enforce";


    public String modeOrDefault() {
        return mode == null || mode.isBlank() ? OFF : mode.trim().toLowerCase();
    }

    public long clockSkewSecondsOrDefault() {
        return clockSkewSeconds == null || clockSkewSeconds <= 0 ? 60 : clockSkewSeconds;
    }

    /**
     * nonce 保留时长。
     *
     * <p>默认 180 秒（3×默认时间窗）：必须明显大于 2×时钟偏差，否则"窗口内仍合法的时间戳"
     * 配上一个已过期的 nonce 就能重放（第五轮 R5-M2，启动时由自动配置断言）。
     */
    public long nonceTtlSecondsOrDefault() {
        return nonceTtlSeconds == null || nonceTtlSeconds <= 0 ? 180 : nonceTtlSeconds;
    }

    public boolean internalPathsSignatureRequiredOrDefault() {
        return Boolean.TRUE.equals(internalPathsSignatureRequired);
    }

    /**
     * 是否要求请求体摘要纳入调用方签名（v1i2，P0-8）。
     *
     * <p>开启后：本服务出站一律签 v1i2；入站只接受 v1i2（旧版 v1i 直接 403）。
     * 灰度顺序：服务端先部署（暂时 false，同时接受两种版本）→ 调用方全部升级 →
     * 再把服务端与调用方的本开关一起打开。
     */
    public boolean bodyHashRequiredOrDefault() {
        return Boolean.TRUE.equals(bodyHashRequired);
    }

    /** 验签时缓存请求体的上限（默认 1MB）；过小会把正常 JSON 误杀，过大则失去内存保护意义。 */
    public int bodyHashMaxBytesOrDefault() {
        return bodyHashMaxBytes == null || bodyHashMaxBytes <= 0
                ? 1024 * 1024 : Math.min(bodyHashMaxBytes, 16 * 1024 * 1024);
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

    /** 网关签名算法：默认 hmac（v1，兼容既有部署），可为 ed25519（v2）。 */
    public String gatewaySigningOrDefault() {
        String value = gatewaySigning == null || gatewaySigning.isBlank()
                ? InternalSignature.GATEWAY_SIGNING_HMAC : gatewaySigning.trim().toLowerCase();
        if (!InternalSignature.GATEWAY_SIGNING_HMAC.equals(value)
                && !InternalSignature.GATEWAY_SIGNING_ED25519.equals(value)) {
            throw new IllegalStateException(
                    "medassist.internal-auth.gateway-signing 非法（" + gatewaySigning
                            + "）：只允许 hmac / ed25519");
        }
        return value;
    }

    public boolean ed25519GatewaySigning() {
        return InternalSignature.GATEWAY_SIGNING_ED25519.equals(gatewaySigningOrDefault());
    }

    /** v2 的 key-id 仅用于观测与审计，不参与选钥（公钥只有一把，轮换靠滚动更新公钥）。 */
    public String gatewayKeyIdOrDefault() {
        return gatewayKeyId == null || gatewayKeyId.isBlank() ? "gw-v2" : gatewayKeyId.trim();
    }

    /** 按调用方显式配置的密钥（Helm 按组件裁剪后注入）；空 Map 表示未配置。 */
    public Map<String, String> callerKeysOrDefault() {
        return callerKeys == null ? Map.of() : callerKeys;
    }
}
