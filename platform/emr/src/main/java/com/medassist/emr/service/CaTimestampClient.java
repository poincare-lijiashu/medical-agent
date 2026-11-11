package com.medassist.emr.service;

import com.medassist.common.api.ErrorCode;
import com.medassist.common.exception.BusinessException;
import com.medassist.emr.config.EmrProperties;
import org.bouncycastle.asn1.ASN1ObjectIdentifier;
import org.bouncycastle.cert.X509CertificateHolder;
import org.bouncycastle.cms.SignerInformation;
import org.bouncycastle.cms.jcajce.JcaSimpleSignerInfoVerifierBuilder;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.tsp.TSPAlgorithms;
import org.bouncycastle.tsp.TimeStampRequest;
import org.bouncycastle.tsp.TimeStampRequestGenerator;
import org.bouncycastle.tsp.TimeStampResponse;
import org.bouncycastle.tsp.TimeStampToken;
import org.bouncycastle.tsp.TimeStampTokenInfo;
import org.bouncycastle.util.Store;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.Security;
import java.time.Instant;
import java.util.Base64;
import java.util.Collection;
import java.util.concurrent.ThreadLocalRandom;

/**
 * RFC 3161 可信时间戳客户端（TSP over HTTP）。
 *
 * <p>为什么需要：CA 签名只证明"谁签的"，不证明"什么时候签的"；
 * WS/T 847—2024 与电子病历规范要求关键文书具备**可信时间源**，
 * 否则签名时间可由本机时钟回拨伪造。
 *
 * <p>实现边界：
 * <ul>
 *   <li>时间戳请求对**签名值字节**做 SHA-256 摘要，令牌与签名值绑定；</li>
 *   <li>验签时同时校验：消息摘要一致 + TSA 令牌自身签名有效；</li>
 *   <li>TSA 证书链信任锚由院方 CA 体系决定，本客户端只校验令牌内证书签名，
 *       完整的链信任与吊销检查在院方 TSA/CA 联调时按信任库补（见 README）。</li>
 * </ul>
 */
@Component
public class CaTimestampClient {

    private static final Logger log = LoggerFactory.getLogger(CaTimestampClient.class);
    private static final MediaType TSP_QUERY = MediaType.parseMediaType("application/timestamp-query");
    private static final MediaType TSP_REPLY = MediaType.parseMediaType("application/timestamp-reply");

