package com.medassist.gateway.filter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.medassist.common.security.AuthHeaders;
import com.medassist.common.security.InternalAuthProperties;
import com.medassist.common.security.InternalKeySet;
import com.medassist.common.security.InternalSignature;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.MediaType;
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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
                new InternalAuthProperties(mode, 60, KEYS_JSON, 120, false, null, null, null, null, null, null, null,
                        null, null),
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

    // ---------------------------------------------------------------- v2：Ed25519（阶段二）

    private static java.security.KeyPair ed25519() throws Exception {
        return java.security.KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
    }

    private static InternalSignatureGlobalFilter ed25519Filter(java.security.PrivateKey key) {
        return new InternalSignatureGlobalFilter(
                new InternalAuthProperties(InternalAuthProperties.ENFORCE, 60, null, 120, false,
                        null, null, "ed25519", "gw-v2", null, null, null, null, null),
                InternalKeySet.empty(), key);
    }

    @Test
    void ed25519ModeSignsWithPrivateKeyAndDownstreamVerifiesWithPublicKey() throws Exception {
        java.security.KeyPair pair = ed25519();
        ServerWebExchange forwarded = run(ed25519Filter(pair.getPrivate()),
                exchange("http://gateway/api/v2/clinical/visits?a=1"));

        ServerHttpRequest request = forwarded.getRequest();
        assertEquals(InternalSignature.SIG_VERSION_GATEWAY_ED25519,
                request.getHeaders().getFirst(InternalSignature.HEADER_SIG_VER));
        assertEquals("gw-v2", request.getHeaders().getFirst(InternalSignature.HEADER_KEY_ID));

        Map<String, String> identity = new HashMap<>();
        for (String header : InternalSignature.IDENTITY_HEADERS) {
            identity.put(header, request.getHeaders().getFirst(header));
        }
        String canonical = InternalSignature.canonicalGatewayV2(request.getMethod().name(),
                request.getURI().getRawPath(), request.getURI().getRawQuery(), identity,
                Long.parseLong(request.getHeaders().getFirst(InternalSignature.HEADER_TS)),
                request.getHeaders().getFirst(InternalSignature.HEADER_NONCE),
                InternalSignature.EMPTY_BODY_HASH);
        assertTrue(InternalSignature.verifyEd25519(pair.getPublic(), canonical,
                request.getHeaders().getFirst(InternalSignature.HEADER_SIG)),
                "下游只持公钥也必须能验签通过");

        // 另一把私钥签出的同一请求不能被该公钥验证（业务服务无法伪造网关身份）
        java.security.KeyPair attacker = ed25519();
        assertTrue(!InternalSignature.verifyEd25519(attacker.getPublic(), canonical,
                request.getHeaders().getFirst(InternalSignature.HEADER_SIG)));
    }

    // ---------------------------------------------------------------- P0-8：请求体绑定（v1b/v2b）

    private static InternalSignatureGlobalFilter bodyFilter(int maxBytes) {
        return new InternalSignatureGlobalFilter(
                new InternalAuthProperties(InternalAuthProperties.ENFORCE, 60, KEYS_JSON, 120,
                        false, null, null, null, null, null, null, null, true, maxBytes),
                InternalKeySet.parse(KEYS_JSON, new ObjectMapper()));
    }

    private static ServerWebExchange runBody(InternalSignatureGlobalFilter filter,
                                             MockServerWebExchange exchange) {
        AtomicReference<ServerWebExchange> captured = new AtomicReference<>();
        GatewayFilterChain chain = current -> {
            captured.set(current);
            return Mono.empty();
        };
        filter.filter(exchange, chain).block();
        return captured.get();
    }

    private static byte[] readBody(ServerHttpRequest request) {
        return DataBufferUtils.join(request.getBody())
                .map(buffer -> {
                    byte[] bytes = new byte[buffer.readableByteCount()];
                    buffer.read(bytes);
                    DataBufferUtils.release(buffer);
                    return bytes;
                })
                .block();
    }

    @Test
    void bodyBoundSignatureCoversBodyAndKeepsItReadable() {
        String json = "{\"items\":[{\"itemCode\":\"TRT-IV\",\"quantity\":1}]}";
        byte[] payload = json.getBytes(StandardCharsets.UTF_8);
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.post("http://gateway/api/v2/clinical/visits/1/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(AuthHeaders.ACTOR, "doctor01")
                        .body(json));

        ServerWebExchange forwarded = runBody(bodyFilter(1024 * 1024), exchange);
        assertNotNull(forwarded);
        ServerHttpRequest request = forwarded.getRequest();
        String version = request.getHeaders().getFirst(InternalSignature.HEADER_SIG_VER);
        assertEquals(InternalSignature.SIG_VERSION_GATEWAY_BODY, version);
        assertArrayEquals(payload, readBody(request), "下游读到的必须是同一份字节");

        Map<String, String> identity = new HashMap<>();
        for (String header : InternalSignature.IDENTITY_HEADERS) {
            identity.put(header, request.getHeaders().getFirst(header));
        }
        String canonical = InternalSignature.canonicalGateway(version, request.getMethod().name(),
                request.getURI().getRawPath(), request.getURI().getRawQuery(), identity,
                Long.parseLong(request.getHeaders().getFirst(InternalSignature.HEADER_TS)),
                request.getHeaders().getFirst(InternalSignature.HEADER_NONCE),
                InternalSignature.bodyHash(payload));
        assertTrue(InternalSignature.verify(KEY, canonical,
                request.getHeaders().getFirst(InternalSignature.HEADER_SIG)),
                "按真实体摘要重算必须验签通过");

        String withoutBody = InternalSignature.canonicalGateway(version, request.getMethod().name(),
                request.getURI().getRawPath(), request.getURI().getRawQuery(), identity,
                Long.parseLong(request.getHeaders().getFirst(InternalSignature.HEADER_TS)),
                request.getHeaders().getFirst(InternalSignature.HEADER_NONCE),
                InternalSignature.EMPTY_BODY_HASH);
        assertFalse(InternalSignature.verify(KEY, withoutBody,
                        request.getHeaders().getFirst(InternalSignature.HEADER_SIG)),
                "签名必须绑定请求体，不能退化成阶段一的无体签名");
    }

    @Test
    void oversizedBodyIsRejectedWith413() {
        char[] payload = new char[4096];
        java.util.Arrays.fill(payload, 'x');
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.post("http://gateway/api/v2/clinical/visits/1/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(new String(payload)));

        AtomicReference<ServerWebExchange> captured = new AtomicReference<>();
        bodyFilter(1024).filter(exchange, current -> {
            captured.set(current);
            return Mono.empty();
        }).block();

        assertNull(captured.get(), "超限请求不得进入路由");
        assertEquals(413, exchange.getResponse().getStatusCode().value());
    }

    @Test
    void multipartUploadsKeepStreamingAndSkipBodyDigest() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.post("http://gateway/api/v2/kb/spaces/1/documents")
                        .contentType(MediaType.MULTIPART_FORM_DATA)
                        .body("------x\r\ncontent\r\n------x--"));

        ServerWebExchange forwarded = runBody(bodyFilter(1024 * 1024), exchange);
        assertNotNull(forwarded);
        assertEquals(InternalSignature.SIG_VERSION_GATEWAY_BODY,
                forwarded.getRequest().getHeaders().getFirst(InternalSignature.HEADER_SIG_VER));
    }
}
