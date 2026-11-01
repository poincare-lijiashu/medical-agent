package com.medassist.kb.index;

import com.medassist.kb.domain.KbModel.Hit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** RRF 融合：名次而非分数、双路合并、去重、稳定排序。 */
class HybridRankerTest {

    private static Hit hit(String title, int seq, double score) {
        return new Hit(UUID.randomUUID(), UUID.randomUUID(), title, seq, "content",
                seq * 10, seq * 10 + 10, score, "");
    }

    @Test
    @DisplayName("两路都命中的块排序优先（1/61 + 1/61 高于单路 1/61）")
    void bothListsBoostRanking() {
        Hit shared = hit("A", 0, 0.9);
        Hit keywordOnly = hit("B", 1, 0.8);
        Hit vectorOnly = hit("C", 2, 0.7);

        List<Hit> merged = HybridRanker.merge(
                List.of(keywordOnly, shared),
                List.of(vectorOnly, shared), 60, 10);

        assertThat(merged).hasSize(3);
        assertThat(merged.get(0).chunkId()).isEqualTo(shared.chunkId());
        assertThat(merged.get(0).matchedBy()).isEqualTo("KEYWORD+VECTOR");
        assertThat(merged.get(0).score()).isGreaterThan(merged.get(1).score());
    }

    @Test
    @DisplayName("单路命中标记来源，前端可据此提示'仅关键词匹配'")
    void marksSinglePathSource() {
        Hit keywordOnly = hit("B", 1, 0.8);
        Hit vectorOnly = hit("C", 2, 0.7);

        List<Hit> merged = HybridRanker.merge(List.of(keywordOnly), List.of(vectorOnly), 60, 10);

        assertThat(merged).extracting(Hit::matchedBy)
                .containsExactlyInAnyOrder("KEYWORD", "VECTOR");
    }

    @Test
    @DisplayName("limit 生效，且空输入不抛异常")
    void respectsLimitAndEmptyInput() {
        List<Hit> many = List.of(hit("A", 0, 1), hit("B", 1, 1), hit("C", 2, 1));
        assertThat(HybridRanker.merge(many, List.of(), 60, 2)).hasSize(2);
        assertThat(HybridRanker.merge(List.of(), List.of(), 60, 10)).isEmpty();
        assertThat(HybridRanker.merge(null, null, 60, 10)).isEmpty();
    }
}
