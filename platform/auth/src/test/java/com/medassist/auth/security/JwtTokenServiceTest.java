package com.medassist.auth.security;

import com.medassist.auth.domain.UserAccount;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * JWT 签发/校验核心行为测试（不依赖 Spring 容器与数据库）。
 */
class JwtTokenServiceTest {

    private static final String SECRET = "unit-test-secret-0123456789abcdefghijklmnop";
    private static final String ISSUER = "medassist-auth";

    private static final UserAccount USER = new UserAccount(
            UUID.fromString("11111111-2222-3333-4444-555555555555"),
            "zhangsan", "$2a$10$abcdefghijklmnopqrstuv", "张三", "E1001", "DEPT-01",
            UserAccount.STATUS_ACTIVE, 0L, null, Instant.now(), Instant.now());

    @Test
    void issueAndVerifyRoundTrip() {
        JwtTokenService service = new JwtTokenService(
                new JwtProperties(ISSUER, SECRET, Duration.ofHours(8)));

        JwtTokenService.IssuedToken issued = service.issue(USER, List.of("DOCTOR", "NURSE"));

        assertThat(issued.token()).isNotBlank();
        assertThat(issued.expiresInSeconds()).isBetween(8 * 3600 - 5L, 8 * 3600L);

        JwtTokenService.TokenClaims claims = service.verify(issued.token()).orElseThrow();
        assertThat(claims.userId()).isEqualTo(USER.id().toString());
        assertThat(claims.username()).isEqualTo("zhangsan");
        assertThat(claims.displayName()).isEqualTo("张三");
        assertThat(claims.roles()).containsExactly("DOCTOR", "NURSE");
        assertThat(claims.orgCode()).isEqualTo("DEPT-01");
        assertThat(claims.tokenId()).isEqualTo(issued.tokenId());
    }

    @Test
    void tamperedTokenRejected() {
        JwtTokenService service = new JwtTokenService(
                new JwtProperties(ISSUER, SECRET, Duration.ofHours(8)));
        String token = service.issue(USER, List.of("ADMIN")).token();

        // 篡改签名段（保留结构，改变最后 4 个字符）
        String tampered = token.substring(0, token.length() - 4) + "AAAA";

        assertThat(service.verify(tampered)).isEmpty();
        assertThat(service.verify("not-a-jwt")).isEmpty();
        assertThat(service.verify("")).isEmpty();
        assertThat(service.verify(null)).isEmpty();
    }

    @Test
    void tokenSignedByOtherSecretRejected() {
        JwtTokenService issuer = new JwtTokenService(
                new JwtProperties(ISSUER, "another-secret-0123456789abcdefghijklmnop", Duration.ofHours(8)));
        JwtTokenService verifier = new JwtTokenService(
                new JwtProperties(ISSUER, SECRET, Duration.ofHours(8)));

        assertThat(verifier.verify(issuer.issue(USER, List.of("ADMIN")).token())).isEmpty();
    }

    @Test
    void tokenFromOtherIssuerRejected() {
        JwtTokenService otherIssuer = new JwtTokenService(
                new JwtProperties("legacy-ai-platform", SECRET, Duration.ofHours(8)));
        JwtTokenService verifier = new JwtTokenService(
                new JwtProperties(ISSUER, SECRET, Duration.ofHours(8)));

        assertThat(verifier.verify(otherIssuer.issue(USER, List.of("ADMIN")).token())).isEmpty();
    }

    @Test
    void expiredTokenRejected() throws Exception {
        JwtTokenService verifier = new JwtTokenService(
                new JwtProperties(ISSUER, SECRET, Duration.ofHours(8)));

        // 签发器不允许 exp 早于 iat，这里用 Nimbus 直接构造一枚"已过期"的合法签名令牌
        Instant expiredAt = Instant.now().minusSeconds(600);
        com.nimbusds.jwt.JWTClaimsSet claims = new com.nimbusds.jwt.JWTClaimsSet.Builder()
                .issuer(ISSUER)
                .subject(USER.id().toString())
                .issueTime(java.util.Date.from(expiredAt.minusSeconds(600)))
                .expirationTime(java.util.Date.from(expiredAt))
                .claim("username", USER.username())
                .claim("roles", List.of("ADMIN"))
                .build();
        com.nimbusds.jwt.SignedJWT signed = new com.nimbusds.jwt.SignedJWT(
                new com.nimbusds.jose.JWSHeader(com.nimbusds.jose.JWSAlgorithm.HS256), claims);
        signed.sign(new com.nimbusds.jose.crypto.MACSigner(SECRET.getBytes(java.nio.charset.StandardCharsets.UTF_8)));

        assertThat(verifier.verify(signed.serialize())).isEmpty();
    }

    @Test
    void shortSecretRejectedAtStartup() {
        assertThatThrownBy(() -> new JwtTokenService(new JwtProperties(ISSUER, "too-short", Duration.ofHours(1))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("JWT");
        assertThatThrownBy(() -> new JwtTokenService(new JwtProperties(ISSUER, null, Duration.ofHours(1))))
                .isInstanceOf(IllegalStateException.class);
    }
}
