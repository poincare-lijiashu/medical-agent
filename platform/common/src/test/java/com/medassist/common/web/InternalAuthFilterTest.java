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
            "{\"active\":\"k2\",\"keys\":{\"k1\":\"" + KEY_1 + "\",\"k2\":\"" + KEY_2 + "\"},"
                    + "\"internal\":{\"clinical\":\"" + KEY_1 + "\",\"emr\":\"" + KEY_2 + "\"}}";

    private static InternalAuthFilter filter(String mode) {
        return filter(mode, false);
    }

    private static InternalAuthFilter filter(String mode, boolean internalRequired) {
        InternalAuthProperties props = new InternalAuthProperties(
                mode, 60, KEYS_JSON, 120, internalRequired, null, null, null, null, null, null, null,
                null, null);
        return new InternalAuthFilter(props, InternalKeySet.parse(KEYS_JSON, new ObjectMapper()),
                null);
    }

    private static InternalAuthFilter filterWithCapacity(int maxNonceEntries) {
        InternalAuthProperties props = new InternalAuthProperties(
                InternalAuthProperties.ENFORCE, 60, KEYS_JSON, 120, false, null, null, null, null, null, null, null,
                null, null);
        return new InternalAuthFilter(props, InternalKeySet.parse(KEYS_JSON, new ObjectMapper()),
                null, maxNonceEntries);
    }

    /** P0-S5：注入外部 nonce 存储，验证共享存储的返回值/异常如何映射为放行或拒绝。 */
    private static InternalAuthFilter filterWithStore(NonceStore store) {
        InternalAuthProperties props = new InternalAuthProperties(
                InternalAuthProperties.ENFORCE, 60, KEYS_JSON, 120, false, null, null, null, null,
                null, null, null, null, null);
        return new InternalAuthFilter(props, InternalKeySet.parse(KEYS_JSON, new ObjectMapper()),
                null, null, store);
    }

    /** P0-8：请求体绑定（v1i2）用例需要自定义开关与 body 上限。 */
    private static InternalAuthFilter filterWithBodyHash(boolean required, int maxBytes) {
        InternalAuthProperties props = new InternalAuthProperties(
                InternalAuthProperties.ENFORCE, 60, KEYS_JSON, 120, true, null, null, null, null,
                null, null, null, required, maxBytes);
        return new InternalAuthFilter(props, InternalKeySet.parse(KEYS_JSON, new ObjectMapper()),
                null);
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

    // ---------------------------------------------------------------- 调用方签名（T5）

    private static void signCaller(MockHttpServletRequest request, String caller, String secret,
                                   long timestamp, String nonce) {
        String canonical = InternalSignature.canonicalCaller(request.getMethod(),
                request.getRequestURI(), request.getQueryString(), caller, timestamp, nonce,
                InternalSignature.EMPTY_BODY_HASH);
        request.addHeader(InternalSignature.HEADER_SIG_VER, InternalSignature.SIG_VERSION_CALLER);
        request.addHeader(InternalSignature.HEADER_CALLER, caller);
        request.addHeader(InternalSignature.HEADER_TS, Long.toString(timestamp));
        request.addHeader(InternalSignature.HEADER_NONCE, nonce);
        request.addHeader(InternalSignature.HEADER_SIG, InternalSignature.sign(secret, canonical));
    }

    @Test
    void callerChannelAcceptsValidSignatureWhenRequired() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/internal/patients/1");
        request.setQueryString("reveal=false");
        signCaller(request, "clinical", KEY_1, now(), "caller-nonce-1");

        MockFilterChain chain = new MockFilterChain();
        filter(InternalAuthProperties.ENFORCE, true).doFilter(request, new MockHttpServletResponse(), chain);

        assertNotNull(chain.getRequest());
        assertEquals("ok", request.getAttribute(InternalAuthFilter.ATTR_RESULT));
        assertEquals("clinical", request.getAttribute(InternalAuthFilter.ATTR_CALLER));
    }

    @Test
    void callerChannelRejectsMissingUnknownAndWrongKey() throws Exception {
        MockFilterChain chain1 = new MockFilterChain();
        MockHttpServletRequest missing = new MockHttpServletRequest("GET", "/internal/patients/1");
        filter(InternalAuthProperties.ENFORCE, true).doFilter(missing, new MockHttpServletResponse(), chain1);
        assertNull(chain1.getRequest());
        assertEquals("missing", missing.getAttribute(InternalAuthFilter.ATTR_RESULT));

        MockHttpServletRequest unknown = new MockHttpServletRequest("GET", "/internal/patients/1");
        signCaller(unknown, "billing", KEY_1, now(), "caller-nonce-2");
        MockFilterChain chain2 = new MockFilterChain();
        filter(InternalAuthProperties.ENFORCE, true).doFilter(unknown, new MockHttpServletResponse(), chain2);
        assertNull(chain2.getRequest());
        assertEquals("unknown_caller", unknown.getAttribute(InternalAuthFilter.ATTR_RESULT));

        // 用 emr 的密钥冒充 clinical：caller 参与规范串 + 密钥隔离，双重拦截
        MockHttpServletRequest wrongKey = new MockHttpServletRequest("GET", "/internal/patients/1");
        signCaller(wrongKey, "clinical", KEY_2, now(), "caller-nonce-3");
        MockFilterChain chain3 = new MockFilterChain();
        filter(InternalAuthProperties.ENFORCE, true).doFilter(wrongKey, new MockHttpServletResponse(), chain3);
        assertNull(chain3.getRequest());
        assertEquals("bad_sig", wrongKey.getAttribute(InternalAuthFilter.ATTR_RESULT));
    }

    @Test
    void callerChannelRejectsReplayAndSkew() throws Exception {
        InternalAuthFilter filter = filter(InternalAuthProperties.ENFORCE, true);
        long ts = now();

        MockHttpServletRequest first = new MockHttpServletRequest("GET", "/internal/patients/1");
        signCaller(first, "emr", KEY_2, ts, "caller-replay");
        MockFilterChain chain1 = new MockFilterChain();
        filter.doFilter(first, new MockHttpServletResponse(), chain1);
        assertNotNull(chain1.getRequest());

        MockHttpServletRequest replay = new MockHttpServletRequest("GET", "/internal/patients/1");
        signCaller(replay, "emr", KEY_2, ts, "caller-replay");
        MockFilterChain chain2 = new MockFilterChain();
        filter.doFilter(replay, new MockHttpServletResponse(), chain2);
        assertNull(chain2.getRequest());
        assertEquals("replay", replay.getAttribute(InternalAuthFilter.ATTR_RESULT));

        MockHttpServletRequest stale = new MockHttpServletRequest("GET", "/internal/patients/1");
        signCaller(stale, "emr", KEY_2, now() - 120, "caller-stale");
        MockFilterChain chain3 = new MockFilterChain();
        filter.doFilter(stale, new MockHttpServletResponse(), chain3);
        assertNull(chain3.getRequest());
        assertEquals("skew", stale.getAttribute(InternalAuthFilter.ATTR_RESULT));
    }

    @Test
    void nonceIsNotConsumedWhenSignatureFails() throws Exception {
        InternalAuthFilter filter = filter(InternalAuthProperties.ENFORCE);
        long ts = now();

        // 先用错误密钥"占坑"：若 nonce 在验签前注册，随后的正确请求会被误判为重放
        MockHttpServletRequest bad = request("GET", "/clinical/visits");
        sign(bad, KEY_1, "k2", ts, "nonce-shared");
        MockFilterChain chain1 = new MockFilterChain();
        filter.doFilter(bad, new MockHttpServletResponse(), chain1);
        assertNull(chain1.getRequest());
        assertEquals("bad_sig", bad.getAttribute(InternalAuthFilter.ATTR_RESULT));

        MockHttpServletRequest good = request("GET", "/clinical/visits");
        sign(good, KEY_2, "k2", ts, "nonce-shared");
        MockFilterChain chain2 = new MockFilterChain();
        filter.doFilter(good, new MockHttpServletResponse(), chain2);
        assertNotNull(chain2.getRequest(), "验签失败不应占用 nonce（R5-M1）");
        assertEquals("ok", good.getAttribute(InternalAuthFilter.ATTR_RESULT));
    }

    @Test
    void saturatedNonceCacheRejectsInsteadOfClearing() throws Exception {
        InternalAuthFilter filter = filterWithCapacity(2);
        long ts = now();

        for (String nonce : new String[]{"n1", "n2"}) {
            MockHttpServletRequest ok = request("GET", "/clinical/visits");
            sign(ok, KEY_2, "k2", ts, nonce);
            MockFilterChain chain = new MockFilterChain();
            filter.doFilter(ok, new MockHttpServletResponse(), chain);
            assertNotNull(chain.getRequest());
        }

        MockHttpServletRequest overflow = request("GET", "/clinical/visits");
        sign(overflow, KEY_2, "k2", ts, "n3");
        MockFilterChain chain = new MockFilterChain();
        filter.doFilter(overflow, new MockHttpServletResponse(), chain);
        assertNull(chain.getRequest(), "容量满时必须 fail-closed，不能整体清空（R5-M1）");
        assertEquals("saturated", overflow.getAttribute(InternalAuthFilter.ATTR_RESULT));
    }

    @Test
    void sharedNonceStoreReplayRejectsRequest() throws Exception {
        InternalAuthFilter filter = filterWithStore(key -> NonceStore.Outcome.REPLAY);
        MockHttpServletRequest request = request("GET", "/clinical/visits");
        sign(request, KEY_2, "k2", now(), "shared-replay");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();
        filter.doFilter(request, response, chain);
        assertNull(chain.getRequest());
        assertEquals(403, response.getStatus());
        assertEquals("replay", request.getAttribute(InternalAuthFilter.ATTR_RESULT));
    }

    @Test
    void sharedNonceStoreFailureFailsClosed() throws Exception {
        InternalAuthFilter filter = filterWithStore(key -> {
            throw new IllegalStateException("db down");
        });
        MockHttpServletRequest request = request("GET", "/clinical/visits");
        sign(request, KEY_2, "k2", now(), "shared-error");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();
        filter.doFilter(request, response, chain);
        assertNull(chain.getRequest(), "nonce 存储不可用必须 fail-closed，不能当成新鲜放行");
        assertEquals(403, response.getStatus());
        assertEquals("nonce_store_error", request.getAttribute(InternalAuthFilter.ATTR_RESULT));
    }

    @Test
    void recordsResultInMeterRegistry() throws Exception {
        io.micrometer.core.instrument.simple.SimpleMeterRegistry registry =
                new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
        InternalAuthProperties props = new InternalAuthProperties(
                InternalAuthProperties.ENFORCE, 60, KEYS_JSON, 120, false, null, null, null, null, null, null, null,
                null, null);
        InternalAuthFilter filter = new InternalAuthFilter(props,
                InternalKeySet.parse(KEYS_JSON, new ObjectMapper()), registry);

        MockHttpServletRequest ok = request("GET", "/clinical/visits");
        sign(ok, KEY_2, "k2", now(), "metric-ok");
        filter.doFilter(ok, new MockHttpServletResponse(), new MockFilterChain());

        MockHttpServletRequest bad = request("GET", "/clinical/visits");
        sign(bad, KEY_1, "k2", now(), "metric-bad");
        filter.doFilter(bad, new MockHttpServletResponse(), new MockFilterChain());

        assertEquals(1.0, registry.get("medassist.internal.auth").tag("result", "ok")
                .counter().count(), 1e-9);
        assertEquals(1.0, registry.get("medassist.internal.auth").tag("result", "bad_sig")
                .counter().count(), 1e-9);
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

    // ---------------------------------------------------------------- v2：Ed25519（阶段二）

    private static InternalAuthFilter filterWithPublicKey(java.security.PublicKey publicKey) {
        InternalAuthProperties props = new InternalAuthProperties(
                InternalAuthProperties.ENFORCE, 60, null, 120, false, null, null,
                "ed25519", "gw-v2", null, null, null, null, null);
        return new InternalAuthFilter(props, InternalKeySet.empty(), null, publicKey);
    }

    /** 按网关 v2 的规则签名（业务服务只持公钥）。 */
    private static void signEd25519(MockHttpServletRequest request,
                                    java.security.PrivateKey privateKey,
                                    long timestamp, String nonce) {
        String canonical = InternalSignature.canonicalGatewayV2(request.getMethod(),
                request.getRequestURI(), request.getQueryString(), identity(request), timestamp,
                nonce, InternalSignature.EMPTY_BODY_HASH);
        request.addHeader(InternalSignature.HEADER_SIG_VER,
                InternalSignature.SIG_VERSION_GATEWAY_ED25519);
        request.addHeader(InternalSignature.HEADER_KEY_ID, "gw-v2");
        request.addHeader(InternalSignature.HEADER_TS, Long.toString(timestamp));
        request.addHeader(InternalSignature.HEADER_NONCE, nonce);
        request.addHeader(InternalSignature.HEADER_SIG,
                InternalSignature.signEd25519(privateKey, canonical));
    }

    private static java.security.KeyPair ed25519() throws Exception {
        return java.security.KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
    }

    @Test
    void enforceAcceptsEd25519GatewaySignature() throws Exception {
        java.security.KeyPair pair = ed25519();
        MockHttpServletRequest request = request("GET", "/clinical/visits");
        signEd25519(request, pair.getPrivate(), now(), "v2-nonce-ok");

        MockFilterChain chain = new MockFilterChain();
        filterWithPublicKey(pair.getPublic()).doFilter(request, new MockHttpServletResponse(), chain);

        assertNotNull(chain.getRequest());
        assertEquals("ok", request.getAttribute(InternalAuthFilter.ATTR_RESULT));
        assertEquals("gateway", request.getAttribute(InternalAuthFilter.ATTR_CALLER));
        assertEquals("gw-v2", request.getAttribute(InternalAuthFilter.ATTR_KEY_ID));
    }

    @Test
    void enforceRejectsEd25519SignatureFromAnotherKey() throws Exception {
        java.security.KeyPair trusted = ed25519();
        java.security.KeyPair attacker = ed25519();
        MockHttpServletRequest request = request("GET", "/clinical/visits");
        signEd25519(request, attacker.getPrivate(), now(), "v2-nonce-forged");

        MockFilterChain chain = new MockFilterChain();
        filterWithPublicKey(trusted.getPublic()).doFilter(request, new MockHttpServletResponse(), chain);
        assertNull(chain.getRequest(), "非网关私钥签出的 v2 请求必须被拒绝");
        assertEquals("bad_sig", request.getAttribute(InternalAuthFilter.ATTR_RESULT));
    }

    @Test
    void v2RequestIsRejectedWhenPublicKeyNotConfigured() throws Exception {
        java.security.KeyPair pair = ed25519();
        MockHttpServletRequest request = request("GET", "/clinical/visits");
        signEd25519(request, pair.getPrivate(), now(), "v2-nonce-nokey");

        MockFilterChain chain = new MockFilterChain();
        // 未挂载公钥（例如上线顺序不当）：fail-closed，而不是退化成不校验
        filter(InternalAuthProperties.ENFORCE).doFilter(request, new MockHttpServletResponse(), chain);
        assertNull(chain.getRequest());
        assertEquals("unknown_key", request.getAttribute(InternalAuthFilter.ATTR_RESULT));
    }

    @Test
    void v1RequestIsRejectedWhenOnlyPublicKeyConfigured() throws Exception {
        java.security.KeyPair pair = ed25519();
        MockHttpServletRequest request = request("GET", "/clinical/visits");
        sign(request, KEY_2, "k2", now(), "v1-nonce-after-cutover");

        MockFilterChain chain = new MockFilterChain();
        filterWithPublicKey(pair.getPublic()).doFilter(request, new MockHttpServletResponse(), chain);
        assertNull(chain.getRequest(), "切换 v2 后不再接受 HMAC 请求（密钥面已移除）");
        assertEquals("unknown_key", request.getAttribute(InternalAuthFilter.ATTR_RESULT));
    }

    @Test
    void v2ReplayIsRejected() throws Exception {
        java.security.KeyPair pair = ed25519();
        InternalAuthFilter filter = filterWithPublicKey(pair.getPublic());
        long ts = now();

        MockHttpServletRequest first = request("POST", "/clinical/orders");
        signEd25519(first, pair.getPrivate(), ts, "v2-replay");
        MockFilterChain chain1 = new MockFilterChain();
        filter.doFilter(first, new MockHttpServletResponse(), chain1);
        assertNotNull(chain1.getRequest());

        MockHttpServletRequest replay = request("POST", "/clinical/orders");
        signEd25519(replay, pair.getPrivate(), ts, "v2-replay");
        MockFilterChain chain2 = new MockFilterChain();
        filter.doFilter(replay, new MockHttpServletResponse(), chain2);
        assertNull(chain2.getRequest());
        assertEquals("replay", replay.getAttribute(InternalAuthFilter.ATTR_RESULT));
    }

    // ---------------------------------------------------------------- P0-8：请求体绑定（v1i2）

    /** 按 v1i2 规则签名：请求体 SHA-256 纳入规范串。 */
    private static void signCallerWithBody(MockHttpServletRequest request, String caller, String secret,
                                           long timestamp, String nonce) {
        String canonical = InternalSignature.canonicalCaller(
                InternalSignature.SIG_VERSION_CALLER_BODY, request.getMethod(),
                request.getRequestURI(), request.getQueryString(), caller, timestamp, nonce,
                InternalSignature.bodyHash(request.getContentAsByteArray()));
        request.addHeader(InternalSignature.HEADER_SIG_VER, InternalSignature.SIG_VERSION_CALLER_BODY);
        request.addHeader(InternalSignature.HEADER_CALLER, caller);
        request.addHeader(InternalSignature.HEADER_TS, Long.toString(timestamp));
        request.addHeader(InternalSignature.HEADER_NONCE, nonce);
        request.addHeader(InternalSignature.HEADER_SIG, InternalSignature.sign(secret, canonical));
    }

    private static MockHttpServletRequest jsonRequest(String method, String uri, String body) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, uri);
        request.setContent(body.getBytes(StandardCharsets.UTF_8));
        request.setContentType("application/json");
        return request;
    }

    @Test
    void bodyBoundSignatureAcceptedAndBodyRemainsReadable() throws Exception {
        String json = "{\"orderId\":\"o-1\",\"quantity\":2}";
        MockHttpServletRequest request = jsonRequest("POST", "/internal/prescriptions", json);
        signCallerWithBody(request, "clinical", KEY_1, now(), "body-ok");

        java.util.concurrent.atomic.AtomicReference<String> seen = new java.util.concurrent.atomic.AtomicReference<>();
        jakarta.servlet.http.HttpServlet servlet = new jakarta.servlet.http.HttpServlet() {
            @Override
            protected void service(jakarta.servlet.http.HttpServletRequest req,
                                   jakarta.servlet.http.HttpServletResponse resp)
                    throws java.io.IOException {
                seen.set(req.getReader().lines().collect(java.util.stream.Collectors.joining()));
            }
        };
        MockFilterChain chain = new MockFilterChain(servlet);
        filterWithBodyHash(true, 1024 * 1024)
                .doFilter(request, new MockHttpServletResponse(), chain);

        assertNotNull(chain.getRequest(), "v1i2 正确签名必须放行");
        assertEquals("ok", request.getAttribute(InternalAuthFilter.ATTR_RESULT));
        // 关键：验签读过 body 后，业务侧仍能读到同一份内容（缓存包装生效）
        assertEquals(json, seen.get());
    }

    @Test
    void bodyBoundSignatureRejectsTamperedBody() throws Exception {
        MockHttpServletRequest request = jsonRequest("POST", "/internal/prescriptions",
                "{\"quantity\":2}");
        signCallerWithBody(request, "clinical", KEY_1, now(), "body-tampered");
        // 内网攻击者在途改写请求体：签名不变，但摘要不再匹配
        request.setContent("{\"quantity\":200}".getBytes(StandardCharsets.UTF_8));

        MockFilterChain chain = new MockFilterChain();
        filterWithBodyHash(true, 1024 * 1024)
                .doFilter(request, new MockHttpServletResponse(), chain);

        assertNull(chain.getRequest(), "篡改请求体必须验签失败");
        assertEquals("bad_sig", request.getAttribute(InternalAuthFilter.ATTR_RESULT));
    }

    @Test
    void bodyBoundSignatureRejectsDowngradeToLegacyVersion() throws Exception {
        MockHttpServletRequest request = jsonRequest("POST", "/internal/prescriptions",
                "{\"quantity\":2}");
        signCallerWithBody(request, "clinical", KEY_1, now(), "body-downgrade");
        // 试图把版本降级成 v1i 以绕过体校验：版本行参与签名，验签必然失败。
        // 这里用"未强制体绑定"的过滤器，确保失败原因是签名不符，而不是开关拦截。
        request.removeHeader(InternalSignature.HEADER_SIG_VER);
        request.addHeader(InternalSignature.HEADER_SIG_VER, InternalSignature.SIG_VERSION_CALLER);

        MockFilterChain chain = new MockFilterChain();
        filterWithBodyHash(false, 1024 * 1024)
                .doFilter(request, new MockHttpServletResponse(), chain);

        assertNull(chain.getRequest());
        assertEquals("bad_sig", request.getAttribute(InternalAuthFilter.ATTR_RESULT));
    }

    @Test
    void legacyV1iRejectedWhenBodyHashRequired() throws Exception {
        MockHttpServletRequest request = jsonRequest("POST", "/internal/prescriptions",
                "{\"quantity\":2}");
        signCaller(request, "clinical", KEY_1, now(), "legacy-required");

        MockFilterChain chain = new MockFilterChain();
        filterWithBodyHash(true, 1024 * 1024)
                .doFilter(request, new MockHttpServletResponse(), chain);

        assertNull(chain.getRequest(), "强制体绑定时旧版 v1i 必须被拒绝");
        assertEquals("body_hash_required", request.getAttribute(InternalAuthFilter.ATTR_RESULT));
    }

    @Test
    void legacyV1iStillAcceptedDuringGradualRollout() throws Exception {
        MockHttpServletRequest request = jsonRequest("POST", "/internal/prescriptions",
                "{\"quantity\":2}");
        signCaller(request, "clinical", KEY_1, now(), "legacy-rollout");

        MockFilterChain chain = new MockFilterChain();
        // 灰度期：服务端先升级但开关未打开，旧调用方仍可用
        filterWithBodyHash(false, 1024 * 1024)
                .doFilter(request, new MockHttpServletResponse(), chain);

        assertNotNull(chain.getRequest(), "灰度期必须同时接受 v1i 与 v1i2");
        assertEquals("ok", request.getAttribute(InternalAuthFilter.ATTR_RESULT));
    }

    @Test
    void oversizedBodyIsRejectedInsteadOfSkippingBodyCheck() throws Exception {
        char[] payload = new char[2048];
        java.util.Arrays.fill(payload, 'x');
        MockHttpServletRequest request = jsonRequest("POST", "/internal/prescriptions",
                "{\"note\":\"" + new String(payload) + "\"}");
        signCallerWithBody(request, "clinical", KEY_1, now(), "body-oversize");

        MockFilterChain chain = new MockFilterChain();
        // 上限 1KB：必须拒绝，而不是"读不到 body 就跳过校验"
        filterWithBodyHash(true, 1024)
                .doFilter(request, new MockHttpServletResponse(), chain);

        assertNull(chain.getRequest());
        assertEquals("body_too_large", request.getAttribute(InternalAuthFilter.ATTR_RESULT));
    }

    // ---------------------------------------------------------------- P0-8：网关通道体绑定（v1b/v2b）

    /** 网关通道：内部路径签名关闭、只开"要求体绑定"。 */
    private static InternalAuthFilter gatewayBodyFilter(boolean required) {
        InternalAuthProperties props = new InternalAuthProperties(
                InternalAuthProperties.ENFORCE, 60, KEYS_JSON, 120, false, null, null, null, null,
                null, null, null, required, 1024 * 1024);
        return new InternalAuthFilter(props, InternalKeySet.parse(KEYS_JSON, new ObjectMapper()),
                null);
    }

    /** 按网关 v1b 规则签名：请求体 SHA-256 纳入规范串。 */
    private static void signGatewayWithBody(MockHttpServletRequest request, String secret,
                                            String keyId, long timestamp, String nonce,
                                            String bodyHash, String version) {
        String canonical = InternalSignature.canonicalGateway(version, request.getMethod(),
                request.getRequestURI(), request.getQueryString(), identity(request), timestamp,
                nonce, bodyHash);
        request.addHeader(InternalSignature.HEADER_SIG_VER, version);
        request.addHeader(InternalSignature.HEADER_KEY_ID, keyId);
        request.addHeader(InternalSignature.HEADER_TS, Long.toString(timestamp));
        request.addHeader(InternalSignature.HEADER_NONCE, nonce);
        request.addHeader(InternalSignature.HEADER_SIG, InternalSignature.sign(secret, canonical));
    }

    @Test
    void gatewayBodyBoundSignatureAcceptedAndBodyRemainsReadable() throws Exception {
        String json = "{\"items\":[{\"itemCode\":\"TRT-IV\",\"quantity\":1}]}";
        MockHttpServletRequest request = request("POST", "/clinical/visits/1/orders");
        request.setContentType("application/json;charset=UTF-8");
        request.setContent(json.getBytes(StandardCharsets.UTF_8));
        signGatewayWithBody(request, KEY_2, "k2", now(), "gw-body-ok",
                InternalSignature.bodyHash(request.getContentAsByteArray()),
                InternalSignature.SIG_VERSION_GATEWAY_BODY);

        java.util.concurrent.atomic.AtomicReference<String> seen = new java.util.concurrent.atomic.AtomicReference<>();
        jakarta.servlet.http.HttpServlet servlet = new jakarta.servlet.http.HttpServlet() {
            @Override
            protected void service(jakarta.servlet.http.HttpServletRequest req,
                                   jakarta.servlet.http.HttpServletResponse resp)
                    throws java.io.IOException {
                seen.set(req.getReader().lines().collect(java.util.stream.Collectors.joining()));
            }
        };
        MockFilterChain chain = new MockFilterChain(servlet);
        gatewayBodyFilter(true).doFilter(request, new MockHttpServletResponse(), chain);

        assertNotNull(chain.getRequest(), "v1b 正确签名必须放行");
        assertEquals("ok", request.getAttribute(InternalAuthFilter.ATTR_RESULT));
        assertEquals(json, seen.get(), "验签后业务侧仍必须读到同一份请求体");
    }

    @Test
    void gatewayBodyBoundSignatureRejectsTamperedBody() throws Exception {
        MockHttpServletRequest request = request("POST", "/clinical/visits/1/orders");
        request.setContentType("application/json");
        request.setContent("{\"quantity\":1}".getBytes(StandardCharsets.UTF_8));
        signGatewayWithBody(request, KEY_2, "k2", now(), "gw-body-tampered",
                InternalSignature.bodyHash(request.getContentAsByteArray()),
                InternalSignature.SIG_VERSION_GATEWAY_BODY);
        // 在途改写医嘱体：签名不变，摘要不再匹配
        request.setContent("{\"quantity\":99}".getBytes(StandardCharsets.UTF_8));

        MockFilterChain chain = new MockFilterChain();
        gatewayBodyFilter(true).doFilter(request, new MockHttpServletResponse(), chain);

        assertNull(chain.getRequest(), "篡改请求体必须验签失败");
        assertEquals("bad_sig", request.getAttribute(InternalAuthFilter.ATTR_RESULT));
    }

    @Test
    void gatewayLegacyV1RejectedWhenBodyHashRequired() throws Exception {
        MockHttpServletRequest request = request("POST", "/clinical/visits/1/orders");
        request.setContentType("application/json");
        request.setContent("{\"quantity\":1}".getBytes(StandardCharsets.UTF_8));
        sign(request, KEY_2, "k2", now(), "gw-legacy-required");

        MockFilterChain chain = new MockFilterChain();
        gatewayBodyFilter(true).doFilter(request, new MockHttpServletResponse(), chain);

        assertNull(chain.getRequest(), "强制体绑定时网关也必须用 v1b/v2b");
        assertEquals("body_hash_required", request.getAttribute(InternalAuthFilter.ATTR_RESULT));
    }

    @Test
    void gatewayLegacyV1StillAcceptedDuringGradualRollout() throws Exception {
        MockHttpServletRequest request = request("POST", "/clinical/visits/1/orders");
        request.setContentType("application/json");
        request.setContent("{\"quantity\":1}".getBytes(StandardCharsets.UTF_8));
        sign(request, KEY_2, "k2", now(), "gw-legacy-rollout");

        MockFilterChain chain = new MockFilterChain();
        gatewayBodyFilter(false).doFilter(request, new MockHttpServletResponse(), chain);

        assertNotNull(chain.getRequest(), "灰度期必须同时接受 v1 与 v1b");
        assertEquals("ok", request.getAttribute(InternalAuthFilter.ATTR_RESULT));
    }

    @Test
    void gatewayMultipartUploadsAreSignedWithoutBodyDigest() throws Exception {
        MockHttpServletRequest request = request("POST", "/kb/spaces/1/documents");
        request.setContentType("multipart/form-data; boundary=----x");
        request.setContent("------x\r\ncontent\r\n------x--".getBytes(StandardCharsets.UTF_8));
        // 上传类两侧都按 EMPTY_BODY_HASH 约定签名（避免网关把大文件聚合进内存）
        signGatewayWithBody(request, KEY_2, "k2", now(), "gw-upload",
                InternalSignature.EMPTY_BODY_HASH, InternalSignature.SIG_VERSION_GATEWAY_BODY);

        MockFilterChain chain = new MockFilterChain();
        gatewayBodyFilter(true).doFilter(request, new MockHttpServletResponse(), chain);

        assertNotNull(chain.getRequest(), "multipart 不能因为强制体绑定而全部 403");
        assertEquals("ok", request.getAttribute(InternalAuthFilter.ATTR_RESULT));
    }
}
