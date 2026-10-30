package com.medassist.gateway.filter;

import com.medassist.common.security.AuthHeaders;
import com.medassist.gateway.config.GatewayAuthConfig;
import com.medassist.gateway.config.GatewayAuthProperties;
import com.nimbusds.jose.jwk.source.ImmutableSecret;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 网关鉴权过滤器测试：覆盖计划书 D12 的四类验收场景。
 *
 * <ol>
 *   <li>无令牌访问受保护路径 → 401 统一壳；</li>
 *   <li>客户端伪造 X-Actor / X-Roles / X-Org → 被剥离并按令牌重新注入；</li>
 *   <li>有效令牌 → 注入正确的账号、角色、科室；</li>
 *   <li>内部端点经网关 → 404；v1 兼容层放行但清洗伪造头。</li>
 * </ol>
 */
class AuthGlobalFilterTest {

    private static final String SECRET = "unit-test-secret-0123456789abcdefghijklmnop";
    private static final String ISSUER = "medassist-auth";

    private final GatewayAuthProperties props = props(true);
    private final JwtDecoder decoder = new GatewayAuthConfig().jwtDecoder(props);
    private final AuthGlobalFilter filter = new AuthGlobalFilter(props, decoder);

    @Test
    void whitelistedLoginPassesWithoutToken() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.post("/api/v2/auth/login").build());
        CapturingChain chain = new CapturingChain();

        filter.filter(exchange, chain).block();

        assertThat(chain.called).isTrue();
        assertThat(chain.exchange.getRequest().getHeaders().getFirst(AuthHeaders.TRACE_ID)).isNotBlank();
    }

    @Test
    void protectedPathWithoutTokenReturns401() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/v2/empi/patients").build());
        CapturingChain chain = new CapturingChain();

        filter.filter(exchange, chain).block();

        assertThat(chain.called).isFalse();
        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(exchange.getResponse().getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_JSON);
        assertThat(exchange.getResponse().getHeaders().getFirst(HttpHeaders.WWW_AUTHENTICATE)).isEqualTo("Bearer");
        String body = body(exchange);
        assertThat(body).contains("\"code\":40100").contains("traceId");
    }

    @Test
    void forgedIdentityHeadersAreStrippedAndReplacedFromToken() {
        String token = token("doctor01", "DEPT-A", "DOCTOR");
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/v2/empi/patients")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .header(AuthHeaders.ACTOR, "hacker")
                        .header(AuthHeaders.USER_ID, "00000000-0000-0000-0000-000000000000")
                        .header(AuthHeaders.ROLES, "ADMIN")
                        .header(AuthHeaders.ORG, "FAKE-DEPT")
                        .build());
        CapturingChain chain = new CapturingChain();

        filter.filter(exchange, chain).block();

        assertThat(chain.called).isTrue();
        HttpHeaders downstream = chain.exchange.getRequest().getHeaders();
        assertThat(downstream.getFirst(AuthHeaders.ACTOR)).isEqualTo("doctor01");
        assertThat(downstream.getFirst(AuthHeaders.USER_ID)).isNotEqualTo("00000000-0000-0000-0000-000000000000");
        assertThat(downstream.getFirst(AuthHeaders.ROLES)).isEqualTo("DOCTOR");
        assertThat(downstream.getFirst(AuthHeaders.ORG)).isEqualTo("DEPT-A");
        assertThat(downstream.getFirst(AuthHeaders.TRACE_ID)).isNotBlank();
    }

    @Test
    void tokenWithoutOrgDoesNotLeakForgedOrg() {
        String token = token("nurse01", null, "NURSE");
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/v2/auth/me")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .header(AuthHeaders.ORG, "FAKE-DEPT")
                        .build());
        CapturingChain chain = new CapturingChain();

        filter.filter(exchange, chain).block();

        assertThat(chain.called).isTrue();
        assertThat(chain.exchange.getRequest().getHeaders().getFirst(AuthHeaders.ORG)).isNull();
        assertThat(chain.exchange.getRequest().getHeaders().getFirst(AuthHeaders.ROLES)).isEqualTo("NURSE");
    }

    @Test
    void invalidTokenReturns401() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/v2/empi/patients")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer not-a-jwt")
                        .build());
        CapturingChain chain = new CapturingChain();

        filter.filter(exchange, chain).block();

        assertThat(chain.called).isFalse();
        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(body(exchange)).contains("\"code\":40100");
    }

    @Test
    void internalPathIsDeniedEvenWithValidToken() {
        String token = token("admin", "DEPT-A", "ADMIN");
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/v2/auth/internal/token/verify")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .build());
        CapturingChain chain = new CapturingChain();

        filter.filter(exchange, chain).block();

        assertThat(chain.called).isFalse();
        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(body(exchange)).contains("\"code\":40400");
    }

    @Test
    void legacyV1PassesThroughButForgeIsStillStripped() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/v1/medical/config")
                        .header(AuthHeaders.ACTOR, "hacker")
                        .header(AuthHeaders.ROLES, "ADMIN")
                        .build());
        CapturingChain chain = new CapturingChain();

        filter.filter(exchange, chain).block();

        assertThat(chain.called).isTrue();
        HttpHeaders downstream = chain.exchange.getRequest().getHeaders();
        assertThat(downstream.getFirst(AuthHeaders.ACTOR)).isNull();
        assertThat(downstream.getFirst(AuthHeaders.ROLES)).isNull();
        assertThat(downstream.getFirst(AuthHeaders.TRACE_ID)).isNotBlank();
    }

    @Test
    void disabledSwitchStillStripsForgedHeaders() {
        AuthGlobalFilter relaxed = new AuthGlobalFilter(props(false), decoder);
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/v2/empi/patients")
                        .header(AuthHeaders.ACTOR, "hacker")
                        .build());
        CapturingChain chain = new CapturingChain();

        relaxed.filter(exchange, chain).block();

        assertThat(chain.called).isTrue();
        assertThat(chain.exchange.getRequest().getHeaders().getFirst(AuthHeaders.ACTOR)).isNull();

        // 即使鉴权总开关关闭，内部端点仍必须 404（防止排障时误暴露）
        MockServerWebExchange internal = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/v2/auth/internal/token/verify").build());
        CapturingChain internalChain = new CapturingChain();
        relaxed.filter(internal, internalChain).block();

        assertThat(internalChain.called).isFalse();
        assertThat(internal.getResponse().getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    // ---------------------------------------------------------------- 辅助

    private static GatewayAuthProperties props(boolean enabled) {
        return new GatewayAuthProperties(enabled, ISSUER, SECRET,
                List.of("/api/v2/auth/login", "/actuator/health", "/actuator/info"),
                List.of("/api/v2/**"),
                List.of("/api/v2/*/internal/**"));
    }

    private static String token(String username, String org, String... roles) {
        SecretKey key = new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
        JwtEncoder encoder = new NimbusJwtEncoder(new ImmutableSecret<>(key));
        Instant now = Instant.now();
        JwtClaimsSet.Builder claims = JwtClaimsSet.builder()
                .issuer(ISSUER)
                .subject(UUID.randomUUID().toString())
                .issuedAt(now)
                .expiresAt(now.plusSeconds(600))
                .claim("username", username)
                .claim("roles", List.of(roles));
        if (org != null) {
            claims.claim("org", org);
        }
        return encoder.encode(JwtEncoderParameters.from(
                JwsHeader.with(MacAlgorithm.HS256).build(), claims.build())).getTokenValue();
    }

    private static String body(MockServerWebExchange exchange) {
        DataBuffer buffer = DataBufferUtils.join(exchange.getResponse().getBody()).block();
        assertThat(buffer).isNotNull();
        byte[] bytes = new byte[buffer.readableByteCount()];
        buffer.read(bytes);
        DataBufferUtils.release(buffer);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static final class CapturingChain implements GatewayFilterChain {

        private boolean called;
        private ServerWebExchange exchange;

        @Override
        public Mono<Void> filter(ServerWebExchange exchange) {
            this.called = true;
            this.exchange = exchange;
            return Mono.empty();
        }
    }
}
