package com.medassist.auth.security;

import com.medassist.common.security.SecurityDefaults;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 生产环境判定：决定"是否允许开发默认密钥 / 是否强制内部签名"，判错会让生产鉴权守卫整体失效。
 *
 * <p>关键回归点：
 * <ol>
 *   <li>Helm Chart 的默认运行 profile 是 {@code k8s}（不是 prod），早期只认 prod/staging；</li>
 *   <li>裸机部署未设 profile 时必须按生产处理（P0-10 ③ 缺省拒绝）；</li>
 *   <li>显式 {@code MEDASSIST_ENV} 优先于 profile 探测。</li>
 * </ol>
 */
class SecurityDefaultsTest {

    @Test
    @DisplayName("k8s profile 按生产处理（Helm 默认运行 profile）")
    void k8sProfileCountsAsProduction() {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles("k8s");

        assertThat(SecurityDefaults.isProduction(env, null)).isTrue();
    }

    @Test
    @DisplayName("prod / production / staging 均按生产处理")
    void prodLikeProfilesCountAsProduction() {
        for (String profile : new String[]{"prod", "production", "staging", "PROD"}) {
            MockEnvironment env = new MockEnvironment();
            env.setActiveProfiles(profile);
            assertThat(SecurityDefaults.isProduction(env, null))
                    .as("profile=%s", profile)
                    .isTrue();
        }
    }

    @Test
    @DisplayName("dev profile 在集群外不按生产处理（本地联调保留默认密钥）")
    void devProfileIsNotProductionOutsideCluster() {
        Assumptions.assumeTrue(System.getenv("KUBERNETES_SERVICE_HOST") == null,
                "在 Kubernetes 集群内运行时该断言不适用");
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles("dev");

        assertThat(SecurityDefaults.isProduction(env, null)).isFalse();
    }

    @Test
    @DisplayName("未声明任何 profile 时按生产处理（缺省拒绝，P0-10 ③）")
    void noProfileCountsAsProduction() {
        Assumptions.assumeTrue(System.getenv("KUBERNETES_SERVICE_HOST") == null,
                "在 Kubernetes 集群内运行时该断言不适用");

        assertThat(SecurityDefaults.isProduction(new MockEnvironment(), null)).isTrue();
    }

    @Test
    @DisplayName("无法判定（Environment 为 null）时按生产处理")
    void nullEnvironmentCountsAsProduction() {
        Assumptions.assumeTrue(System.getenv("KUBERNETES_SERVICE_HOST") == null,
                "在 Kubernetes 集群内运行时该断言不适用");

        assertThat(SecurityDefaults.isProduction(null, null)).isTrue();
    }

    @Test
    @DisplayName("显式 MEDASSIST_ENV 优先：dev 类取值不按生产，其余一律按生产")
    void declaredEnvOverridesProfileDetection() {
        MockEnvironment prodProfile = new MockEnvironment();
        prodProfile.setActiveProfiles("prod");

        // 显式声明 dev：即便误开了 prod profile 也按非生产（运维唯一权威开关）
        assertThat(SecurityDefaults.isProduction(prodProfile, "dev")).isFalse();
        assertThat(SecurityDefaults.isProduction(prodProfile, "LOCAL")).isFalse();
        assertThat(SecurityDefaults.isProduction(prodProfile, "test")).isFalse();

        // 其余任何取值（含拼写错误）都按生产处理
        assertThat(SecurityDefaults.isProduction(new MockEnvironment(), "prod")).isTrue();
        assertThat(SecurityDefaults.isProduction(new MockEnvironment(), "prd")).isTrue();
        assertThat(SecurityDefaults.isProduction(new MockEnvironment(), " ")).isTrue();
    }
}
