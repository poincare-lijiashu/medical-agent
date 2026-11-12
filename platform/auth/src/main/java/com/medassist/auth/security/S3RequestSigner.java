package com.medassist.auth.security;

import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;

/**
 * AWS Signature V4 签名器（第 286 批，仅覆盖 S3 的 HEAD/PUT 桶级请求）。
 *
 * <p>为什么手写而不是引 SDK：控制面只需要"探桶/建桶"两个动作，引入 AWS SDK
 * 会给 auth 增加数十 MB 依赖与版本面；而 SigV4 的规范稳定、可单测。
 * 这里只实现校验必需的最小集合（host / x-amz-content-sha256 / x-amz-date），
 * <b>不支持</b>查询串、临时凭据（STS）与分块上传——需要时再评估引 SDK。
 *
 * <p>安全：密钥只来自部署 Secret，签名器不记录、不返回任何密钥片段。
 */
@Component
public class S3RequestSigner {

    private static final DateTimeFormatter AMZ_DATE =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);

    private static final String ALGORITHM = "AWS4-HMAC-SHA256";
    private static final String SERVICE = "s3";
    private static final String EMPTY_SHA256 =
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";

    /** 空负载（HEAD/PUT 桶）时的 SHA256；与 {@link #sha256Hex(String)} 的结果一致。 */
    public static String emptyPayloadHash() {
        return EMPTY_SHA256;
    }

    /**
     * 生成 Authorization 头。
     *
     * @param method     HTTP 方法（GET/HEAD/PUT/POST/DELETE）
     * @param canonicalUri 以 {@code /} 开头、已编码的路径（桶级请求为 {@code /<bucket>}）
     * @param host       与 HTTP Host 头完全一致（含非默认端口）
     * @param region     区域
     * @param accessKey  访问密钥 ID
     * @param secretKey  访问密钥
     * @param now        签名时刻（测试可注入固定值）
     * @param payloadHash 负载 SHA256 十六进制
     */
    public Signed sign(String method, String canonicalUri, String host, String region,
                       String accessKey, String secretKey, Instant now, String payloadHash) {
        String amzDate = AMZ_DATE.format(now);
        String dateStamp = amzDate.substring(0, 8);
        String scope = dateStamp + "/" + region + "/" + SERVICE + "/aws4_request";

        String canonicalHeaders = "host:" + host + "\n"
                + "x-amz-content-sha256:" + payloadHash + "\n"
                + "x-amz-date:" + amzDate + "\n";
        String signedHeaders = "host;x-amz-content-sha256;x-amz-date";
        String canonicalRequest = method + "\n" + canonicalUri + "\n" + "\n"
                + canonicalHeaders + "\n" + signedHeaders + "\n" + payloadHash;

        String stringToSign = ALGORITHM + "\n" + amzDate + "\n" + scope + "\n"
                + sha256Hex(canonicalRequest);
        byte[] signingKey = signingKey(secretKey, dateStamp, region);
        String signature = HexFormat.of().formatHex(hmacSha256(signingKey, stringToSign));

        String authorization = ALGORITHM + " Credential=" + accessKey + "/" + scope
                + ", SignedHeaders=" + signedHeaders + ", Signature=" + signature;
        return new Signed(authorization, amzDate, payloadHash);
    }

    public static String sha256Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    /** 校验用：把规范化请求暴露出来，单测可以断言"改一个头签名就变"。 */
    public String canonicalRequest(String method, String canonicalUri, String host, String region,
                                   Instant now, String payloadHash) {
        String amzDate = AMZ_DATE.format(now);
        return method + "\n" + canonicalUri + "\n" + "\n"
                + "host:" + host + "\n"
                + "x-amz-content-sha256:" + payloadHash + "\n"
                + "x-amz-date:" + amzDate + "\n\n"
                + "host;x-amz-content-sha256;x-amz-date\n" + payloadHash;
    }

    private static byte[] signingKey(String secretKey, String dateStamp, String region) {
        byte[] dateKey = hmacSha256(("AWS4" + secretKey).getBytes(StandardCharsets.UTF_8), dateStamp);
        byte[] regionKey = hmacSha256(dateKey, region);
        byte[] serviceKey = hmacSha256(regionKey, SERVICE);
        return hmacSha256(serviceKey, "aws4_request");
    }

    private static byte[] hmacSha256(byte[] key, String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException("HmacSHA256 不可用", e);
        }
    }

    public record Signed(String authorization, String amzDate, String payloadHash) {
    }
}
