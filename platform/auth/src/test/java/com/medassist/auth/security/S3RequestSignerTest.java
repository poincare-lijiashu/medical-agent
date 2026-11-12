package com.medassist.auth.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * S3 SigV4 签名（§286）：控制面"探桶/建桶"能否被对象存储接受，全靠这里。
 *
 * <p>不校验 AWS 官方测试向量（本实现只覆盖桶级请求，且需要固定 canonical URI 行为），
 * 而是锁三件容易改坏的事：作用域与签名头齐全、签名确定、任何输入变化都会改变签名。
 * 真正与对象存储的兼容性由真实栈开通/恢复演练验证。
 */
class S3RequestSignerTest {

    private static final Instant FIXED = Instant.parse("2026-11-12T00:00:00Z");
    private static final String HOST = "127.0.0.1:9000";
    private static final String REGION = "us-east-1";
    private static final String KEY = "minioadmin";
    private static final String SECRET = "minioadmin";

    private final S3RequestSigner signer = new S3RequestSigner();

    @Test
    @DisplayName("Authorization 带正确作用域与签名头")
    void authorizationCarriesScopeAndSignedHeaders() {
        S3RequestSigner.Signed signed = signer.sign("HEAD", "/medassist-backup-tenant-003", HOST,
                REGION, KEY, SECRET, FIXED, S3RequestSigner.emptyPayloadHash());

        assertThat(signed.amzDate()).isEqualTo("20261112T000000Z");
        assertThat(signed.authorization())
                .startsWith("AWS4-HMAC-SHA256 Credential=minioadmin/20261112/us-east-1/s3/aws4_request")
                .contains("SignedHeaders=host;x-amz-content-sha256;x-amz-date")
                .contains("Signature=");
    }

    @Test
    @DisplayName("签名确定：同一输入稳定，路径/方法/主机任一变化都会变")
    void signatureIsDeterministicAndSensitive() {
        String base = signature("HEAD", "/bucket-a", HOST);

        assertThat(signature("HEAD", "/bucket-a", HOST)).isEqualTo(base);
        assertThat(signature("HEAD", "/bucket-b", HOST)).isNotEqualTo(base);
        assertThat(signature("PUT", "/bucket-a", HOST)).isNotEqualTo(base);
        assertThat(signature("HEAD", "/bucket-a", "minio.internal:9000")).isNotEqualTo(base);
    }

    @Test
    @DisplayName("规范化请求包含 host 与空负载哈希（改头必改签名）")
    void canonicalRequestShape() {
        String canonical = signer.canonicalRequest("HEAD", "/bucket-a", HOST, REGION, FIXED,
                S3RequestSigner.emptyPayloadHash());

        assertThat(canonical).startsWith("HEAD\n/bucket-a\n\nhost:127.0.0.1:9000\n");
        assertThat(canonical).contains("x-amz-content-sha256:" + S3RequestSigner.emptyPayloadHash());
        assertThat(canonical).contains("x-amz-date:20261112T000000Z");
        assertThat(canonical).endsWith(S3RequestSigner.emptyPayloadHash());
    }

    private String signature(String method, String path, String host) {
        String authorization = signer.sign(method, path, host, REGION, KEY, SECRET, FIXED,
                S3RequestSigner.emptyPayloadHash()).authorization();
        return authorization.substring(authorization.indexOf("Signature=") + "Signature=".length());
    }
}
