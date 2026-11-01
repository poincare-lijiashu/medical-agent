package com.medassist.kb.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * 知识库配置（切片 / 检索 / 索引 / 向量模型）。
 *
 * <p>全部给默认值：没有配置文件也能启动，但向量模型默认关闭——
 * 未配置时检索退化为关键词模式，绝不写入占位向量。
 *
 * @param chunk     切片参数
 * @param search    混合检索参数
 * @param ingest    异步索引参数
 * @param embedding 向量模型参数
 * @param answer    抽取式问答参数
 */
@ConfigurationProperties(prefix = "kb")
public record KbProperties(Chunk chunk, Search search, Ingest ingest, Embedding embedding,
                           Answer answer) {

    public KbProperties {
        chunk = chunk == null ? new Chunk(600, 80) : chunk;
        search = search == null ? new Search(60, 4, 50) : search;
        ingest = ingest == null ? new Ingest(true, 5000, 5, 5, 600) : ingest;
        embedding = embedding == null ? new Embedding(false, null, "", "", 1536, Duration.ofSeconds(10))
                : embedding;
        answer = answer == null ? new Answer(3, 400) : answer;
    }

    /** @param maxChars     单块最大字符数
     *  @param overlapChars 相邻块重叠字符数（保证跨块语义不丢） */
    public record Chunk(int maxChars, int overlapChars) {
        public Chunk {
            maxChars = maxChars <= 0 ? 600 : maxChars;
            overlapChars = overlapChars < 0 ? 0 : Math.min(overlapChars, maxChars / 2);
        }
    }

    /** @param rrfK                RRF 平滑常数（60 是行业默认）
     *  @param candidateMultiplier 单路召回数量 = topK × 该系数
     *  @param maxTopK             单次检索最大返回条数（防止大 topK 扫描拖垮库） */
    public record Search(int rrfK, int candidateMultiplier, int maxTopK) {
        public Search {
            rrfK = rrfK <= 0 ? 60 : rrfK;
            candidateMultiplier = candidateMultiplier <= 0 ? 4 : candidateMultiplier;
            maxTopK = maxTopK <= 0 ? 50 : maxTopK;
        }
    }

    /** @param enabled     是否启用后台索引 worker
     *  @param intervalMs  轮询间隔
     *  @param batchSize   单次抢占任务数
     *  @param maxAttempts 最大尝试次数（超过后任务置 FAILED 不再重试）
     *  @param leaseSeconds RUNNING 任务的租约时长：worker 崩溃后超过该时长可被重新抢占，
     *                      否则任务会永远停在 RUNNING、文档永远卡在 INDEXING */
    public record Ingest(boolean enabled, long intervalMs, int batchSize, int maxAttempts,
                         int leaseSeconds) {
        public Ingest {
            intervalMs = intervalMs <= 0 ? 5000 : intervalMs;
            batchSize = batchSize <= 0 ? 5 : batchSize;
            maxAttempts = maxAttempts <= 0 ? 5 : maxAttempts;
            leaseSeconds = leaseSeconds < 60 ? 600 : leaseSeconds;
        }
    }

    /** @param enabled   是否启用向量检索
     *  @param baseUrl   OpenAI 兼容 base url（含 /v1）
     *  @param apiKey    API Key（空则表示未配置）
     *  @param model     向量模型名
     *  @param dimension 向量维度（必须与模型一致，否则入库时报错）
     *  @param timeout   调用超时 */
    public record Embedding(boolean enabled, String baseUrl, String apiKey, String model,
                            int dimension, Duration timeout) {
        public Embedding {
            dimension = dimension <= 0 ? 1536 : dimension;
            timeout = timeout == null ? Duration.ofSeconds(10) : timeout;
            model = model == null || model.isBlank() ? "text-embedding-3-small" : model;
        }

        /** 真正的"可用"判定：开关打开 + Key 非空 + base url 非空。 */
        public boolean usable() {
            return enabled && apiKey != null && !apiKey.isBlank()
                    && baseUrl != null && !baseUrl.isBlank();
        }
    }

    /**
     * 抽取式问答（不接大模型：答案完全由命中原文拼接，故只需要控制"拼多少"）。
     *
     * @param maxChunks         参与拼接的片段上限（越多越完整，但也越像"复制粘贴"）
     * @param maxCharsPerChunk  单条证据最多取多少字符（按句子边界截断）
     */
    public record Answer(int maxChunks, int maxCharsPerChunk) {
        public Answer {
            maxChunks = maxChunks <= 0 ? 3 : Math.min(maxChunks, 10);
            maxCharsPerChunk = maxCharsPerChunk < 80 ? 400 : maxCharsPerChunk;
        }
    }
}
