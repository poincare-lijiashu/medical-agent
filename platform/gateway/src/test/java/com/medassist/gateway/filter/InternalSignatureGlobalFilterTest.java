package com.medassist.gateway.filter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.medassist.common.security.AuthHeaders;
import com.medassist.common.security.InternalAuthProperties;
import com.medassist.common.security.InternalKeySet;
import com.medassist.common.security.InternalSignature;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.core.Ordered;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 网关签名过滤器：签的是"下游视角"的最终请求，且客户端伪造的签名头会被覆盖。
 */
class InternalSignatureGlobalFilterTest {

    private static final String KEY = Base64.getEncoder()
            .encodeToString("0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8));
    private static final String KEYS_JSON = "{\"active\":\"k1\",\"keys\":{\"k1\":\"" + KEY + "\"}}";

    private static InternalSignatureGlobalFilter filter(String mode) {
        return new InternalSignatureGlobalFilter(
                new InternalAuthProperties(mode, 60, KEYS_JSON, 120, false, null, null),
                InternalKeySet.parse(KEYS_JSON, new ObjectMapper()));
    }

    private static MockServerWebExchange exchange(String uri) {
        return MockServerWebExchange.from(MockServerHttpRequest.get(uri)
                .header(AuthHeaders.ACTOR, "doctor01")
                .header(AuthHeaders.PERMISSIONS, "clinical:visit:read")
                .header(AuthHeaders.TRACE_ID, "trace-1"));
    }

    private static ServerWebExchange run(InternalSignatureGlobalFilter filter,
                                         MockServerWebExchange exchange) {
        AtomicReference<ServerWebExchange> captured = new AtomicReference<>();
        GatewayFilterChain chain = current -> {
            captured.set(current);
            return Mono.empty();
        };
        filter.filter(exchange, chain).block();
        return captured.get();
    }

    @Test
    void signsTheFinalDownstreamRequest() {
        ServerWebExchange forwarded = run(filter(InternalAuthProperties.ENFORCE),
                exchange("http://gateway/api/v2/clinical/visits?b=2&a=1"));
        assertNotNull(forwarded);

        ServerHttpRequest request = forwarded.getRequest();
        String signature = request.getHeaders().getFirst(InternalSignature.HEADER_SIG);
        String timestamp = request.getHeaders().getFirst(InternalSignature.HEADER_TS);
        String nonce = request.getHeaders().getFirst(InternalSignature.HEADER_NONCE);
        String keyId = request.getHeaders().getFirst(InternalSignature.HEADER_KEY_ID);
        String version = request.getHeaders().getFirst(InternalSignature.HEADER_SIG_VER);
        assertNotNull(signature);
        assertEquals("k1", keyId);
        assertEquals(InternalSignature.SIG_VERSION, version);

        Map<String, String> identity = new HashMap<>();
        for (String header : InternalSignature.IDENTITY_HEADERS) {
            identity.put(header, request.getHeaders().getFirst(header));
        }
        String canonical = InternalSignature.canonical(request.getMethod().name(),
                request.getURI().getRawPath(), request.getURI().getRawQuery(), identity,
                Long.parseLong(timestamp), nonce, InternalSignature.EMPTY_BODY_HASH);
        assertTrue(InternalSignature.verify(KEY, canonical, signature),
                "下游用同一实现必须能验签通过");
        assertEquals("/api/v2/clinical/visits", request.getURI().getRawPath());
    }

    @Test
    void overwritesClientSuppliedSignatureHeaders() {
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest
                .get("http://gateway/api/v2/clinical/visits")
                .header(InternalSignature.HEADER_SIG, "forged")
                .header(InternalSignature.HEADER_KEY_ID, "attacker")
                .header(InternalSignature.HEADER_SIG_VER, "v0"));
        ServerWebExchange forwarded = run(filter(InternalAuthProperties.LOG), exchange);
        assertNotNull(forwarded);
        assertEquals("k1", forwarded.getRequest().getHeaders().getFirst(InternalSignature.HEADER_KEY_ID));
        assertEquals(InternalSignature.SIG_VERSION,
                forwarded.getRequest().getHeaders().getFirst(InternalSignature.HEADER_SIG_VER));
        assertTrue(!"forged".equals(
                forwarded.getRequest().getHeaders().getFirst(InternalSignature.HEADER_SIG)));
    }

    @Test
    void offModeDoesNotTouchHeaders() {
        MockServerWebExchange exchange = exchange("http://gateway/api/v2/clinical/visits");
        ServerWebExchange forwarded = run(filter(InternalAuthProperties.OFF), exchange);
        assertNotNull(forwarded);
        assertNull(forwarded.getRequest().getHeaders().getFirst(InternalSignature.HEADER_SIG));
    }

    @Test
    void runsAfterRouteFiltersAndBeforeRouting() {
        assertEquals(Ordered.LOWEST_PRECEDENCE - 100, filter(InternalAuthProperties.OFF).getOrder());
    }
}
