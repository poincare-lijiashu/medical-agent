package com.medassist.emr.service;

import com.medassist.common.api.ErrorCode;
import com.medassist.common.exception.BusinessException;
import com.medassist.emr.config.EmrProperties;
import com.medassist.emr.domain.EmrModel.CaSignature;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;

/**
 * CA 签名适配层（G41）。
 *
 * <p>mock 模式：本地 HMAC-SHA256（仅用于开发/演示，签名值可自校验，证书序列号为 MOCK-*）。
 * vendor 模式：调用配置的厂商 HTTP 接口 POST /sign、/verify，密钥只从部署配置注入，不落库。
 */
@Component
public class CaSignatureProvider {

    private final EmrProperties.Ca ca;

    /**
     * 构造期守卫（G53）。
     *
     * <p>原来的 {@code mode != "vendor"} 判定让"漏配一行"静默退化成 mock：
     * 生产环境用对称 HMAC 冒充 CA 签名，任何拿到共享密钥的人都能离线伪造
     * "合法"签名，而无纸化证据链看起来完全正常。这里把退化路径在启动期堵死：
     * <ul>
     *   <li>生产 profile 下 mode 不是 vendor → 拒绝启动；</li>
     *   <li>生产 profile 下 secret / api-key 为空 → 拒绝启动。</li>
     * </ul>
     * dev/test 仍允许 mock，便于本地演示。
     */
    public CaSignatureProvider(EmrProperties props, org.springframework.core.env.Environment environment) {
        this.ca = props.ca();
        if (isProduction(environment)) {
            if (isMock()) {
                throw new IllegalStateException(
                        "生产环境禁止 CA mock 模式：请设置 CA_MODE=vendor 并配置厂商地址与密钥");
            }
            if (blank(secret())) {
                throw new IllegalStateException("生产环境必须配置 CA_SECRET（对称验签密钥不得为空）");
            }
            if (blank(apiKey())) {
                throw new IllegalStateException("生产环境必须配置 CA_API_KEY");
            }
            if (ca != null && ca.tsaRequiredOrDefault() && !ca.tsaEnabled()) {
                throw new IllegalStateException(
                        "生产环境要求可信时间戳，但未配置 CA_TSA_URL：无时间戳的签名不能作为可靠电子签名证据");
            }
        }
    }

    private static boolean isProduction(org.springframework.core.env.Environment environment) {
        if (environment == null) {
            return false;
        }
        for (String profile : environment.getActiveProfiles()) {
            String p = profile == null ? "" : profile.trim().toLowerCase();
            if (p.equals("prod") || p.equals("production")) {
                return true;
            }
        }
        return false;
    }

    public String mode() {
        return isMock() ? "MOCK" : "VENDOR";
    }

    public CaSignResult sign(CaSignCommand command) {
        if (isMock()) {
            return mockSign(command);
        }
        return vendorSign(command);
    }

    public boolean verify(CaSignature signature) {
        if (isMock()) {
            return mockVerify(signature);
        }
        return vendorVerify(signature);
    }

