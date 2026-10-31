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
 *   <li>规范串为 v1i，不含用户身份头，改为绑定 caller；</li>
 *   <li>密钥按调用方隔离，一个调用方密钥泄露不影响其它调用方；</li>
 *   <li>{@code X-Internal-Token} 仍保留为第一因子，签名是第二因子。</li>
 * </ul>
 */
public final class InternalCallerSigner {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final String caller;
    private final String secret;

    private InternalCallerSigner(String caller, String secret) {
        this.caller = caller;
        this.secret = secret;
    }

    /**
     * @param caller 调用方标识（小写）；null 或空表示不签名
     * @param secret Base64 密钥；null 表示该调用方未配置密钥
     */
    public static InternalCallerSigner of(String caller, String secret) {
        String normalized = caller == null || caller.isBlank() ? null : caller.trim().toLowerCase();
        return new InternalCallerSigner(normalized, secret == null || secret.isBlank() ? null : secret);
    }

    public static InternalCallerSigner disabled() {
        return new InternalCallerSigner(null, null);
    }

    public boolean configured() {
        return caller != null && secret != null;
    }

    public String caller() {
        return caller;
    }

    /** 生成需要附加到请求上的签名头；未配置时返回空 Map（对方会在 enforce 下拒绝）。 */
    public Map<String, String> headers(String method, String path, String query) {
        if (!configured()) {
            return Map.of();
        }
        long timestamp = System.currentTimeMillis() / 1000L;
        String nonce = nonce();
        String canonical = InternalSignature.canonicalCaller(method, path, query, caller,
                timestamp, nonce, InternalSignature.EMPTY_BODY_HASH);
        Map<String, String> headers = new LinkedHashMap<>(6);
        headers.put(InternalSignature.HEADER_SIG_VER, InternalSignature.SIG_VERSION_CALLER);
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
