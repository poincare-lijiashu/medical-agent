package com.medassist.gateway.config;

import com.medassist.common.security.InternalAuthProperties;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 网关签名启动守卫（MF-07）：算法与密钥必须匹配，坏配置拒绝启动而不是"起来了但全站 403"。
 */
class GatewayInternalSignConfigTest {

    private static final String HMAC_KEY = Base64.getEncoder()
            .encodeToString("0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8));

    private static InternalAuthProperties props(String mode, String signing, String keysJson,
                                                String privateKey) {
        return new InternalAuthProperties(mode, 60, keysJson, 180, false, null, null,
                signing, "gw-v2", privateKey, null, null, null, null);
    }

    @Test
    void ed25519RequiresPrivateKey() {
        GatewayInternalSignConfig config = new GatewayInternalSignConfig();
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> config.internalSignatureGlobalFilter(
                        props(InternalAuthProperties.ENFORCE, "ed25519", null, null)));
        assertTrue(error.getMessage().contains("gateway-private-key"));
    }

    @Test
    void hmacRequiresKeysJson() {
        GatewayInternalSignConfig config = new GatewayInternalSignConfig();
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> config.internalSignatureGlobalFilter(
                        props(InternalAuthProperties.ENFORCE, "hmac", "{\"active\":\"k1\",\"keys\":{}}", null)));
        assertTrue(error.getMessage().contains("keys-json"));
    }

    @Test
    void rejectsUnknownSigningAlgorithm() {
        GatewayInternalSignConfig config = new GatewayInternalSignConfig();
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> config.internalSignatureGlobalFilter(
                        props(InternalAuthProperties.LOG, "rsa", null, null)));
        assertTrue(error.getMessage().contains("gateway-signing"));
    }

    @Test
    void ed25519WithPrivateKeyIsAcceptedEvenWithoutHmacKeys() throws Exception {
        KeyPair pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        String privateKey = Base64.getEncoder().encodeToString(pair.getPrivate().getEncoded());
        GatewayInternalSignConfig config = new GatewayInternalSignConfig();
        assertTrue(config.internalSignatureGlobalFilter(
                props(InternalAuthProperties.ENFORCE, "ed25519", null, privateKey)) != null);
    }

    @Test
    void offModeNeedsNoKeys() {
        GatewayInternalSignConfig config = new GatewayInternalSignConfig();
        assertTrue(config.internalSignatureGlobalFilter(
                props(InternalAuthProperties.OFF, "hmac", null, null)) != null);
    }

    @Test
    void hmacModeStillWorksWithValidKeys() {
        GatewayInternalSignConfig config = new GatewayInternalSignConfig();
        String json = "{\"active\":\"k1\",\"keys\":{\"k1\":\"" + HMAC_KEY + "\"}}";
        assertTrue(config.internalSignatureGlobalFilter(
                props(InternalAuthProperties.ENFORCE, "hmac", json, null)) != null);
        assertEquals(InternalAuthProperties.ENFORCE,
                props(InternalAuthProperties.ENFORCE, "hmac", json, null).modeOrDefault());
    }
}
