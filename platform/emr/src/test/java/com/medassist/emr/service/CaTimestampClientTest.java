package com.medassist.emr.service;

import com.medassist.common.api.ErrorCode;
import com.medassist.common.exception.BusinessException;
import com.medassist.emr.config.EmrProperties;
import com.sun.net.httpserver.HttpServer;
import org.bouncycastle.asn1.ASN1ObjectIdentifier;
import org.bouncycastle.asn1.oiw.OIWObjectIdentifiers;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.AlgorithmIdentifier;
import org.bouncycastle.asn1.x509.BasicConstraints;
import org.bouncycastle.asn1.x509.ExtendedKeyUsage;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.KeyPurposeId;
import org.bouncycastle.asn1.x509.KeyUsage;
import org.bouncycastle.cert.X509CertificateHolder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.cms.jcajce.JcaSignerInfoGeneratorBuilder;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.DigestCalculator;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.bouncycastle.operator.jcajce.JcaDigestCalculatorProviderBuilder;
import org.bouncycastle.tsp.TSPAlgorithms;
import org.bouncycastle.tsp.TimeStampRequest;
import org.bouncycastle.tsp.TimeStampResponse;
import org.bouncycastle.tsp.TimeStampResponseGenerator;
import org.bouncycastle.tsp.TimeStampTokenGenerator;
import org.bouncycastle.cert.jcajce.JcaCertStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Security;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * RFC 3161 时间戳客户端：用本机 TSA（BouncyCastle 生成真实令牌）验证请求/响应与绑定关系。
 *
 * <p>关键断言：签发的令牌能验签；签名值被篡改后必须失败；未配置 TSA 时不伪造时间戳。
 */
class CaTimestampClientTest {

    static {
        if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
            Security.addProvider(new BouncyCastleProvider());
        }
    }

    private HttpServer tsa;
    private int port;

    @BeforeEach
    void startTsa() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        KeyPair keyPair = generator.generateKeyPair();
        X500Name subject = new X500Name("CN=MedAssist Test TSA");
        Date notBefore = Date.from(Instant.now().minusSeconds(3600));
        Date notAfter = Date.from(Instant.now().plusSeconds(86400));
        JcaX509v3CertificateBuilder certBuilder = new JcaX509v3CertificateBuilder(
                subject, BigInteger.valueOf(System.currentTimeMillis()),
                notBefore, notAfter, subject, keyPair.getPublic());
        certBuilder.addExtension(Extension.basicConstraints, true, new BasicConstraints(false));
        certBuilder.addExtension(Extension.keyUsage, true,
                new KeyUsage(KeyUsage.digitalSignature | KeyUsage.nonRepudiation));
        // RFC 3161 要求 TSA 证书带 id-kp-timeStamping 扩展
        certBuilder.addExtension(Extension.extendedKeyUsage, true,
                new ExtendedKeyUsage(KeyPurposeId.id_kp_timeStamping));
        ContentSigner certSigner = new JcaContentSignerBuilder("SHA256withRSA").build(
                keyPair.getPrivate());
        X509CertificateHolder holder = certBuilder.build(certSigner);
        // 用 JDK 的 X509Certificate 实现：BC 的证书实现不解析 ExtendedKeyUsage，
        // 会被 TimeStampTokenGenerator 误判为"缺少 EKU 扩展"。
        X509Certificate cert = new JcaX509CertificateConverter().getCertificate(holder);

        ContentSigner tsaSigner = new JcaContentSignerBuilder("SHA256withRSA").build(
                keyPair.getPrivate());
        DigestCalculator digestCalculator = new JcaDigestCalculatorProviderBuilder().build()
                .get(new AlgorithmIdentifier(OIWObjectIdentifiers.idSHA1));
        TimeStampTokenGenerator tokenGenerator = new TimeStampTokenGenerator(
                new JcaSignerInfoGeneratorBuilder(new JcaDigestCalculatorProviderBuilder().build())
                        .build(tsaSigner, cert),
                digestCalculator, new ASN1ObjectIdentifier("1.2.3.4.1"));
        tokenGenerator.addCertificates(new JcaCertStore(List.of(cert)));
        TimeStampResponseGenerator responseGenerator = new TimeStampResponseGenerator(
                tokenGenerator, TSPAlgorithms.ALLOWED);
        AtomicLong serial = new AtomicLong(1);

        tsa = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        tsa.createContext("/tsa", exchange -> {
            System.out.println("TSA request " + exchange.getRequestMethod() + " "
                    + exchange.getRequestURI());
            try {
                byte[] requestBytes = exchange.getRequestBody().readAllBytes();
                TimeStampRequest request = new TimeStampRequest(requestBytes);
                TimeStampResponse response = responseGenerator.generate(request,
                        BigInteger.valueOf(serial.getAndIncrement()), new Date());
                byte[] bytes = response.getEncoded();
                exchange.getResponseHeaders().add("Content-Type", "application/timestamp-reply");
                exchange.sendResponseHeaders(200, bytes.length);
                exchange.getResponseBody().write(bytes);
            } catch (Exception e) {
                exchange.sendResponseHeaders(500, -1);
            } finally {
                exchange.close();
            }
        });
        tsa.start();
        port = tsa.getAddress().getPort();
    }

    @AfterEach
    void stopTsa() {
        if (tsa != null) {
            tsa.stop(0);
        }
    }

    @Test
    @DisplayName("申请时间戳并验签：令牌与签名值绑定，篡改后验签失败")
    void timestampAndVerify() {
        CaTimestampClient client = client("http://127.0.0.1:" + port + "/tsa");
        byte[] signature = "SIG-001".getBytes(StandardCharsets.UTF_8);

        CaTimestampClient.TimestampEvidence evidence = client.timestamp(signature);

        assertThat(evidence).isNotNull();
        assertThat(evidence.token()).isNotBlank();
        assertThat(evidence.genTime()).isNotNull();
        assertThat(client.verify(signature, evidence.token())).isNotNull();

        byte[] tampered = "SIG-002".getBytes(StandardCharsets.UTF_8);
        assertThatThrownBy(() -> client.verify(tampered, evidence.token()))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.CA_VERIFY_FAILED);
    }

    @Test
    @DisplayName("未配置 TSA：不返回伪造时间戳；非法令牌明确失败")
    void disabledAndInvalidToken() {
        CaTimestampClient disabled = client("");
        assertThat(disabled.enabled()).isFalse();
        assertThat(disabled.timestamp("SIG".getBytes(StandardCharsets.UTF_8))).isNull();

        CaTimestampClient client = client("http://127.0.0.1:" + port);
        assertThatThrownBy(() -> client.verify("SIG".getBytes(StandardCharsets.UTF_8), "not-a-token"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.CA_VERIFY_FAILED);
    }

    private CaTimestampClient client(String tsaUrl) {
        EmrProperties properties = new EmrProperties(null, null, null,
                new EmrProperties.Ca("mock", "http://127.0.0.1:9098", "", "secret",
                        Duration.ofSeconds(2), tsaUrl, true, Duration.ofSeconds(5)));
        return new CaTimestampClient(properties);
    }
}
