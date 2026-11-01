package com.medassist.kb.index;

import com.medassist.kb.domain.KbModel.Hit;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 混合检索融合（Reciprocal Rank Fusion, RRF）。
 *
 * <p>为什么用 RRF 而不是把 ts_rank 与余弦分数加权相加：
 * 两路分数量纲完全不同（ts_rank 是 0~1 的小数，点积可能大于 1），
 * 直接加权需要按查询动态归一化，工程上很难稳定；RRF 只使用**名次**：
 * <pre>score(d) = Σ 1 / (k + rank_i(d))</pre>
 * 不依赖分数尺度，天然支持多路召回，且实现是纯函数、可单测。
 *
 * <p>纯函数、无 Spring 依赖：便于用固定数据回归排序行为。
 */
public final class HybridRanker {

    private HybridRanker() {
    }

    /**
     * 融合两路召回结果。
     *
     * @param keyword 关键词召回（已按相关度降序）
     * @param vector  向量召回（已按相似度降序）
     * @param k       RRF 平滑常数（一般 60）
     * @param limit   返回条数
     * @return 按融合分数降序的命中；同分时按文档、切片序号稳定排序
     */
    public static List<Hit> merge(List<Hit> keyword, List<Hit> vector, int k, int limit) {
        Map<UUID, Double> scores = new HashMap<>();
        Map<UUID, Hit> data = new LinkedHashMap<>();
        Set<UUID> keywordHits = new HashSet<>();
        Set<UUID> vectorHits = new HashSet<>();
        accumulate(keyword, k, scores, data, keywordHits);
        accumulate(vector, k, scores, data, vectorHits);
        return scores.entrySet().stream()
                .sorted(Map.Entry.<UUID, Double>comparingByValue().reversed()
                        .thenComparing(e -> data.get(e.getKey()).documentTitle())
                        .thenComparingInt(e -> data.get(e.getKey()).seq()))
                .limit(Math.max(1, limit))
                .map(e -> {
                    UUID id = e.getKey();
                    Hit base = data.get(id);
                    boolean kw = keywordHits.contains(id);
                    boolean vec = vectorHits.contains(id);
                    String by = kw && vec ? "KEYWORD+VECTOR" : (vec ? "VECTOR" : "KEYWORD");
                    return new Hit(base.chunkId(), base.documentId(), base.documentTitle(),
                            base.seq(), base.content(), base.charStart(), base.charEnd(),
                            round6(e.getValue()), by);
                })
                .toList();
    }

    private static void accumulate(List<Hit> hits, int k, Map<UUID, Double> scores,
                                   Map<UUID, Hit> data, Set<UUID> seen) {
        if (hits == null) {
            return;
        }
        int rank = 0;
        for (Hit hit : hits) {
            rank++;
            if (hit == null || hit.chunkId() == null) {
                continue;
            }
            data.putIfAbsent(hit.chunkId(), hit);
            seen.add(hit.chunkId());
            scores.merge(hit.chunkId(), 1.0 / (k + rank), Double::sum);
        }
    }

    private static double round6(double value) {
        return Math.round(value * 1_000_000d) / 1_000_000d;
    }
}
