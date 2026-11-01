package com.medassist.kb.embedding;

import java.util.List;

/**
 * 向量模型端口。
 *
 * <p>为什么要抽象成端口而不是直接调 SDK：
 * <ul>
 *   <li>生产环境可能用 OpenAI、通义、本地 vLLM 等不同实现，业务代码不应感知；</li>
 *   <li>单元测试可以注入确定性实现，不必联网；</li>
 *   <li>{@link #available()} 让上层显式决定"退化到关键词检索"，而不是悄悄写入占位向量。</li>
 * </ul>
 */
public interface EmbeddingProvider {

    /** 是否已配置且可用（未配置时上层只能走关键词检索）。 */
    boolean available();

    /** 向量维度（入库前校验，避免写入不同模型混用的向量）。 */
    int dimension();

    /**
     * 批量向量化，返回顺序与入参一致，且每条都已 L2 归一化。
     *
     * @throws com.medassist.common.exception.BusinessException 未配置或上游失败
     */
    List<float[]> embed(List<String> texts);
}
