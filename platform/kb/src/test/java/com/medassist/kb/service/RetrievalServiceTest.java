package com.medassist.kb.service;

import com.medassist.common.api.ErrorCode;
import com.medassist.common.exception.BusinessException;
import com.medassist.kb.config.KbProperties;
import com.medassist.kb.domain.KbModel.Hit;
import com.medassist.kb.domain.KbModel.Space;
import com.medassist.kb.embedding.EmbeddingProvider;
import com.medassist.kb.repo.KbRepository;
import com.medassist.kb.web.KbDtos.SearchReq;
import com.medassist.kb.web.KbDtos.SearchResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 检索：降级必须显式、空查询 422、topK 上限、混合模式标记。 */
class RetrievalServiceTest {

    private KbRepository repo;
    private SpaceAccessGuard guard;
    private EmbeddingProvider embedding;
    private RetrievalService service;

    private final UUID spaceId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        repo = mock(KbRepository.class);
        guard = mock(SpaceAccessGuard.class);
        embedding = mock(EmbeddingProvider.class);
        // 默认配置：rrfK=60, candidateMultiplier=4, maxTopK=50
        service = new RetrievalService(repo, guard, embedding,
                new KbProperties(null, null, null, null, null));
        when(guard.requireReadable(spaceId, userId)).thenReturn(new Space(spaceId, "kb",
                "知识库", null, null, Space.PRIVATE, Space.ACTIVE, "admin01", 0L, Instant.now()));
    }

    private Hit hit(String title, int seq) {
        return new Hit(UUID.randomUUID(), UUID.randomUUID(), title, seq, "丙泊酚禁用于对本品过敏者",
                seq * 10, seq * 10 + 15, 0.5, "");
    }

    @Test
    @DisplayName("未配置向量模型：显式降级为关键词检索，并给出可观测的原因")
    void degradesToKeywordWhenEmbeddingMissing() {
        when(embedding.available()).thenReturn(false);
        when(repo.keywordSearch(eq(spaceId), anyString(), anyInt())).thenReturn(List.of(hit("药典", 0)));

        SearchResult result = service.search(spaceId, new SearchReq("丙泊酚 禁忌", 5, null),
                "doctor01", userId);

        assertThat(result.mode()).isEqualTo("KEYWORD");
        assertThat(result.vectorUsed()).isFalse();
        assertThat(result.degraded()).isTrue();
        assertThat(result.degradedReason()).isEqualTo("EMBEDDING_UNAVAILABLE");
        assertThat(result.hits()).hasSize(1);
        assertThat(result.hits().get(0).citation()).contains("药典");
        verify(repo, never()).vectorSearch(any(), anyString(), anyInt());
        verify(repo).logQuery(eq(spaceId), eq("doctor01"), eq("丙泊酚 禁忌"), eq("KEYWORD"),
                eq(5), eq(1), eq(false), anyInt(), any());
    }

    @Test
    @DisplayName("纯标点查询 → 422，不发起全表扫描")
    void rejectsEmptyQuery() {
        assertThatThrownBy(() -> service.search(spaceId, new SearchReq("，。！", 5, null),
                "doctor01", userId))
                .isInstanceOfSatisfying(BusinessException.class, e ->
                        assertThat(e.errorCode()).isEqualTo(ErrorCode.KB_QUERY_INVALID));
        verify(repo, never()).keywordSearch(any(), anyString(), anyInt());
    }

    @Test
    @DisplayName("topK 超上限时按 maxTopK 收敛，单路召回量 = topK × 候选系数")
    void clampsTopK() {
        when(embedding.available()).thenReturn(false);
        when(repo.keywordSearch(eq(spaceId), anyString(), anyInt())).thenReturn(List.of());

        service.search(spaceId, new SearchReq("阑尾炎", 500, "KEYWORD"), "doctor01", userId);

        verify(repo).keywordSearch(eq(spaceId), anyString(), eq(200));
    }

    @Test
    @DisplayName("已配置向量但空间内没有向量：降级原因 NO_VECTORS（而不是假装跑了语义检索）")
    void degradesWhenNoVectorsIndexed() {
        when(embedding.available()).thenReturn(true);
        when(repo.countEmbeddedChunks(spaceId)).thenReturn(0L);
        when(repo.keywordSearch(eq(spaceId), anyString(), anyInt())).thenReturn(List.of());

        SearchResult result = service.search(spaceId, new SearchReq("阑尾炎", 5, null),
                "doctor01", userId);

        assertThat(result.mode()).isEqualTo("KEYWORD");
        assertThat(result.degradedReason()).isEqualTo("NO_VECTORS");
        verify(repo, never()).vectorSearch(any(), anyString(), anyInt());
    }

    @Test
    @DisplayName("向量可用时走混合模式：命中标记 HYBRID，两路结果融合")
    void hybridModeMergesBothPaths() {
        when(embedding.available()).thenReturn(true);
        when(repo.countEmbeddedChunks(spaceId)).thenReturn(3L);
        when(embedding.embed(List.of("阑尾炎"))).thenReturn(List.of(new float[]{1f, 0f}));
        Hit keywordHit = hit("药典", 0);
        Hit vectorHit = hit("指南", 1);
        when(repo.keywordSearch(eq(spaceId), anyString(), anyInt())).thenReturn(List.of(keywordHit));
        when(repo.vectorSearch(eq(spaceId), anyString(), anyInt())).thenReturn(List.of(vectorHit));

        SearchResult result = service.search(spaceId, new SearchReq("阑尾炎", 10, null),
                "doctor01", userId);

        assertThat(result.mode()).isEqualTo("HYBRID");
        assertThat(result.vectorUsed()).isTrue();
        assertThat(result.degraded()).isFalse();
        assertThat(result.hits()).hasSize(2);
        verify(repo).logQuery(eq(spaceId), eq("doctor01"), eq("阑尾炎"), eq("HYBRID"),
                eq(10), eq(2), eq(true), anyInt(), any());
    }

    @Test
    @DisplayName("向量调用失败：不整体报错，降级关键词并标记 EMBEDDING_FAILED")
    void embeddingFailureDegradesInsteadOfFailing() {
        when(embedding.available()).thenReturn(true);
        when(repo.countEmbeddedChunks(spaceId)).thenReturn(2L);
        when(embedding.embed(any())).thenThrow(new BusinessException(
                ErrorCode.KB_EMBEDDING_UNAVAILABLE, "向量模型暂不可用"));
        when(repo.keywordSearch(eq(spaceId), anyString(), anyInt())).thenReturn(List.of(hit("药典", 0)));

        SearchResult result = service.search(spaceId, new SearchReq("阑尾炎", 5, null),
                "doctor01", userId);

        assertThat(result.vectorUsed()).isFalse();
        assertThat(result.degradedReason()).isEqualTo("EMBEDDING_FAILED");
        assertThat(result.hits()).hasSize(1);
        verify(repo).logQuery(eq(spaceId), eq("doctor01"), anyString(), eq("KEYWORD"),
                anyInt(), anyInt(), anyBoolean(), anyInt(), any());
    }
}
