package com.medassist.common.config;

import com.medassist.common.security.InternalCallerSigner;
import com.medassist.common.web.InternalAuthFilter;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 自动装配与启动守卫（MF-07）：enforce 无密钥、生产 off、非法 mode 都必须拒绝启动。
 */
class InternalAuthAutoConfigurationTest {

    private static final String KEY = Base64.getEncoder()
            .encodeToString("0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8));
    private static final String KEYS_JSON = "{\"active\":\"k1\",\"keys\":{\"k1\":\"" + KEY + "\"}}";
    private static final String KEYS_JSON_WITH_INTERNAL = "{\"active\":\"k1\",\"keys\":{\"k1\":\"" + KEY
            + "\"},\"internal\":{\"clinical\":\"" + KEY + "\"}}";

    private final WebApplicationContextRunner runner = new WebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(InternalAuthAutoConfiguration.class))
            // 显式声明非生产：isProduction 现在"无 profile 即按生产处理"（P0-10 ③ 缺省拒绝），
            // 单元测试必须显式声明 dev，否则每个用例都会命中生产守卫。
            .withPropertyValues("spring.profiles.active=dev");

    @Test
    void registersFilterAndRegistrationBeanWhenKeysConfigured() {
        runner.withPropertyValues(
                        "medassist.internal-auth.mode=log",
                        "medassist.internal-auth.keys-json=" + KEYS_JSON)
                .run(context -> {
                    assertThat(context).hasSingleBean(InternalAuthFilter.class);
                    assertThat(context).hasBean("internalAuthFilterRegistration");
                });
    }

    @Test
    void defaultsToOffOutsideProduction() {
        runner.run(context -> assertThat(context).hasSingleBean(InternalAuthFilter.class));
    }

