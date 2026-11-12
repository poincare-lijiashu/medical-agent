package com.medassist.auth.service;

import com.medassist.auth.domain.OAuthClient;
import com.medassist.auth.repo.OAuthClientRepository;
import com.medassist.auth.security.JwtProperties;
import com.medassist.auth.security.JwtTokenService;
import com.medassist.common.api.ErrorCode;
import com.medassist.common.exception.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 开放平台客户端语义：密钥只存哈希、只读范围、缩权换令牌、停用即拒绝。
 */
class OAuthClientServiceTest {

    private static final UUID CID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final String CLIENT_ID = "cli_0123456789abcdef";

    private OAuthClientRepository repo;
    private JwtTokenService jwt;
    private OAuthClientService service;

    @BeforeEach
    void setUp() {
        repo = Mockito.mock(OAuthClientRepository.class);
        jwt = new JwtTokenService(new JwtProperties("medassist-auth",
                "x".repeat(40), Duration.ofMinutes(30), Duration.ofDays(7)));
        service = new OAuthClientService(repo, jwt, new ObjectMapper());
        // create 会生成随机 UUID 再回读；rotate 用固定 CID，两者都走 any()
        when(repo.findById(any())).thenAnswer(inv -> Optional.of(activeClient(Instant.now().plus(Duration.ofDays(365)))));
    }

    @Test
    void create_returns_plaintext_once_and_stores_only_hash() {
        OAuthClientService.Created created = service.create("科研报表平台", "只读访问病案首页汇总",
                List.of("fhir:read", "clinical:visit:read"), 900, null, "admin01");

        assertThat(created.secret()).hasSizeGreaterThan(30);
        ArgumentCaptor<String> hash = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> hint = ArgumentCaptor.forClass(String.class);
        verify(repo).insert(any(), anyString(), anyString(), any(), hash.capture(), hint.capture(),
                anyList(), anyInt(), any(), anyString());
        assertThat(hash.getValue()).hasSize(64).isNotEqualTo(created.secret());
        assertThat(hint.getValue()).contains("…");
    }

    @Test
    void write_scopes_are_rejected_outright() {
        assertThatThrownBy(() -> service.create("越权客户端", null,
                List.of("pharmacy:drug:manage"), 900, null, "admin01"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.OAUTH_SCOPE_NOT_ALLOWED);
    }

    @Test
    void wrong_secret_is_rejected() {
        when(repo.findForAuth(CLIENT_ID)).thenReturn(Optional.of(
                new OAuthClientRepository.StoredClient(activeClient(null), sha256("right-secret"))));

        assertThatThrownBy(() -> service.issueToken(CLIENT_ID, "wrong-secret", null))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.OAUTH_SECRET_INVALID);
    }

    @Test
    void disabled_or_expired_client_cannot_get_token() {
        when(repo.findForAuth(CLIENT_ID)).thenReturn(Optional.of(
                new OAuthClientRepository.StoredClient(activeClient(null), sha256("s3cret"))));
        OAuthClient expired = new OAuthClient(CID, CLIENT_ID, "已过期", null, "hint",
                List.of("fhir:read"), "ACTIVE", 900, Instant.now().minus(Duration.ofDays(1)),
                null, "admin01", Instant.now(), null, Instant.now());
        when(repo.findForAuth(CLIENT_ID)).thenReturn(Optional.of(
                new OAuthClientRepository.StoredClient(expired, sha256("s3cret"))));

        assertThatThrownBy(() -> service.issueToken(CLIENT_ID, "s3cret", null))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.OAUTH_CLIENT_DISABLED);
    }

    @Test
    void token_carries_only_granted_read_scopes() {
        when(repo.findForAuth(CLIENT_ID)).thenReturn(Optional.of(
                new OAuthClientRepository.StoredClient(activeClient(null), sha256("s3cret"))));

        OAuthClientService.TokenIssued issued = service.issueToken(CLIENT_ID, "s3cret", "fhir:read");

        assertThat(issued.tokenType()).isEqualTo("Bearer");
        assertThat(issued.expiresIn()).isBetween(1L, 900L);
        assertThat(issued.scopes()).containsExactly("fhir:read");

        JwtTokenService.TokenClaims claims = jwt.verify(issued.accessToken()).orElseThrow();
        assertThat(claims.username()).isEqualTo(CLIENT_ID);
        assertThat(claims.roles()).containsExactly("INTEGRATION_CLIENT");
        assertThat(claims.permissions()).containsExactly("fhir:read");
        assertThat(claims.orgCode()).isNull();
    }

    @Test
    void scope_cannot_be_widened_via_request_param() {
        when(repo.findForAuth(CLIENT_ID)).thenReturn(Optional.of(
                new OAuthClientRepository.StoredClient(activeClient(null), sha256("s3cret"))));

        assertThatThrownBy(() -> service.issueToken(CLIENT_ID, "s3cret", "audit:log:read"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.OAUTH_SCOPE_NOT_ALLOWED);
    }

    @Test
    void rotate_replaces_hash_and_returns_new_secret() {
        when(repo.findById(CID)).thenReturn(Optional.of(activeClient(null)));

        String secret = service.rotate(CID, "admin01");

        assertThat(secret).hasSizeGreaterThan(30);
        ArgumentCaptor<String> hash = ArgumentCaptor.forClass(String.class);
        verify(repo).updateSecret(any(), hash.capture(), anyString(), anyString());
        assertThat(hash.getValue()).hasSize(64).isEqualTo(sha256(secret));
    }

    @Test
    void scope_catalog_contains_no_write_permission() {
        assertThat(service.scopeCatalogView().keySet()).allSatisfy(scope ->
                assertThat(scope).matches(".*:(read|read_phi|search|answer)$"));
    }

    /* ------------------------------------------------------------------ fixtures */

    private OAuthClient activeClient(Instant expiresAt) {
        return new OAuthClient(CID, CLIENT_ID, "科研报表平台", "只读", "abc123…def0",
                List.of("fhir:read", "clinical:visit:read"), "ACTIVE", 900, expiresAt,
                null, "admin01", Instant.now(), null, Instant.now());
    }

    private static String sha256(String value) {
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
