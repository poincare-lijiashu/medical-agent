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
    void canonicalCallerBindsCallerAndHasNoIdentityHeaders() {
        String a = InternalSignature.canonicalCaller("get", "/internal/patients/1", "b=2&a=1",
                "clinical", 1793512800L, "n", InternalSignature.EMPTY_BODY_HASH);
        String b = InternalSignature.canonicalCaller("GET", "/internal/patients/1", "a=1&b=2",
                "clinical", 1793512800L, "n", InternalSignature.EMPTY_BODY_HASH);
        assertEquals(a, b);
        String[] lines = a.split("\n", -1);
        assertEquals("v1i", lines[0]);
        assertEquals("GET", lines[1]);
        assertEquals("/internal/patients/1", lines[2]);
        assertEquals("a=1&b=2", lines[3]);
        assertEquals("clinical", lines[4], "caller 必须进入规范串");
        assertEquals("1793512800", lines[5]);
        assertEquals("n", lines[6]);
        assertEquals("-", lines[7]);

        String other = InternalSignature.canonicalCaller("GET", "/internal/patients/1", "a=1&b=2",
                "emr", 1793512800L, "n", InternalSignature.EMPTY_BODY_HASH);
        assertFalse(a.equals(other), "换 caller 必须改变规范串");
    }

    @Test
    void keySetParsesActiveAndRotationKeys() {
        String json = "{\"active\":\"k2\",\"keys\":{\"k1\":\"" + KEY_1 + "\",\"k2\":\"" + KEY_2 + "\"},"
                + "\"internal\":{\"clinical\":\"" + KEY_1 + "\",\"EMR\":\"" + KEY_2 + "\"}}";
        InternalKeySet keySet = InternalKeySet.parse(json, new com.fasterxml.jackson.databind.ObjectMapper());
        assertTrue(keySet.configured());
        assertEquals("k2", keySet.activeKeyId());
        assertEquals(KEY_1, keySet.secret("k1"));
        assertEquals(KEY_2, keySet.secret("k2"));
        assertEquals(null, keySet.secret("k3"));
        assertTrue(keySet.callerConfigured());
        assertEquals(KEY_1, keySet.callerSecret("clinical"));
        assertEquals(KEY_2, keySet.callerSecret("emr"), "调用方标识大小写不敏感");
        assertEquals(null, keySet.callerSecret("billing"));
        assertFalse(InternalKeySet.empty().configured());
    }

    // ---------------------------------------------------------------- v2：Ed25519（阶段二）

    private static java.security.KeyPair ed25519() throws Exception {
        return java.security.KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
    }

    @Test
    void canonicalV2HasDifferentVersionLineButSameStructure() {
        String v1 = InternalSignature.canonical("GET", "/clinical/visits", "a=1",
                identity(), 1793512800L, "n", InternalSignature.EMPTY_BODY_HASH);
        String v2 = InternalSignature.canonicalGatewayV2("GET", "/clinical/visits", "a=1",
                identity(), 1793512800L, "n", InternalSignature.EMPTY_BODY_HASH);
        assertEquals("v1\n" + v1.substring(3), v1);
        assertTrue(v2.startsWith(InternalSignature.SIG_VERSION_GATEWAY_ED25519 + "\n"));
        // 除版本行外逐字节一致：保证两种算法覆盖范围相同
        assertEquals(v1.substring(3), v2.substring(3));
    }

    @Test
    void gatewayVersionHelpersAndBodyHashability() {
        assertTrue(InternalSignature.isGatewayBodyVersion(InternalSignature.SIG_VERSION_GATEWAY_BODY));
        assertTrue(InternalSignature.isGatewayBodyVersion(
                InternalSignature.SIG_VERSION_GATEWAY_ED25519_BODY));
        assertFalse(InternalSignature.isGatewayBodyVersion(InternalSignature.SIG_VERSION));
        assertFalse(InternalSignature.isGatewayBodyVersion(
                InternalSignature.SIG_VERSION_GATEWAY_ED25519));

        assertTrue(InternalSignature.isGatewayEd25519Version(
                InternalSignature.SIG_VERSION_GATEWAY_ED25519_BODY));
        assertFalse(InternalSignature.isGatewayEd25519Version(
                InternalSignature.SIG_VERSION_GATEWAY_BODY));

        // 体摘要判据必须两侧一致：JSON/表单/文本摘要，上传类不摘要
        assertTrue(InternalSignature.isBodyHashable("application/json;charset=UTF-8"));
        assertTrue(InternalSignature.isBodyHashable("application/x-www-form-urlencoded"));
        assertTrue(InternalSignature.isBodyHashable(null));
        assertFalse(InternalSignature.isBodyHashable("multipart/form-data; boundary=x"));
        assertFalse(InternalSignature.isBodyHashable("application/octet-stream"));
        assertFalse(InternalSignature.isBodyHashable("image/png"));

        // 版本行参与签名：同一份请求换版本号后签名必须不同
        String v1b = InternalSignature.canonicalGateway(InternalSignature.SIG_VERSION_GATEWAY_BODY,
                "POST", "/clinical/orders", null, identity(), 1793512800L, "n", "BODYHASH");
        String v2b = InternalSignature.canonicalGateway(
                InternalSignature.SIG_VERSION_GATEWAY_ED25519_BODY, "POST", "/clinical/orders",
                null, identity(), 1793512800L, "n", "BODYHASH");
        assertEquals(v1b.substring(4), v2b.substring(4));
        assertTrue(v1b.endsWith("\nBODYHASH"));
    }

    @Test
    void ed25519SignVerifyRoundTripAndTamperDetection() throws Exception {
        java.security.KeyPair pair = ed25519();
        String canonical = InternalSignature.canonicalGatewayV2("GET", "/clinical/visits", null,
                identity(), 1793512800L, "nonce-v2", InternalSignature.EMPTY_BODY_HASH);
        String signature = InternalSignature.signEd25519(pair.getPrivate(), canonical);
        assertTrue(InternalSignature.verifyEd25519(pair.getPublic(), canonical, signature));

        Map<String, String> tampered = identity();
        tampered.put(AuthHeaders.PERMISSIONS, "clinical:visit:read,emr:record:read");
        String tamperedCanonical = InternalSignature.canonicalGatewayV2("GET", "/clinical/visits",
                null, tampered, 1793512800L, "nonce-v2", InternalSignature.EMPTY_BODY_HASH);
        assertFalse(InternalSignature.verifyEd25519(pair.getPublic(), tamperedCanonical, signature),
                "篡改身份头必须验签失败");

        java.security.KeyPair other = ed25519();
        assertFalse(InternalSignature.verifyEd25519(other.getPublic(), canonical, signature),
                "换公钥必须验签失败");
        assertFalse(InternalSignature.verifyEd25519(pair.getPublic(), canonical, "not-base64!!"));
        assertFalse(InternalSignature.verifyEd25519(pair.getPublic(), canonical, ""));
    }

    @Test
    void ed25519KeyParsingAcceptsBase64AndRejectsGarbage() throws Exception {
        java.security.KeyPair pair = ed25519();
        String privateB64 = Base64.getEncoder().encodeToString(pair.getPrivate().getEncoded());
        String publicB64 = Base64.getEncoder().encodeToString(pair.getPublic().getEncoded());
        assertTrue(InternalSignature.parsePrivateKey(privateB64) instanceof java.security.PrivateKey);
        assertTrue(InternalSignature.parsePublicKey(publicB64) instanceof java.security.PublicKey);

        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
                () -> InternalSignature.parsePrivateKey("not-base64!!!"));
        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
                () -> InternalSignature.parsePublicKey(publicB64.substring(0, 8)),
                "长度不足 32 字节的密钥必须拒绝");
        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
                () -> InternalSignature.parsePublicKey(""));
    }

    @Test
    void callerKeysCanBeInjectedPerServiceWithoutTouchingGatewayKeys() {
        InternalKeySet base = InternalKeySet.parse(
                "{\"active\":\"k1\",\"keys\":{\"k1\":\"" + KEY_1 + "\"}}",
                new com.fasterxml.jackson.databind.ObjectMapper());
        assertFalse(base.callerConfigured());

        InternalKeySet withCallers = base.withCallerKeys(Map.of(
                "Clinical", KEY_1, "emr", KEY_2));
        assertTrue(withCallers.callerConfigured());
        assertEquals(KEY_1, withCallers.callerSecret("clinical"), "调用方名统一小写");
        assertEquals(KEY_2, withCallers.callerSecret("emr"));
        assertEquals(null, withCallers.callerSecret("pharmacy"), "未挂载的调用方不可用");
        // 只合并调用方通道，网关通道不受影响
        assertEquals(KEY_1, withCallers.secret("k1"));
        assertFalse(base.callerConfigured(), "原实例不可变");
    }

    @Test
    void callerKeysRejectInvalidBase64() {
        InternalKeySet base = InternalKeySet.parse("{\"active\":\"k1\",\"keys\":{\"k1\":\"" + KEY_1 + "\"}}",
                new com.fasterxml.jackson.databind.ObjectMapper());
        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
                () -> base.withCallerKeys(Map.of("clinical", "bad!!")));
    }
}
