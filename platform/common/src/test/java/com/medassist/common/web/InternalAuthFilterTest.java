package com.medassist.common.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.medassist.common.security.AuthHeaders;
import com.medassist.common.security.InternalAuthProperties;
import com.medassist.common.security.InternalKeySet;
import com.medassist.common.security.InternalSignature;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 服务间签名过滤器行为矩阵（MF-07）：off / log / enforce、篡改、时间偏差、重放、密钥轮换、豁免。
 */
class InternalAuthFilterTest {

    private static final String KEY_1 = Base64.getEncoder()
            .encodeToString("0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8));
    private static final String KEY_2 = Base64.getEncoder()
            .encodeToString("fedcba9876543210fedcba9876543210".getBytes(StandardCharsets.UTF_8));
    private static final String KEYS_JSON =
            "{\"active\":\"k2\",\"keys\":{\"k1\":\"" + KEY_1 + "\",\"k2\":\"" + KEY_2 + "\"}}";

    private static InternalAuthFilter filter(String mode) {
        InternalAuthProperties props = new InternalAuthProperties(
                mode, 60, KEYS_JSON, 120, false, null);
        return new InternalAuthFilter(props, InternalKeySet.parse(KEYS_JSON, new ObjectMapper()),
                null, null);
    }

    private static MockHttpServletRequest request(String method, String uri) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, uri);
        request.addHeader(AuthHeaders.ACTOR, "doctor01");
        request.addHeader(AuthHeaders.USER_ID, "user-1");
        request.addHeader(AuthHeaders.ROLES, "DOCTOR");
        request.addHeader(AuthHeaders.PERMISSIONS, "clinical:visit:read");
        request.addHeader(AuthHeaders.TOKEN_ID, "jti-1");
        request.addHeader(AuthHeaders.ORG, "DEPT-IM");
        request.addHeader(AuthHeaders.CLIENT_IP, "10.1.2.3");
        request.addHeader(AuthHeaders.TRACE_ID, "trace-1");
        return request;
    }

    private static Map<String, String> identity(MockHttpServletRequest request) {
        Map<String, String> identity = new HashMap<>();
        for (String header : InternalSignature.IDENTITY_HEADERS) {
            identity.put(header, request.getHeader(header));
        }
        return identity;
    }

    private static void sign(MockHttpServletRequest request, Map<String, String> identity,
                             String secret, String keyId, long timestamp, String nonce) {
        String canonical = InternalSignature.canonical(request.getMethod(), request.getRequestURI(),
                request.getQueryString(), identity, timestamp, nonce, InternalSignature.EMPTY_BODY_HASH);
        request.addHeader(InternalSignature.HEADER_SIG_VER, InternalSignature.SIG_VERSION);
        request.addHeader(InternalSignature.HEADER_KEY_ID, keyId);
        request.addHeader(InternalSignature.HEADER_TS, Long.toString(timestamp));
        request.addHeader(InternalSignature.HEADER_NONCE, nonce);
        request.addHeader(InternalSignature.HEADER_SIG, InternalSignature.sign(secret, canonical));
    }

    private static void sign(MockHttpServletRequest request, String secret, String keyId,
                             long timestamp, String nonce) {
        sign(request, identity(request), secret, keyId, timestamp, nonce);
    }

    private static long now() {
        return System.currentTimeMillis() / 1000L;
    }

    @Test
    void offModePassesWithoutSignature() throws Exception {
        MockHttpServletRequest request = request("GET", "/clinical/visits");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();
        filter(InternalAuthProperties.OFF).doFilter(request, response, chain);
        assertNotNull(chain.getRequest());
        assertEquals(200, response.getStatus());
    }

    @Test
    void enforceAcceptsValidGatewaySignature() throws Exception {
        MockHttpServletRequest request = request("GET", "/clinical/visits");
        sign(request, KEY_2, "k2", now(), "nonce-ok");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();
        filter(InternalAuthProperties.ENFORCE).doFilter(request, response, chain);
        assertNotNull(chain.getRequest());
        assertEquals("ok", request.getAttribute(InternalAuthFilter.ATTR_RESULT));
        assertEquals("gateway", request.getAttribute(InternalAuthFilter.ATTR_CALLER));
        assertEquals("k2", request.getAttribute(InternalAuthFilter.ATTR_KEY_ID));
    }

    @Test
    void enforceRejectsTamperedPermissions() throws Exception {
        MockHttpServletRequest request = request("GET", "/emr/records/1");
        Map<String, String> signed = new HashMap<>(identity(request));
        signed.put(AuthHeaders.PERMISSIONS, "emr:record:read");
        // 实际请求头是 clinical:visit:read，签名却按 emr:record:read 计算 → 篡改必须失败
        sign(request, signed, KEY_2, "k2", now(), "nonce-tamper");

        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();
        filter(InternalAuthProperties.ENFORCE).doFilter(request, response, chain);
        assertNull(chain.getRequest(), "篡改身份头后必须被拦截");
        assertEquals(403, response.getStatus());
        assertEquals("bad_sig", request.getAttribute(InternalAuthFilter.ATTR_RESULT));
    }

    @Test
    void enforceRejectsExpiredOrFutureTimestamp() throws Exception {
        MockHttpServletRequest expired = request("GET", "/clinical/visits");
        sign(expired, KEY_2, "k2", now() - 120, "nonce-old");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();
        filter(InternalAuthProperties.ENFORCE).doFilter(expired, response, chain);
        assertNull(chain.getRequest());
        assertEquals(403, response.getStatus());
        assertEquals("skew", expired.getAttribute(InternalAuthFilter.ATTR_RESULT));

        MockHttpServletRequest future = request("GET", "/clinical/visits");
        sign(future, KEY_2, "k2", now() + 120, "nonce-future");
        MockFilterChain chain2 = new MockFilterChain();
        filter(InternalAuthProperties.ENFORCE).doFilter(future, new MockHttpServletResponse(), chain2);
        assertNull(chain2.getRequest());
    }

    @Test
    void enforceRejectsReplayedNonce() throws Exception {
        InternalAuthFilter filter = filter(InternalAuthProperties.ENFORCE);
        long ts = now();
        MockHttpServletRequest first = request("POST", "/insurance/bills");
        sign(first, KEY_2, "k2", ts, "nonce-replay");
        MockFilterChain chain1 = new MockFilterChain();
        filter.doFilter(first, new MockHttpServletResponse(), chain1);
        assertNotNull(chain1.getRequest());

        MockHttpServletRequest second = request("POST", "/insurance/bills");
        sign(second, KEY_2, "k2", ts, "nonce-replay");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain2 = new MockFilterChain();
        filter.doFilter(second, response, chain2);
        assertNull(chain2.getRequest());
        assertEquals("replay", second.getAttribute(InternalAuthFilter.ATTR_RESULT));
    }

    @Test
    void enforceRejectsUnknownKeyIdAndMissingHeaders() throws Exception {
        MockHttpServletRequest unknown = request("GET", "/clinical/visits");
        sign(unknown, KEY_1, "k9", now(), "nonce-unknown");
        MockFilterChain chain = new MockFilterChain();
        filter(InternalAuthProperties.ENFORCE).doFilter(unknown, new MockHttpServletResponse(), chain);
        assertNull(chain.getRequest());
        assertEquals("unknown_key", unknown.getAttribute(InternalAuthFilter.ATTR_RESULT));

        MockHttpServletRequest missing = request("GET", "/clinical/visits");
        MockFilterChain chain2 = new MockFilterChain();
        filter(InternalAuthProperties.ENFORCE).doFilter(missing, new MockHttpServletResponse(), chain2);
        assertNull(chain2.getRequest());
        assertEquals("missing", missing.getAttribute(InternalAuthFilter.ATTR_RESULT));
    }

    @Test
    void enforceRejectsTamperedQuery() throws Exception {
        long ts = now();
        MockHttpServletRequest request = request("GET", "/insurance/bills");
        request.setQueryString("limit=10");
        Map<String, String> signed = identity(request);
        // 签名按 limit=10 计算，但实际请求的是 limit=100000
        String canonical = InternalSignature.canonical("GET", "/insurance/bills", "limit=10",
                signed, ts, "nonce-query", InternalSignature.EMPTY_BODY_HASH);
        request.setQueryString("limit=100000");
        request.addHeader(InternalSignature.HEADER_SIG_VER, InternalSignature.SIG_VERSION);
        request.addHeader(InternalSignature.HEADER_KEY_ID, "k2");
        request.addHeader(InternalSignature.HEADER_TS, Long.toString(ts));
        request.addHeader(InternalSignature.HEADER_NONCE, "nonce-query");
        request.addHeader(InternalSignature.HEADER_SIG, InternalSignature.sign(KEY_2, canonical));

        MockFilterChain chain = new MockFilterChain();
        filter(InternalAuthProperties.ENFORCE).doFilter(request, new MockHttpServletResponse(), chain);
        assertNull(chain.getRequest());
        assertEquals("bad_sig", request.getAttribute(InternalAuthFilter.ATTR_RESULT));
    }

    @Test
    void enforceAcceptsOldKeyDuringRotation() throws Exception {
        MockHttpServletRequest request = request("GET", "/clinical/visits");
        sign(request, KEY_1, "k1", now(), "nonce-rotation");
        MockFilterChain chain = new MockFilterChain();
        filter(InternalAuthProperties.ENFORCE).doFilter(request, new MockHttpServletResponse(), chain);
        assertNotNull(chain.getRequest());
        assertEquals("ok", request.getAttribute(InternalAuthFilter.ATTR_RESULT));
    }

    @Test
    void exemptPathsPassWithoutSignature() throws Exception {
        for (String uri : new String[]{"/actuator/health/liveness", "/actuator/info",
                "/actuator/prometheus"}) {
            MockHttpServletRequest request = new MockHttpServletRequest("GET", uri);
            MockFilterChain chain = new MockFilterChain();
            filter(InternalAuthProperties.ENFORCE).doFilter(request, new MockHttpServletResponse(), chain);
            assertNotNull(chain.getRequest(), uri + " 必须豁免");
        }
    }

    @Test
    void internalPathsPassUntilCallerSignatureIsRolledOut() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/internal/visits/1");
        MockFilterChain chain = new MockFilterChain();
        filter(InternalAuthProperties.ENFORCE).doFilter(request, new MockHttpServletResponse(), chain);
        assertNotNull(chain.getRequest());
        assertEquals("internal", request.getAttribute(InternalAuthFilter.ATTR_CALLER));
    }

    @Test
    void logModePassesButRecordsFailure() throws Exception {
        MockHttpServletRequest request = request("GET", "/clinical/visits");
        sign(request, KEY_1, "k2", now(), "nonce-bad");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();
        filter(InternalAuthProperties.LOG).doFilter(request, response, chain);
        assertNotNull(chain.getRequest());
        assertEquals("bad_sig", request.getAttribute(InternalAuthFilter.ATTR_RESULT));
        assertEquals(200, response.getStatus());
    }
}
