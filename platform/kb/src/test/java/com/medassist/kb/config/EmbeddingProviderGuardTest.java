package com.medassist.kb.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 向量模型提供方守卫（评测 D-29）：境外端点在产线必须拒绝启动。 */
class EmbeddingProviderGuardTest {

    @Test
    @DisplayName("未开启向量化 → 不校验（不发请求就没有数据出境）")
    void disabledSkipsGuard() {
        MockEnvironment env = env("dev");
        env.setProperty("medassist.kb.embedding.enabled", "false");
        env.setProperty("medassist.kb.embedding.base-url", "https://api.openai.com/v1");
        assertThatCode(() -> new EmbeddingProviderGuard(env).afterPropertiesSet())
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("生产 + 境外端点 → 拒绝启动")
    void productionRejectsForeignProvider() {
        MockEnvironment env = env("prod");
        env.setProperty("medassist.kb.embedding.enabled", "true");
        env.setProperty("medassist.kb.embedding.base-url", "https://api.openai.com/v1");
        assertThatThrownBy(() -> new EmbeddingProviderGuard(env).afterPropertiesSet())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("api.openai.com")
                .hasMessageContaining("拒绝启动");
    }

    @Test
    @DisplayName("生产 + 未知端点 → 同样拒绝（fail-closed）")
    void productionRejectsUnknownProvider() {
        MockEnvironment env = env("prod");
        env.setProperty("medassist.kb.embedding.enabled", "true");
        env.setProperty("medassist.kb.embedding.base-url", "https://llm.example.com/v1");
        assertThatThrownBy(() -> new EmbeddingProviderGuard(env).afterPropertiesSet())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("unknown");
    }

    @Test
    @DisplayName("生产 + 国产端点 → 放行")
    void productionAllowsDomesticProvider() {
        MockEnvironment env = env("prod");
        env.setProperty("medassist.kb.embedding.enabled", "true");
        env.setProperty("medassist.kb.embedding.base-url",
                "https://dashscope.aliyuncs.com/compatible-mode/v1");
        assertThatCode(() -> new EmbeddingProviderGuard(env).afterPropertiesSet())
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("生产 + 境外端点但已显式放行 → 放行（合规审批通道）")
    void productionAllowsForeignWithExplicitOptIn() {
        MockEnvironment env = env("prod");
        env.setProperty("medassist.kb.embedding.enabled", "true");
        env.setProperty("medassist.kb.embedding.base-url", "https://api.openai.com/v1");
        env.setProperty(EmbeddingProviderGuard.ALLOW_FOREIGN_PROPERTY, "true");
        assertThatCode(() -> new EmbeddingProviderGuard(env).afterPropertiesSet())
                .doesNotThrowAnyException();
    }

    private MockEnvironment env(String profile) {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles(profile);
        return env;
    }
}