    static {
        if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
            Security.addProvider(new BouncyCastleProvider());
        }
    }

    private final EmrProperties.Ca ca;
    private final RestClient client;

    public CaTimestampClient(EmrProperties properties) {
        this.ca = properties.ca();
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        java.time.Duration timeout = ca == null ? java.time.Duration.ofSeconds(5)
                : ca.tsaTimeoutOrDefault();
        factory.setConnectTimeout(timeout);
        factory.setReadTimeout(timeout);
        this.client = RestClient.builder().requestFactory(factory).build();
    }

    public boolean enabled() {
        return ca != null && ca.tsaEnabled();
    }

    /** 是否强制时间戳（生产建议 true）。 */
    public boolean required() {
        return ca != null && ca.tsaRequiredOrDefault();
    }

    /**
     * 为签名值申请时间戳；未配置 TSA 时返回 null（是否强制由服务层决定）。
     */
    public TimestampEvidence timestamp(byte[] signatureBytes) {
        if (!enabled()) {
            return null;
        }
        try {
            TimeStampRequestGenerator generator = new TimeStampRequestGenerator();
            generator.setCertReq(true);
            byte[] imprint = sha256(signatureBytes);
            TimeStampRequest request = generator.generate(TSPAlgorithms.SHA256, imprint,
                    BigInteger.valueOf(ThreadLocalRandom.current().nextLong() & Long.MAX_VALUE));

            byte[] responseBytes = client.post()
                    .uri(java.net.URI.create(ca.tsaUrl().trim()))
                    .contentType(TSP_QUERY)
                    .accept(TSP_REPLY)
                    .body(request.getEncoded())
                    .retrieve()
                    .body(byte[].class);
            TimeStampResponse response = new TimeStampResponse(responseBytes);
            response.validate(request);
            TimeStampToken token = response.getTimeStampToken();
            if (token == null) {
                throw new BusinessException(ErrorCode.CA_PROVIDER_UNAVAILABLE,
                        "可信时间戳服务未返回时间戳令牌");
            }
            TimeStampTokenInfo info = token.getTimeStampInfo();
            return new TimestampEvidence(ca.tsaUrl().trim(),
                    Base64.getEncoder().encodeToString(token.getEncoded()),
                    info.getGenTime().toInstant());
        } catch (BusinessException e) {
            throw e;
        } catch (RestClientException e) {
            throw new BusinessException(ErrorCode.CA_PROVIDER_UNAVAILABLE,
                    "可信时间戳服务不可用：" + e.getClass().getSimpleName());
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.CA_PROVIDER_UNAVAILABLE,
                    "可信时间戳请求失败：" + e.getMessage());
        }
    }

    /**
     * 校验时间戳令牌与签名值的绑定关系，并验证令牌自身签名。
     *
     * @return 时间戳签发时间；令牌无效时抛 {@link ErrorCode#CA_VERIFY_FAILED}
     */
    public Instant verify(byte[] signatureBytes, String tokenBase64) {
        if (tokenBase64 == null || tokenBase64.isBlank()) {
            throw new BusinessException(ErrorCode.CA_VERIFY_FAILED, "缺少可信时间戳令牌");
        }
        try {
            TimeStampToken token = new TimeStampToken(
                    new org.bouncycastle.cms.CMSSignedData(
                            Base64.getDecoder().decode(tokenBase64)));
            TimeStampTokenInfo info = token.getTimeStampInfo();
            byte[] expected = digest(info.getHashAlgorithm().getAlgorithm(), signatureBytes);
            if (!MessageDigest.isEqual(expected, info.getMessageImprintDigest())) {
                throw new BusinessException(ErrorCode.CA_VERIFY_FAILED,
                        "时间戳令牌与签名值不匹配（内容可能已被篡改）");
            }
            Store<X509CertificateHolder> certificates = token.getCertificates();
            Collection<SignerInformation> signers = token.toCMSSignedData().getSignerInfos()
                    .getSigners();
            if (signers.isEmpty()) {
                throw new BusinessException(ErrorCode.CA_VERIFY_FAILED, "时间戳令牌没有签名者");
            }
            SignerInformation signer = signers.iterator().next();
            Collection<X509CertificateHolder> matches = certificates.getMatches(signer.getSID());
            if (matches.isEmpty()) {
                throw new BusinessException(ErrorCode.CA_VERIFY_FAILED,
                        "时间戳令牌缺少 TSA 证书，无法验证签名");
            }
            X509CertificateHolder holder = matches.iterator().next();
            boolean valid = signer.verify(new JcaSimpleSignerInfoVerifierBuilder()
                    .setProvider(BouncyCastleProvider.PROVIDER_NAME)
                    .build(holder));
            if (!valid) {
                throw new BusinessException(ErrorCode.CA_VERIFY_FAILED, "时间戳令牌签名验证失败");
            }
            return info.getGenTime().toInstant();
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.CA_VERIFY_FAILED,
                    "时间戳令牌解析失败：" + e.getMessage());
        }
    }

    private static byte[] sha256(byte[] value) throws Exception {
        return MessageDigest.getInstance("SHA-256").digest(value);
    }

    private static byte[] digest(ASN1ObjectIdentifier algorithm, byte[] value) throws Exception {
        String jca = jcaDigestName(algorithm);
        return MessageDigest.getInstance(jca).digest(value);
    }

    private static String jcaDigestName(ASN1ObjectIdentifier oid) {
        String id = oid.getId();
        if (id.equals(TSPAlgorithms.SHA256.getId())) {
            return "SHA-256";
        }
        if (id.equals(TSPAlgorithms.SHA1.getId())) {
            return "SHA-1";
        }
        if (id.equals(TSPAlgorithms.SHA384.getId())) {
            return "SHA-384";
        }
        if (id.equals(TSPAlgorithms.SHA512.getId())) {
            return "SHA-512";
        }
        throw new BusinessException(ErrorCode.CA_VERIFY_FAILED,
                "不支持的时间戳摘要算法：" + id);
    }

    /** 时间戳证据（令牌原文 + 签发时间）；令牌本身是长期验证证据，不能只存时间。 */
    public record TimestampEvidence(String tsaUrl, String token, Instant genTime) {

        public byte[] tokenBytes() {
            return token == null ? new byte[0]
                    : token.getBytes(StandardCharsets.UTF_8);
        }
    }
}
