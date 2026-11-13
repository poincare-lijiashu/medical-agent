package com.medassist.common.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** AI 模型提供方分类：与 Python 中台同一张域名表，fail-closed。 */
class ModelProviderPolicyTest {

    @Test
    @DisplayName("境外模型厂商 → foreign 且不允许")
    void foreignProviders() {
        for (String url : new String[]{
                "https://api.openai.com/v1",
                "https://api.anthropic.com/v1",
                "https://generativelanguage.googleapis.com/v1beta",
                "https://api.groq.com/openai/v1"}) {
            assertThat(ModelProviderPolicy.classify(url)).isEqualTo("foreign");
            assertThat(ModelProviderPolicy.isAllowed(url)).isFalse();
        }
    }

    @Test
    @DisplayName("国产模型/云厂商 → domestic 且允许")
    void domesticProviders() {
        for (String url : new String[]{
                "https://dashscope.aliyuncs.com/compatible-mode/v1",
                "https://ark.cn-beijing.volces.com/api/v3",
                "https://api.deepseek.com/v1",
                "https://open.bigmodel.cn/api/paas/v4",
                "https://api.siliconflow.cn/v1"}) {
            assertThat(ModelProviderPolicy.classify(url)).isEqualTo("domestic");
            assertThat(ModelProviderPolicy.isAllowed(url)).isTrue();
        }
    }

    @Test
    @DisplayName("回环/私网/内网短主机名 → local 且允许")
    void localProviders() {
        for (String url : new String[]{
                "http://127.0.0.1:8000/v1",
                "http://localhost:11434/v1",
                "http://10.20.30.40:8080/v1",
                "http://192.168.1.10/v1",
                "http://ollama:11434/v1"}) {
            assertThat(ModelProviderPolicy.classify(url)).isEqualTo("local");
            assertThat(ModelProviderPolicy.isAllowed(url)).isTrue();
        }
    }

    @Test
    @DisplayName("未知域名/空值 → unknown 且不允许（fail-closed）")
    void unknownProviders() {
        assertThat(ModelProviderPolicy.classify("https://llm.example.com/v1")).isEqualTo("unknown");
        assertThat(ModelProviderPolicy.classify("")).isEqualTo("unknown");
        assertThat(ModelProviderPolicy.classify(null)).isEqualTo("unknown");
        assertThat(ModelProviderPolicy.isAllowed(null)).isFalse();
    }
}
