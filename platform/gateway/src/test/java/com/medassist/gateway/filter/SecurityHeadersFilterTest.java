package com.medassist.gateway.filter;

import com.medassist.gateway.config.SecurityHeadersProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 安全响应头（§267）：统一注入、HSTS 仅 HTTPS、认证响应禁缓存。
 */
class SecurityHeadersFilterTest {

    private static SecurityHeadersProperties props(boolean enabled) {
        return new SecurityHeadersProperties(enabled, true, 31536000L, true, true,
                "default-src 'none'; frame-ancestors 'none'; base-uri 'none'",
                "no-referrer", "camera=(), microphone=(), geolocation=()", true);
    }

    @Test
    @DisplayName("HTTP 响应注入基础安全头，但不输出 HSTS")
    void addsBaselineHeadersOnHttp() {
        SecurityHeadersFilter filter = new SecurityHeadersFilter(props(true));
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/v2/clinical/depts").build());

        filter.filter(exchange, chain()).block();

        HttpHeaders headers = exchange.getResponse().getHeaders();
        assertThat(headers.getFirst("X-Content-Type-Options")).isEqualTo("nosniff");
        assertThat(headers.getFirst("X-Frame-Options")).isEqualTo("DENY");
        assertThat(headers.getFirst("Referrer-Policy")).isEqualTo("no-referrer");
        assertThat(headers.getFirst("Permissions-Policy")).contains("camera=()");
        assertThat(headers.getFirst("Content-Security-Policy")).contains("frame-ancestors 'none'");
        assertThat(headers.getFirst("Strict-Transport-Security")).isNull();
    }

    @Test
    @DisplayName("Ingress 终止 TLS（X-Forwarded-Proto=https）时输出 HSTS")
    void addsHstsBehindTlsTermination() {
        SecurityHeadersFilter filter = new SecurityHeadersFilter(props(true));
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/v2/clinical/depts")
                        .header("X-Forwarded-Proto", "https").build());

        filter.filter(exchange, chain()).block();

        assertThat(exchange.getResponse().getHeaders()
                .getFirst("Strict-Transport-Security"))
                .isEqualTo("max-age=31536000; includeSubDomains");
    }

    @Test
    @DisplayName("认证接口响应禁止缓存；普通接口不强制 no-store")
    void authResponsesAreNotCacheable() {
        SecurityHeadersFilter filter = new SecurityHeadersFilter(props(true));
        MockServerWebExchange login = MockServerWebExchange.from(
                MockServerHttpRequest.post("/api/v2/auth/login").build());
        filter.filter(login, chain()).block();
        assertThat(login.getResponse().getHeaders().getFirst(HttpHeaders.CACHE_CONTROL))
                .isEqualTo("no-store");

        MockServerWebExchange normal = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/v2/clinical/depts").build());
        filter.filter(normal, chain()).block();
        assertThat(normal.getResponse().getHeaders().getFirst(HttpHeaders.CACHE_CONTROL)).isNull();
    }

    @Test
    @DisplayName("显式关闭时完全不注入安全头")
    void disabledAddsNothing() {
        SecurityHeadersFilter filter = new SecurityHeadersFilter(props(false));
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/v2/clinical/depts").build());

        filter.filter(exchange, chain()).block();

        assertThat(exchange.getResponse().getHeaders().getFirst("X-Content-Type-Options"))
                .isNull();
    }

    private static GatewayFilterChain chain() {
        return new GatewayFilterChain() {
            @Override
            public Mono<Void> filter(ServerWebExchange exchange) {
                return Mono.empty();
            }
        };
    }
}
