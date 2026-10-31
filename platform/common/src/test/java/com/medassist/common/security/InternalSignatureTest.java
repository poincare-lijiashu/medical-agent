package com.medassist.common.security;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 服务间签名协议（MF-07）单元测试：规范串稳定性、篡改检测、密钥轮换。
 */
class InternalSignatureTest {

    private static final String KEY_1 = Base64.getEncoder()
            .encodeToString("0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8));
    private static final String KEY_2 = Base64.getEncoder()
            .encodeToString("fedcba9876543210fedcba9876543210".getBytes(StandardCharsets.UTF_8));

    private static Map<String, String> identity() {
        Map<String, String> map = new HashMap<>();
        map.put(AuthHeaders.ACTOR, "doctor01");
        map.put(AuthHeaders.USER_ID, "8f0c2a9e-1111-2222-3333-444455556666");
        map.put(AuthHeaders.ROLES, "DOCTOR");
        map.put(AuthHeaders.PERMISSIONS, "clinical:visit:read,clinical:order:write");
        map.put(AuthHeaders.TOKEN_ID, "jti-001");
        map.put(AuthHeaders.ORG, "DEPT-IM");
        map.put(AuthHeaders.CLIENT_IP, "10.1.2.3");
        map.put(AuthHeaders.TRACE_ID, "trace-001");
        return map;
    }

    @Test
    void canonicalIsStableAndQueryOrderIndependent() {
        String a = InternalSignature.canonical("get", "/clinical/visits", "b=2&a=1",
                identity(), 1793512800L, "nonce-1", InternalSignature.EMPTY_BODY_HASH);
        String b = InternalSignature.canonical("GET", "/clinical/visits", "a=1&b=2",
                identity(), 1793512800L, "nonce-1", InternalSignature.EMPTY_BODY_HASH);
        assertEquals(a, b, "同一语义不同 query 顺序必须产生同一规范串");
        assertTrue(a.startsWith("v1\nGET\n/clinical/visits\n"));
    }

    @Test
    void canonicalContainsAllIdentityHeadersInFixedOrder() {
        String canonical = InternalSignature.canonical("GET", "/emr/records/1", null,
                identity(), 1L, "n", InternalSignature.EMPTY_BODY_HASH);
        String[] lines = canonical.split("\n", -1);
        assertEquals(InternalSignature.SIG_VERSION, lines[0]);
        assertEquals("GET", lines[1]);
        assertEquals("/emr/records/1", lines[2]);
        assertEquals("", lines[3], "无 query 时保留空行而不是省略");
        assertEquals("doctor01", lines[4]);
        assertEquals("DOCTOR", lines[6]);
        assertEquals("jti-001", lines[8]);
        assertEquals("trace-001", lines[11]);
        assertEquals("1", lines[12]);
        assertEquals("n", lines[13]);
        assertEquals("-", lines[14]);
    }

    @Test
    void signAndVerifyRoundTrip() {
        String canonical = InternalSignature.canonical("POST", "/insurance/bills", null,
                identity(), 1793512800L, "nonce-1", InternalSignature.EMPTY_BODY_HASH);
        String signature = InternalSignature.sign(KEY_1, canonical);
        assertTrue(InternalSignature.verify(KEY_1, canonical, signature));
        assertFalse(InternalSignature.verify(KEY_2, canonical, signature), "换密钥必须验签失败");
    }

    @Test
    void verifyRejectsTamperedIdentityHeader() {
        Map<String, String> tampered = identity();
        tampered.put(AuthHeaders.PERMISSIONS, "clinical:visit:read,emr:record:read");
        String canonical = InternalSignature.canonical("GET", "/emr/records/1", null,
                identity(), 1793512800L, "nonce-1", InternalSignature.EMPTY_BODY_HASH);
        String signature = InternalSignature.sign(KEY_1, canonical);
        String tamperedCanonical = InternalSignature.canonical("GET", "/emr/records/1", null,
                tampered, 1793512800L, "nonce-1", InternalSignature.EMPTY_BODY_HASH);
        assertFalse(InternalSignature.verify(KEY_1, tamperedCanonical, signature));
    }

    @Test
    void verifyRejectsTamperedPathOrQueryOrTimestamp() {
        String signature = InternalSignature.sign(KEY_1, InternalSignature.canonical(
                "GET", "/clinical/visits", "limit=10", identity(), 1793512800L, "n",
                InternalSignature.EMPTY_BODY_HASH));
        assertFalse(InternalSignature.verify(KEY_1, InternalSignature.canonical(
                "GET", "/clinical/visits/other", "limit=10", identity(), 1793512800L, "n",
                InternalSignature.EMPTY_BODY_HASH), signature));
        assertFalse(InternalSignature.verify(KEY_1, InternalSignature.canonical(
                "GET", "/clinical/visits", "limit=100000", identity(), 1793512800L, "n",
                InternalSignature.EMPTY_BODY_HASH), signature));
        assertFalse(InternalSignature.verify(KEY_1, InternalSignature.canonical(
                "GET", "/clinical/visits", "limit=10", identity(), 1793512801L, "n",
                InternalSignature.EMPTY_BODY_HASH), signature));
    }

    @Test
    void emptySignatureNeverVerifies() {
        String canonical = InternalSignature.canonical("GET", "/", null, Map.of(), 1L, "n", "-");
        assertFalse(InternalSignature.verify(KEY_1, canonical, null));
        assertFalse(InternalSignature.verify(KEY_1, canonical, ""));
    }

    @Test
    void queryNormalizationKeepsFirstValueAndToleratesMissingEquals() {
        assertEquals("a=1&b=3", InternalSignature.normalizeQuery("b=3&a=1"));
        assertEquals("a=1", InternalSignature.normalizeQuery("a=1&a=2"));
        assertEquals("flag=&x=1", InternalSignature.normalizeQuery("flag&x=1"));
        assertEquals("", InternalSignature.normalizeQuery(""));
        assertEquals("", InternalSignature.normalizeQuery(null));
    }

    @Test
    void bodyHashEmptyBodyIsDashAndStableForContent() {
        assertEquals(InternalSignature.EMPTY_BODY_HASH, InternalSignature.bodyHash(null));
        assertEquals(InternalSignature.EMPTY_BODY_HASH, InternalSignature.bodyHash(new byte[0]));
        String hash = InternalSignature.bodyHash("{\"a\":1}".getBytes(StandardCharsets.UTF_8));
        assertEquals(43, hash.length(), "SHA-256 的 Base64URL 定长 43 字符");
        assertEquals(hash, InternalSignature.bodyHash("{\"a\":1}".getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void keySetParsesActiveAndRotationKeys() {
        String json = "{\"active\":\"k2\",\"keys\":{\"k1\":\"" + KEY_1 + "\",\"k2\":\"" + KEY_2 + "\"}}";
        InternalKeySet keySet = InternalKeySet.parse(json, new com.fasterxml.jackson.databind.ObjectMapper());
        assertTrue(keySet.configured());
        assertEquals("k2", keySet.activeKeyId());
        assertEquals(KEY_1, keySet.secret("k1"));
        assertEquals(KEY_2, keySet.secret("k2"));
        assertEquals(null, keySet.secret("k3"));
        assertFalse(InternalKeySet.empty().configured());
    }
}
