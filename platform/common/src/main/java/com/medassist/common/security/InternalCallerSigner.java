package com.medassist.common.security;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 服务→服务调用的签名器（MF-07 T5）。
 *
 * <p>每个服务以自身身份（clinical / emr / insurance / pharmacy / tech）对
 * {@code /internal/**} 请求签名，被调方按 {@code X-Internal-Caller} 选择对应密钥验签。
 * 与网关通道相比：
 * <ul>
 *   <li>规范串为 v1i / v1i2，不含用户身份头，改为绑定 caller；</li>
 *   <li>密钥按调用方隔离，一个调用方密钥泄露不影响其它调用方；</li>
 *   <li>{@code X-Internal-Token} 仍保留为第一因子，签名是第二因子。</li>
 * </ul>
 *
 * <p>v1i2（P0-8）：把请求体 SHA-256 纳入规范串。此前 body 摘要固定为 {@code "-"}，
 * 内网在途篡改医嘱/处方/病历 POST 体不会破坏签名，调用双方都无感知。
 * 灰度开关为 {@code medassist.internal-auth.body-hash-required}：
 * 关闭时仍按 v1i 签发（兼容未升级的被调方），打开后按 v1i2 签发。
 */
public final class InternalCallerSigner {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final String caller;
    private final String secret;
    /** 是否把请求体摘要纳入签名（对应签名版本 v1i2）。 */
    private final boolean bodyHashEnabled;

    private InternalCallerSigner(String caller, String secret, boolean bodyHashEnabled) {
        this.caller = caller;
        this.secret = secret;
        this.bodyHashEnabled = bodyHashEnabled;
    }

    /**
     * @param caller 调用方标识（小写）；null 或空表示不签名
     * @param secret Base64 密钥；null 表示该调用方未配置密钥
     */
    public static InternalCallerSigner of(String caller, String secret) {
        return of(caller, secret, false);
    }

    /**
     * @param bodyHashEnabled 是否按 v1i2 签发（请求体绑定）
     */
    public static InternalCallerSigner of(String caller, String secret, boolean bodyHashEnabled) {
        String normalized = caller == null || caller.isBlank() ? null : caller.trim().toLowerCase();
        return new InternalCallerSigner(normalized,
                secret == null || secret.isBlank() ? null : secret, bodyHashEnabled);
    }

    public static InternalCallerSigner disabled() {
        return new InternalCallerSigner(null, null, false);
    }

    public boolean configured() {
        return caller != null && secret != null;
    }

    public String caller() {
        return caller;
    }

    /** 是否已启用请求体绑定（v1i2）。 */
    public boolean bodyHashEnabled() {
        return configured() && bodyHashEnabled;
    }

    /** 无请求体的调用（GET/DELETE）：按当前开关选择版本，body 摘要为 {@code "-"}。 */
    public Map<String, String> headers(String method, String path, String query) {
        return headers(method, path, query, null);
    }

    /**
     * 生成需要附加到请求上的签名头；未配置时返回空 Map（对方会在 enforce 下拒绝）。
     *
     * @param body 已序列化的请求体（可为 null）；启用体绑定时参与摘要
     */
    public Map<String, String> headers(String method, String path, String query, byte[] body) {
        if (!configured()) {
            return Map.of();
        }
        long timestamp = System.currentTimeMillis() / 1000L;
        String nonce = nonce();
        String version = bodyHashEnabled
                ? InternalSignature.SIG_VERSION_CALLER_BODY : InternalSignature.SIG_VERSION_CALLER;
        String bodyHash = bodyHashEnabled
                ? InternalSignature.bodyHash(body) : InternalSignature.EMPTY_BODY_HASH;
        String canonical = InternalSignature.canonicalCaller(version, method, path, query, caller,
                timestamp, nonce, bodyHash);
        Map<String, String> headers = new LinkedHashMap<>(6);
        headers.put(InternalSignature.HEADER_SIG_VER, version);
        headers.put(InternalSignature.HEADER_CALLER, caller);
        headers.put(InternalSignature.HEADER_TS, Long.toString(timestamp));
        headers.put(InternalSignature.HEADER_NONCE, nonce);
        headers.put(InternalSignature.HEADER_SIG, InternalSignature.sign(secret, canonical));
        return headers;
    }

    private static String nonce() {
        byte[] bytes = new byte[16];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
