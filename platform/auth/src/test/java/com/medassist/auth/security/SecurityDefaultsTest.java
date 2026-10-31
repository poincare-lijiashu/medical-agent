package com.medassist.auth.security;

import com.medassist.common.security.SecurityDefaults;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 生产环境判定：决定"是否允许开发默认 JWT 密钥"，判错会让生产鉴权守卫整体失效。
 *
 * <p>关键回归点：Helm Chart 的默认运行 profile 是 {@code k8s}（不是 prod），
 * 早期只认 prod/staging，导致生产 Pod 实际走的是"非生产"分支。
 */
class SecurityDefaultsTest {

    @Test
    @DisplayName("k8s profile 按生产处理（Helm 默认运行 profile）")
    void k8sProfileCountsAsProduction() {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles("k8s");

        assertThat(SecurityDefaults.isProduction(env)).isTrue();
    }

    @Test
    @DisplayName("prod / production / staging 均按生产处理")
    void prodLikeProfilesCountAsProduction() {
        for (String profile : new String[]{"prod", "production", "staging", "PROD"}) {
            MockEnvironment env = new MockEnvironment();
            env.setActiveProfiles(profile);
            assertThat(SecurityDefaults.isProduction(env))
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

        assertThat(SecurityDefaults.isProduction(env)).isFalse();
    }
}