    /**
     * 对"非病历签名"的任意内容验签（G44 知情同意书）。
     *
     * <p>mock 模式直接按同样 payload 重算 HMAC；vendor 模式用最小签名画像调用厂商 /verify。
     */
    public boolean verify(String contentHash, String signer, String signType, Instant signedAt,
                          String algorithm, String signatureValue) {
        if (isMock()) {
            if (!"HMAC-SHA256".equalsIgnoreCase(algorithm)) {
                return false;
            }
            String expected = hmac(payload(contentHash, signer, signType, signedAt));
            return constantTimeEquals(expected, signatureValue);
        }
        CaSignature synthetic = new CaSignature(UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), 0, signer, signType, contentHash, mode(), "", algorithm,
                signatureValue, signedAt, CaSignature.VALID, 0, null, null,
                null, null, null, signedAt);
        return vendorVerify(synthetic);
    }

    // ---------------------------------------------------------------- mock

    private CaSignResult mockSign(CaSignCommand command) {
        // PostgreSQL timestamptz 只保留微秒；签名时间必须先截断，
        // 否则写入后读回的时间精度变化会让 HMAC 重算永远不一致。
        Instant signedAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
        String value = hmac(payload(command.contentHash(), command.signer(),
                command.signType(), signedAt));
        return new CaSignResult("MOCK-" + command.signer(), value, "HMAC-SHA256", signedAt);
    }

    private boolean mockVerify(CaSignature signature) {
        if (!"HMAC-SHA256".equalsIgnoreCase(signature.algorithm())) {
            return false;
        }
        String expected = hmac(payload(signature.contentHash(), signature.signer(),
                signature.signType(), signature.signedAt()));
        return constantTimeEquals(expected, signature.signatureValue());
    }

    // ---------------------------------------------------------------- vendor

    private CaSignResult vendorSign(CaSignCommand command) {
        RestClient client = client();
        try {
            VendorSignResponse response = client.post()
                    .uri("/sign")
                    .header("X-Api-Key", apiKey())
                    .body(command)
                    .retrieve()
                    .body(VendorSignResponse.class);
            if (response == null || isBlank(response.certificateSerial())
                    || isBlank(response.signatureValue())) {
                throw new BusinessException(ErrorCode.CA_SIGNATURE_INVALID,
                        "CA 服务返回的签名结果不完整");
            }
            return new CaSignResult(response.certificateSerial(), response.signatureValue(),
                    isBlank(response.algorithm()) ? "SM2-SM3" : response.algorithm(),
                    response.signedAt() == null ? Instant.now() : response.signedAt());
        } catch (RestClientException e) {
            throw new BusinessException(ErrorCode.CA_PROVIDER_UNAVAILABLE,
                    "CA 签名服务不可用：" + e.getClass().getSimpleName());
        }
    }

    private boolean vendorVerify(CaSignature signature) {
        RestClient client = client();
        try {
            VendorVerifyResponse response = client.post()
                    .uri("/verify")
                    .header("X-Api-Key", apiKey())
                    .body(Map.of(
                            "contentHash", signature.contentHash(),
                            "signer", signature.signer(),
                            "signType", signature.signType(),
                            "signedAt", signature.signedAt().toString(),
                            "certificateSerial", signature.certificateSerial(),
                            "algorithm", signature.algorithm(),
                            "signatureValue", signature.signatureValue()))
                    .retrieve()
                    .body(VendorVerifyResponse.class);
            return response != null && Boolean.TRUE.equals(response.valid());
        } catch (RestClientException e) {
            throw new BusinessException(ErrorCode.CA_PROVIDER_UNAVAILABLE,
                    "CA 验签服务不可用：" + e.getClass().getSimpleName());
        }
    }

    // ---------------------------------------------------------------- 内部

    private RestClient client() {
        Duration timeout = ca == null || ca.timeout() == null ? Duration.ofSeconds(2) : ca.timeout();
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(timeout);
        factory.setReadTimeout(timeout);
        return RestClient.builder()
                .baseUrl(blank(ca == null ? null : ca.baseUrl()) ? "http://127.0.0.1:9098"
                        : ca.baseUrl())
                .requestFactory(factory)
                .build();
    }

    private boolean isMock() {
        return ca == null || !"vendor".equalsIgnoreCase(ca.mode());
    }

    private String apiKey() {
        return ca == null || ca.apiKey() == null ? "" : ca.apiKey();
    }

    /**
     * 对称验签密钥（G53）。
     *
     * <p>不再回退到源码里的硬编码常量——"代码里有一条默认密钥"意味着任何
     * 拿到仓库的人都能为任意文书伪造签名。缺失时由 {@link #hmac} 显式报错，
     * dev 的默认值改由配置文件提供（{@code application.yml} 的 {@code CA_SECRET}）。
     */
    private String secret() {
        return ca == null || ca.secret() == null ? "" : ca.secret();
    }

    private String payload(String hash, String signer, String signType, Instant signedAt) {
        return hash + "|" + signer + "|" + signType + "|" + signedAt;
    }

    private String hmac(String payload) {
        if (blank(secret())) {
            throw new BusinessException(ErrorCode.CA_SIGNATURE_INVALID,
                    "CA 签名密钥未配置（CA_SECRET），拒绝生成签名");
        }
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret().getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return Base64.getEncoder().encodeToString(
                    mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new BusinessException(ErrorCode.CA_SIGNATURE_INVALID, "CA 本地签名初始化失败");
        }
    }

    private static boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null) {
            return false;
        }
        byte[] x = a.getBytes(StandardCharsets.UTF_8);
        byte[] y = b.getBytes(StandardCharsets.UTF_8);
        if (x.length != y.length) {
            return false;
        }
        int diff = 0;
        for (int i = 0; i < x.length; i++) {
            diff |= x[i] ^ y[i];
        }
        return diff == 0;
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    public record CaSignCommand(String recordNo, String contentHash, String signer,
                                String signType) {
    }

    public record CaSignResult(String certificateSerial, String signatureValue, String algorithm,
                               Instant signedAt, String tsaUrl, String timestampToken,
                               Instant timestampTime) {

        /** 无时间戳结果（mock/vendor 未配置 TSA 时）。 */
        public CaSignResult(String certificateSerial, String signatureValue, String algorithm,
                            Instant signedAt) {
            this(certificateSerial, signatureValue, algorithm, signedAt, null, null, null);
        }

        public boolean timestamped() {
            return timestampToken != null && !timestampToken.isBlank();
        }
    }

    private record VendorSignResponse(String certificateSerial, String signatureValue,
                                      String algorithm, Instant signedAt) {
    }

    private record VendorVerifyResponse(Boolean valid) {
    }
}
