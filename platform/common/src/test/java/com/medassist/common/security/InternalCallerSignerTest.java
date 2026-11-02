package com.medassist.common.security;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 出站调用方签名器（T5）：未配置时不加头；配置后生成的签名可被同一协议验证。
 */
class InternalCallerSignerTest {

    private static final String SECRET = Base64.getEncoder()
            .encodeToString("0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8));

    @Test
    void disabledSignerAddsNothing() {
        InternalCallerSigner signer = InternalCallerSigner.disabled();
        assertFalse(signer.configured());
        assertTrue(signer.headers("GET", "/internal/patients/1", null).isEmpty());

        assertFalse(InternalCallerSigner.of("clinical", null).configured());
        assertFalse(InternalCallerSigner.of(null, SECRET).configured());
    }

    @Test
    void headersAreVerifiableWithCallerProtocol() {
        InternalCallerSigner signer = InternalCallerSigner.of("Clinical", SECRET);
        assertTrue(signer.configured());

        Map<String, String> headers = signer.headers("get", "/internal/patients/1", "b=2&a=1");
        assertEquals(InternalSignature.SIG_VERSION_CALLER, headers.get(InternalSignature.HEADER_SIG_VER));
        assertEquals("clinical", headers.get(InternalSignature.HEADER_CALLER), "caller 统一小写");

        String canonical = InternalSignature.canonicalCaller("GET", "/internal/patients/1", "a=1&b=2",
                "clinical", Long.parseLong(headers.get(InternalSignature.HEADER_TS)),
                headers.get(InternalSignature.HEADER_NONCE), InternalSignature.EMPTY_BODY_HASH);
        assertTrue(InternalSignature.verify(SECRET, canonical, headers.get(InternalSignature.HEADER_SIG)));
    }

    @Test
    void differentCallersProduceDifferentSignatures() {
        Map<String, String> a = InternalCallerSigner.of("clinical", SECRET)
                .headers("GET", "/internal/patients/1", null);
        Map<String, String> b = InternalCallerSigner.of("emr", SECRET)
                .headers("GET", "/internal/patients/1", null);
        // nonce 不同 → 签名天然不同；再验证"换 caller 用原签名必失败"由过滤器用例覆盖
        assertFalse(a.get(InternalSignature.HEADER_SIG).equals(b.get(InternalSignature.HEADER_SIG)));
        assertEquals("clinical", a.get(InternalSignature.HEADER_CALLER));
        assertEquals("emr", b.get(InternalSignature.HEADER_CALLER));
    }

    // ---------------------------------------------------------------- P0-8：请求体绑定

    @Test
    void bodyBoundSignerUsesV1i2AndBindsBody() {
        byte[] body = "{\"quantity\":2}".getBytes(StandardCharsets.UTF_8);
        InternalCallerSigner signer = InternalCallerSigner.of("clinical", SECRET, true);
        assertTrue(signer.bodyHashEnabled());

        Map<String, String> headers = signer.headers("POST", "/internal/prescriptions", null, body);
        assertEquals(InternalSignature.SIG_VERSION_CALLER_BODY,
                headers.get(InternalSignature.HEADER_SIG_VER));

        String canonical = InternalSignature.canonicalCaller(
                InternalSignature.SIG_VERSION_CALLER_BODY, "POST", "/internal/prescriptions", null,
                "clinical", Long.parseLong(headers.get(InternalSignature.HEADER_TS)),
                headers.get(InternalSignature.HEADER_NONCE), InternalSignature.bodyHash(body));
        assertTrue(InternalSignature.verify(SECRET, canonical,
                headers.get(InternalSignature.HEADER_SIG)));

        // 换一份请求体（模拟在途篡改）：同一签名不可通过
        String tampered = InternalSignature.canonicalCaller(
                InternalSignature.SIG_VERSION_CALLER_BODY, "POST", "/internal/prescriptions", null,
                "clinical", Long.parseLong(headers.get(InternalSignature.HEADER_TS)),
                headers.get(InternalSignature.HEADER_NONCE),
                InternalSignature.bodyHash("{\"quantity\":200}".getBytes(StandardCharsets.UTF_8)));
        assertFalse(InternalSignature.verify(SECRET, tampered,
                headers.get(InternalSignature.HEADER_SIG)));
    }

    @Test
    void signerKeepsLegacyVersionWhenBodyBindingDisabled() {
        InternalCallerSigner signer = InternalCallerSigner.of("clinical", SECRET, false);
        assertFalse(signer.bodyHashEnabled());

        Map<String, String> headers = signer.headers("POST", "/internal/prescriptions", null,
                "{\"quantity\":2}".getBytes(StandardCharsets.UTF_8));
        assertEquals(InternalSignature.SIG_VERSION_CALLER,
                headers.get(InternalSignature.HEADER_SIG_VER));
    }
}
