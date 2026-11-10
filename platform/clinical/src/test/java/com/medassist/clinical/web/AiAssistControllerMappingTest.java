package com.medassist.clinical.web;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.RequestMapping;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 第 173 批：HIS → AI 通道的"外部 / 内部"必须分开。
 *
 * <p>背景：网关 denylist 会永久拒绝 {@code /api/v2/* /internal/**} 与 {@code /internal/**}。
 * 若 HIS 工作台复用 {@code /internal/ai}，经网关一定 404；若把内部通道整体改成公开路径，
 * 服务间接口就会暴露到公网。因此保留两条映射：
 * <ul>
 *   <li>{@code /clinical/ai-assist}：网关 {@code /api/v2/clinical/**} 剥两段后可达（需 JWT）；</li>
 *   <li>{@code /internal/ai}：只给同命名空间服务调用，网关不可达。</li>
 * </ul>
 * 删掉任一条都不会有明显报错，所以用配置级测试锁住。
 */
class AiAssistControllerMappingTest {

    private static String[] mappings() {
        RequestMapping mapping = AiAssistController.class.getAnnotation(RequestMapping.class);
        assertThat(mapping).as("AiAssistController 缺少 @RequestMapping").isNotNull();
        return mapping.value();
    }

    @Test
    @DisplayName("外部通道 /clinical/ai-assist 存在（网关剥两段后命中）")
    void externalChannelExists() {
        assertThat(mappings()).contains("/clinical/ai-assist");
    }

    @Test
    @DisplayName("内部通道 /internal/ai 保留（服务间调用，网关 denylist 不可达）")
    void internalChannelKept() {
        assertThat(mappings()).contains("/internal/ai");
    }

    @Test
    @DisplayName("两条映射不得相同或互相包含（避免内部路径意外暴露）")
    void channelsAreDistinct() {
        String[] paths = mappings();
        assertThat(paths).hasSize(2).doesNotHaveDuplicates();
        assertThat(paths).noneMatch(p -> p.isBlank() || !p.startsWith("/"));
        assertThat(paths).noneMatch(p -> p.contains("internal") && p.contains("ai-assist"));
    }

    @Test
    @DisplayName("bearer 解析只接受 Bearer 方案，其它方案返回空串")
    void bearerOnlyAcceptsBearerScheme() {
        assertThat(AiAssistController.bearer("Bearer abc")).isEqualTo("abc");
        assertThat(AiAssistController.bearer("bearer  abc ")).isEqualTo("abc");
        assertThat(AiAssistController.bearer("Basic abc")).isEmpty();
        assertThat(AiAssistController.bearer(null)).isEmpty();
    }
}
