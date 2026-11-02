package com.medassist.common.security;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.Map;
import java.util.TreeMap;

/**
 * 服务间请求签名（MF-07 阶段一，规范串 v1）。
 *
 * <p>解决的问题：业务服务此前只信任网关注入的 {@code X-Permissions} 等身份头，
 * 内网任意主机直连业务端口伪造这些头即可读取 PHI。签名覆盖**全部身份头 + 方法 + 路径 + 查询串 + 时间戳 + 随机数**，
 * 攻击者即使拿到头字段也无法生成合法签名。
 *
 * <p>规范串（固定行序，缺失字段用空串占位，不省略行）：
 * <pre>
 * v1
 * &lt;METHOD&gt;
 * &lt;path&gt;
 * &lt;sorted query&gt;
 * &lt;X-Actor&gt; / &lt;X-User-Id&gt; / &lt;X-Roles&gt; / &lt;X-Permissions&gt; / &lt;X-Token-Id&gt;
 * &lt;X-Org&gt; / &lt;X-Client-Ip&gt; / &lt;X-Trace-Id&gt;
 * &lt;X-Internal-Ts&gt; / &lt;X-Internal-Nonce&gt; / &lt;body hash&gt;
 * </pre>
 *
 * <p>阶段一限制：网关通道（v1/v2）的请求体摘要仍固定为 {@code "-"}（WebFlux 侧缓存 body 留待后续）；
 * 服务→服务通道已升级为 {@link #SIG_VERSION_CALLER_BODY v1i2}，把请求体 SHA-256 纳入规范串（P0-8）。
 * 多副本部署时 nonce 去重需改 Redis，内存实现仅适合单副本或 log 灰度期。
 */
public final class InternalSignature {

    public static final String HEADER_SIG = "X-Internal-Sig";
    public static final String HEADER_TS = "X-Internal-Ts";
    public static final String HEADER_NONCE = "X-Internal-Nonce";
    public static final String HEADER_KEY_ID = "X-Internal-Key-Id";
    public static final String HEADER_SIG_VER = "X-Internal-Sig-Ver";
    /** 调用方标识（clinical / emr / insurance / pharmacy / tech），服务→服务通道使用。 */
    public static final String HEADER_CALLER = "X-Internal-Caller";
    public static final String SIG_VERSION = "v1";
    /**
     * 网关签名 v2（Ed25519，阶段二）。
     *
     * <p>与 v1 的差异只有两处：版本行与算法。规范串结构保持一致，
     * 因此同一份"下游视角"的请求在不同算法下签出的覆盖范围相同。
     */
    public static final String SIG_VERSION_GATEWAY_ED25519 = "v2";
    /** 调用方签名版本（规范串结构与网关通道不同：不含身份头，改为绑定 caller）。 */
    public static final String SIG_VERSION_CALLER = "v1i";
    /**
     * 调用方签名版本 v1i2：在 v1i 基础上把**请求体 SHA-256** 纳入规范串（P0-8）。
     *
     * <p>为什么单独升版本而不是直接改 v1i：签名是双方协议，服务端与调用方不可能同时升级。
     * 分版本后可以按"先升级服务端（同时接受 v1i/v1i2）→ 再升级各调用方 → 最后强制 v1i2"
     * 三步灰度，任何一步回滚都不会造成全平台 403。
     *
     * <p>规范串前 8 行与 v1i 完全一致，仅版本行与末行 body hash 不同。
     */
    public static final String SIG_VERSION_CALLER_BODY = "v1i2";
    /** 网关签名算法选择（medassist.internal-auth.gateway-signing）。 */
    public static final String GATEWAY_SIGNING_HMAC = "hmac";
    public static final String GATEWAY_SIGNING_ED25519 = "ed25519";

    /** 无请求体摘要时使用（阶段一）。 */
    public static final String EMPTY_BODY_HASH = "-";

    /**
     * 参与签名的身份头（顺序即规范串中的行序，**不可调整**）。
     *
     * <p>改顺序会让新旧版本生成的签名互不兼容，属于协议级变更，需同时升级全部服务。
     */
    public static final java.util.List<String> IDENTITY_HEADERS = java.util.List.of(
            AuthHeaders.ACTOR, AuthHeaders.USER_ID, AuthHeaders.ROLES, AuthHeaders.PERMISSIONS,
            AuthHeaders.TOKEN_ID, AuthHeaders.ORG, AuthHeaders.CLIENT_IP, AuthHeaders.TRACE_ID);

    private static final String HMAC_ALGORITHM = "HmacSHA256";

    private InternalSignature() {
    }

