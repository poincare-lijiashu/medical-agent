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

/**
 * CA 签名适配层（G41）。
 *
 * <p>mock 模式：本地 HMAC-SHA256（仅用于开发/演示，签名值可自校验，证书序列号为 MOCK-*）。
 * vendor 模式：调用配置的厂商 HTTP 接口 POST /sign、/verify，密钥只从部署配置注入，不落库。
 */
@Component
public class CaSignatureProvider {

    private final EmrProperties.Ca ca;

    public CaSignatureProvider(EmrProperties props) {
        this.ca = props.ca();
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

    private String secret() {
        return ca == null || ca.secret() == null ? "medassist-dev-ca-secret" : ca.secret();
    }

    private String payload(String hash, String signer, String signType, Instant signedAt) {
        return hash + "|" + signer + "|" + signType + "|" + signedAt;
    }

    private String hmac(String payload) {
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
                               Instant signedAt) {
    }

    private record VendorSignResponse(String certificateSerial, String signatureValue,
                                      String algorithm, Instant signedAt) {
    }

    private record VendorVerifyResponse(Boolean valid) {
    }
}
