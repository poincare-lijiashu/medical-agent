package com.medassist.emr.service;

import com.medassist.emr.config.EmrProperties;
import com.medassist.emr.domain.EmrModel.CaSignature;
import com.medassist.emr.service.CaSignatureProvider.CaSignCommand;
import com.medassist.emr.service.CaSignatureProvider.CaSignResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Mock CA 适配层：签名后经数据库微秒精度往返仍能验签。
 *
 * <p>PostgreSQL timestamptz 精度为微秒，{@code Instant.now()} 的纳秒部分写入后会被截断；
 * 若签名时不截断，读回再重算 HMAC 必然不一致，导致所有真实签名验签失败。
 */
class CaSignatureProviderTest {

    private final CaSignatureProvider provider = new CaSignatureProvider(
            new EmrProperties(null, null, null,
                    new EmrProperties.Ca("mock", "http://127.0.0.1:9098", "",
                            "medassist-dev-ca-secret", Duration.ofSeconds(2))));

    @Test
    @DisplayName("mock 签名：截断到微秒后验签通过（模拟 DB 往返）")
    void mockSignVerifiesAfterMicrosecondRoundTrip() {
        CaSignCommand command = new CaSignCommand("MR20261104000001",
                "d6eaf1093b2d9fb6425543142c415cbca0fdd5ec99f95e6f2b56bebc6c866a4c",
                "doctor01", "AUTHOR");
        CaSignResult result = provider.sign(command);

        assertThat(result.algorithm()).isEqualTo("HMAC-SHA256");
        assertThat(result.certificateSerial()).isEqualTo("MOCK-doctor01");
        assertThat(result.signedAt().getNano() % 1000).isZero();

        CaSignature stored = signature(command, result, result.signedAt());
        assertThat(provider.verify(stored)).isTrue();
    }

    @Test
    @DisplayName("mock 验签：签名值被篡改时失败")
    void mockVerifyRejectsTamperedValue() {
        CaSignCommand command = new CaSignCommand("MR20261104000002", "hash", "doctor01", "AUTHOR");
        CaSignResult result = provider.sign(command);
        CaSignature tampered = signature(command, result, result.signedAt().plusSeconds(1));

        assertThat(provider.verify(tampered)).isFalse();
    }

    private static CaSignature signature(CaSignCommand command, CaSignResult result, Instant signedAt) {
        return new CaSignature(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), 1,
                command.signer(), command.signType(), command.contentHash(), "MOCK",
                result.certificateSerial(), result.algorithm(), result.signatureValue(),
                signedAt.truncatedTo(ChronoUnit.MICROS), CaSignature.VALID, 0, null, null,
                null, null, null, Instant.now());
    }
}