    /**
     * 构造规范串。
     *
     * @param query 原始查询串（可空）；内部按 key 排序，保证同一语义不同顺序签名一致
     */
    public static String canonical(String method, String path, String query,
                                   Map<String, String> identityHeaders,
                                   long timestamp, String nonce, String bodyHash) {
        return canonicalGateway(SIG_VERSION, method, path, query, identityHeaders,
                timestamp, nonce, bodyHash);
    }

    /**
     * 网关通道 v2 规范串（Ed25519）：结构同 v1，仅版本行改为 {@code v2}。
     *
     * <p>版本行必须参与签名，否则同一份签名可被改写版本号在"按算法分支"的校验器间反复试探。
     */
    public static String canonicalGatewayV2(String method, String path, String query,
                                            Map<String, String> identityHeaders,
                                            long timestamp, String nonce, String bodyHash) {
        return canonicalGateway(SIG_VERSION_GATEWAY_ED25519, method, path, query,
                identityHeaders, timestamp, nonce, bodyHash);
    }

    private static String canonicalGateway(String version, String method, String path, String query,
                                           Map<String, String> identityHeaders,
                                           long timestamp, String nonce, String bodyHash) {
        StringBuilder sb = new StringBuilder(256);
        sb.append(version).append('\n');
        sb.append(method == null ? "" : method.toUpperCase()).append('\n');
        sb.append(path == null ? "" : path).append('\n');
        sb.append(normalizeQuery(query)).append('\n');
        for (String header : IDENTITY_HEADERS) {
            sb.append(headerValue(identityHeaders, header)).append('\n');
        }
        sb.append(timestamp).append('\n');
        sb.append(nonce == null ? "" : nonce).append('\n');
        sb.append(bodyHash == null || bodyHash.isEmpty() ? EMPTY_BODY_HASH : bodyHash);
        return sb.toString();
    }

    /**
     * 调用方通道规范串（v1i）：服务 → 服务。
     *
     * <p>与网关通道的区别：内部调用没有 {@code X-Actor} 等用户身份头，身份是"哪个服务"，
     * 因此把 caller 绑定进规范串；调换 caller 或换密钥都会导致验签失败。
     * <pre>
     * v1i
     * &lt;METHOD&gt;
     * &lt;path&gt;
     * &lt;sorted query&gt;
     * &lt;caller&gt;
     * &lt;ts&gt;
     * &lt;nonce&gt;
     * &lt;body hash&gt;
     * </pre>
     */
    public static String canonicalCaller(String method, String path, String query, String caller,
                                         long timestamp, String nonce, String bodyHash) {
        return canonicalCaller(SIG_VERSION_CALLER, method, path, query, caller,
                timestamp, nonce, bodyHash);
    }

    /**
     * 指定版本的调用方规范串（v1i / v1i2）。
     *
     * <p>版本行参与签名：即便攻击者把 {@code X-Internal-Sig-Ver} 从 v1i2 改成 v1i，
     * 服务端按 v1i 重算的规范串也不一致，无法把"带体绑定"降级成"不带体绑定"。
     */
    public static String canonicalCaller(String version, String method, String path, String query,
                                         String caller, long timestamp, String nonce,
                                         String bodyHash) {
        StringBuilder sb = new StringBuilder(160);
        sb.append(version == null || version.isEmpty() ? SIG_VERSION_CALLER : version).append('\n');
        sb.append(method == null ? "" : method.toUpperCase()).append('\n');
        sb.append(path == null ? "" : path).append('\n');
        sb.append(normalizeQuery(query)).append('\n');
        sb.append(caller == null ? "" : caller).append('\n');
        sb.append(timestamp).append('\n');
        sb.append(nonce == null ? "" : nonce).append('\n');
        sb.append(bodyHash == null || bodyHash.isEmpty() ? EMPTY_BODY_HASH : bodyHash);
        return sb.toString();
    }

    /** HMAC-SHA256 → Base64URL（无 padding）。 */
    public static String sign(String secretBase64, String canonical) {
        try {
            byte[] key = Base64.getDecoder().decode(secretBase64);
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(key, HMAC_ALGORITHM));
            byte[] digest = mac.doFinal(canonical.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException("HMAC 计算失败", e);
        }
    }