    @Test
    void failsFastWhenEnforceWithoutKeys() {
        runner.withPropertyValues("medassist.internal-auth.mode=enforce")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasMessageContaining("keys-json");
                });
    }

    @Test
    void rejectsOffInProductionProfile() {
        runner.withPropertyValues(
                        "medassist.internal-auth.mode=off",
                        "spring.profiles.active=prod")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasMessageContaining("mode=off");
                });
    }

    @Test
    void rejectsUnknownMode() {
        runner.withPropertyValues("medassist.internal-auth.mode=strict",
                        "medassist.internal-auth.keys-json=" + KEYS_JSON)
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasMessageContaining("非法");
                });
    }

    @Test
    void derivesCallerFromApplicationNameForOutboundSigning() {
        runner.withPropertyValues(
                        "spring.application.name=platform-clinical",
                        "medassist.internal-auth.mode=log",
                        "medassist.internal-auth.keys-json=" + KEYS_JSON_WITH_INTERNAL)
                .run(context -> {
                    assertThat(context).hasSingleBean(InternalCallerSigner.class);
                    InternalCallerSigner signer = context.getBean(InternalCallerSigner.class);
                    assertThat(signer.caller()).isEqualTo("clinical");
                    assertThat(signer.configured()).isTrue();
                });
    }

    @Test
    void failsFastWhenCallerSignatureRequiredWithoutInternalKeys() {
        runner.withPropertyValues(
                        "medassist.internal-auth.mode=log",
                        "medassist.internal-auth.keys-json=" + KEYS_JSON,
                        "medassist.internal-auth.internal-paths-signature-required=true")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasMessageContaining("internal");
                });
    }

    @Test
    void failsFastWhenOwnCallerKeyMissing() {
        runner.withPropertyValues(
                        "spring.application.name=platform-pharmacy",
                        "medassist.internal-auth.mode=log",
                        "medassist.internal-auth.keys-json=" + KEYS_JSON_WITH_INTERNAL,
                        "medassist.internal-auth.internal-paths-signature-required=true")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasMessageContaining("pharmacy");
                });
    }

    @Test
    void rejectsNonceTtlShorterThanTwiceClockSkew() {
        runner.withPropertyValues(
                        "medassist.internal-auth.mode=log",
                        "medassist.internal-auth.keys-json=" + KEYS_JSON,
                        "medassist.internal-auth.clock-skew-seconds=300",
                        "medassist.internal-auth.nonce-ttl-seconds=120")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasMessageContaining("nonce-ttl-seconds");
                });
    }

    @Test
    void rejectsInvalidBase64Key() {
        String broken = "{\"active\":\"k1\",\"keys\":{\"k1\":\"not-base64!!!\"}}";
        runner.withPropertyValues(
                        "medassist.internal-auth.mode=log",
                        "medassist.internal-auth.keys-json=" + broken)
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasMessageContaining("Base64");
                });
    }

    // ---------------------------------------------------------------- 阶段二：Ed25519 与按调用方密钥

    private static String publicKeyBase64() throws Exception {
        java.security.KeyPair pair = java.security.KeyPairGenerator.getInstance("Ed25519")
                .generateKeyPair();
        return Base64.getEncoder().encodeToString(pair.getPublic().getEncoded());
    }

    @Test
    void enforceAcceptsPublicKeyOnlyConfiguration() throws Exception {
        runner.withPropertyValues(
                        "medassist.internal-auth.mode=enforce",
                        "medassist.internal-auth.gateway-signing=ed25519",
                        "medassist.internal-auth.gateway-public-key=" + publicKeyBase64())
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(InternalAuthFilter.class);
                });
    }

    @Test
    void rejectsMalformedPublicKeyAtStartup() {
        runner.withPropertyValues(
                        "medassist.internal-auth.mode=enforce",
                        "medassist.internal-auth.gateway-public-key=not-a-key")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasMessageContaining("gateway-public-key");
                });
    }

    @Test
    void rejectsUnknownGatewaySigningAlgorithm() {
        runner.withPropertyValues(
                        "medassist.internal-auth.mode=log",
                        "medassist.internal-auth.keys-json=" + KEYS_JSON,
                        "medassist.internal-auth.gateway-signing=rsa")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasMessageContaining("gateway-signing");
                });
    }

    @Test
    void rejectsProductionWithoutInternalCallerSignature() {
        // P0-9：生产只靠单值共享令牌时，一处令牌泄露即可冒充任意调用方读 PHI
        runner.withPropertyValues(
                        "spring.profiles.active=prod",
                        "medassist.internal-auth.mode=enforce",
                        "medassist.internal-auth.keys-json=" + KEYS_JSON,
                        "medassist.internal-auth.internal-paths-signature-required=false")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasMessageContaining("internal-paths-signature-required");
                });
    }

    @Test
    void allowsBodyHashRequiredWithoutCallerSignatureForGatewayChannel() {
        // P0-8：体绑定对"网关通道（v1b/v2b）"与"调用方通道（v1i2）"各自独立生效，
        // 只开体绑定、暂不强制 /internal 调用方签名是合法灰度组合（网关流量已必须带体）。
        runner.withPropertyValues(
                        "spring.application.name=platform-clinical",
                        "medassist.internal-auth.mode=log",
                        "medassist.internal-auth.keys-json=" + KEYS_JSON_WITH_INTERNAL,
                        "medassist.internal-auth.body-hash-required=true",
                        "medassist.internal-auth.internal-paths-signature-required=false")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    InternalCallerSigner signer = context.getBean(InternalCallerSigner.class);
                    assertThat(signer.configured()).isTrue();
                });
    }

    @Test
    void callerKeysAreBoundFromPerServiceProperties() {
        // Helm 按组件只注入需要的条目：MEDASSIST_INTERNALAUTH_CALLERKEYS_CLINICAL
        runner.withPropertyValues(
                        "spring.application.name=platform-clinical",
                        "medassist.internal-auth.mode=log",
                        "medassist.internal-auth.internal-paths-signature-required=true",
                        "medassist.internal-auth.caller-keys.clinical=" + KEY)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    InternalCallerSigner signer = context.getBean(InternalCallerSigner.class);
                    assertThat(signer.caller()).isEqualTo("clinical");
                    assertThat(signer.configured()).isTrue();
                });
    }

    @Test
    void perServiceCallerKeysDoNotLeakOtherCallers() {
        runner.withPropertyValues(
                        "spring.application.name=platform-pharmacy",
                        "medassist.internal-auth.mode=log",
                        "medassist.internal-auth.internal-paths-signature-required=true",
                        "medassist.internal-auth.caller-keys.clinical=" + KEY)
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasMessageContaining("pharmacy");
                });
    }
}