    /** 常量时间比较，避免签名被逐字节探测。 */
    public static boolean verify(String secretBase64, String canonical, String providedSignature) {
        if (providedSignature == null || providedSignature.isEmpty()) {
            return false;
        }
        String expected = sign(secretBase64, canonical);
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
                providedSignature.getBytes(StandardCharsets.UTF_8));
    }

    // ---------------------------------------------------------------- v2：Ed25519

    private static final String ED25519 = "Ed25519";
    private static final int ED25519_KEY_BYTES = 32;

    /**
     * Ed25519 私钥（PKCS#8，Base64 标准编码）。
     *
     * <p>私钥**只允许存在于网关**：业务服务仅持公钥，因此任一业务服务沦陷也无法伪造网关身份
     * （对称 HMAC 时代所有被调方都持有网关密钥，这是第五轮 R5-M3 的核心风险）。
     */
    public static PrivateKey parsePrivateKey(String base64) {
        byte[] bytes = decodeKey(base64, "gateway-private-key", "Ed25519 私钥");
        try {
            return KeyFactory.getInstance(ED25519).generatePrivate(new PKCS8EncodedKeySpec(bytes));
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException(
                    "medassist.internal-auth.gateway-private-key 不是合法的 Ed25519 PKCS#8 私钥："
                            + e.getMessage(), e);
        }
    }

    /** Ed25519 公钥（X.509 SubjectPublicKeyInfo，Base64 标准编码）。 */
    public static PublicKey parsePublicKey(String base64) {
        byte[] bytes = decodeKey(base64, "gateway-public-key", "Ed25519 公钥");
        try {
            return KeyFactory.getInstance(ED25519).generatePublic(new X509EncodedKeySpec(bytes));
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException(
                    "medassist.internal-auth.gateway-public-key 不是合法的 Ed25519 X.509 公钥："
                            + e.getMessage(), e);
        }
    }

    private static byte[] decodeKey(String base64, String property, String what) {
        if (base64 == null || base64.isBlank()) {
            throw new IllegalStateException(
                    "medassist.internal-auth." + property + " 未配置（" + what + "）");
        }
        try {
            byte[] bytes = Base64.getDecoder().decode(base64.trim());
            if (bytes.length < ED25519_KEY_BYTES) {
                throw new IllegalStateException("medassist.internal-auth." + property
                        + " 长度不足 32 字节（" + what + "）");
            }
            return bytes;
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("medassist.internal-auth." + property
                    + " 不是合法 Base64（" + what + "）", e);
        }
    }

    /** Ed25519 签名 → Base64URL（无 padding）。 */
    public static String signEd25519(PrivateKey privateKey, String canonical) {
        try {
            Signature signature = Signature.getInstance(ED25519);
            signature.initSign(privateKey);
            signature.update(canonical.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(signature.sign());
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException("Ed25519 签名失败", e);
        }
    }

    /**
     * Ed25519 验签。
     *
     * <p>签名值来自请求头，格式异常（非法 Base64/长度不符）一律返回 false，
     * 与"验签不通过"同一出口，避免把异常做成探测 oracle。
     */
    public static boolean verifyEd25519(PublicKey publicKey, String canonical,
                                        String providedSignature) {
        if (publicKey == null || providedSignature == null || providedSignature.isEmpty()) {
            return false;
        }
        byte[] raw;
        try {
            raw = Base64.getUrlDecoder().decode(providedSignature);
        } catch (IllegalArgumentException e) {
            return false;
        }
        try {
            Signature signature = Signature.getInstance(ED25519);
            signature.initVerify(publicKey);
            signature.update(canonical.getBytes(StandardCharsets.UTF_8));
            return signature.verify(raw);
        } catch (java.security.GeneralSecurityException e) {
            return false;
        }
    }

    /** 请求体摘要（Base64URL）；仅用于未来对 JSON 体做完整性校验。 */
    public static String bodyHash(byte[] body) {
        if (body == null || body.length == 0) {
            return EMPTY_BODY_HASH;
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(digest.digest(body));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    /** 查询串归一化：按 key 排序后原样拼接（不重新编码参数值）。 */
    static String normalizeQuery(String query) {
        if (query == null || query.isEmpty()) {
            return "";
        }
        TreeMap<String, String> sorted = new TreeMap<>();
        // 同 key 多次出现时保留首次值即可：网关与下游看到的是同一原始串，排序只为消除顺序差异
        for (String pair : query.split("&")) {
            if (pair.isEmpty()) {
                continue;
            }
            int idx = pair.indexOf('=');
            String key = idx < 0 ? pair : pair.substring(0, idx);
            String value = idx < 0 ? "" : pair.substring(idx + 1);
            sorted.putIfAbsent(key, value);
        }
        StringBuilder sb = new StringBuilder(query.length());
        sorted.forEach((k, v) -> {
            if (sb.length() > 0) {
                sb.append('&');
            }
            sb.append(k).append('=').append(v);
        });
        return sb.toString();
    }

    private static String headerValue(Map<String, String> headers, String name) {
        if (headers == null) {
            return "";
        }
        String value = headers.get(name);
        return value == null ? "" : value;
    }
}
