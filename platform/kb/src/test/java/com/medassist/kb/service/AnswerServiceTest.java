package com.medassist.kb.service;

import com.medassist.kb.config.KbProperties;
import com.medassist.kb.domain.KbModel.Hit;
import com.medassist.kb.domain.KbModel.SpaceStats;
import com.medassist.kb.repo.KbRepository;
import com.medassist.kb.web.KbDtos.AnswerReq;
import com.medassist.kb.web.KbDtos.AnswerResult;
import com.medassist.kb.web.KbDtos.SearchReq;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 抽取式问答：有证据才回答、无证据拒答、引用可回指、topK 受配置约束。 */
class AnswerServiceTest {

    private RetrievalService retrieval;
    private KbRepository repo;
    private AnswerService service;

    private final UUID spaceId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        retrieval = mock(RetrievalService.class);
        repo = mock(KbRepository.class);
        // 默认 answer.maxChunks=3, maxCharsPerChunk=400
        service = new AnswerService(retrieval, repo, new KbProperties(null, null, null, null, null));
    }

    private Hit hit(String title, int seq, String content) {
        return new Hit(UUID.randomUUID(), UUID.randomUUID(), title, seq, content,
                seq * 100, seq * 100 + content.length(), 0.032, "KEYWORD");
    }

    private void outcome(List<Hit> hits) {
        when(retrieval.retrieve(eq(spaceId), any(SearchReq.class), eq(userId)))
                .thenReturn(new RetrievalService.RetrievalOutcome("KEYWORD", false, true,
                        "EMBEDDING_UNAVAILABLE", 4, 3, hits));
    }

    @Test
    @DisplayName("有证据：答案由原文片段拼接并带【序号】，extractive=true / llmUsed=false")
    void answersWithCitations() {
        outcome(List.of(hit("急性阑尾炎指南", 0, "急性阑尾炎首选手术切除。"),
                hit("抗菌药物规范", 2, "围手术期预防用药不超过 24 小时。")));

        AnswerResult result = service.answer(spaceId, new AnswerReq("阑尾炎怎么治", 5),
                "doctor01", userId);

        assertThat(result.grounded()).isTrue();
        assertThat(result.answer()).contains("【1】急性阑尾炎首选手术切除").contains("【2】围手术期");
        assertThat(result.citations()).hasSize(2);
        assertThat(result.citations().get(0).quote()).isEqualTo("急性阑尾炎首选手术切除。");
        assertThat(result.citations().get(0).citation()).contains("急性阑尾炎指南").contains("第 1 段");
        assertThat(result.citations().get(1).charStart()).isEqualTo(200);
        assertThat(result.extractive()).isTrue();
        assertThat(result.llmUsed()).isFalse();
        assertThat(result.refusedReason()).isNull();
        assertThat(result.degraded()).isTrue();
        assertThat(result.degradedReason()).isEqualTo("EMBEDDING_UNAVAILABLE");
        verify(repo).logAnswer(eq(spaceId), eq("doctor01"), eq("阑尾炎怎么治"), eq(true),
                isNull(), eq(2), anyInt(), any());
    }

    @Test
    @DisplayName("无命中且空间为空：拒答并标记 SPACE_EMPTY，不产出任何答案文本")
    void refusesWhenSpaceEmpty() {
        outcome(List.of());
        when(repo.stats(spaceId)).thenReturn(new SpaceStats(0, 0, 0, 0));

        AnswerResult result = service.answer(spaceId, new AnswerReq("阑尾炎怎么治", 3),
                "doctor01", userId);

        assertThat(result.grounded()).isFalse();
        assertThat(result.refusedReason()).isEqualTo("SPACE_EMPTY");
        assertThat(result.answer()).isEqualTo(AnswerService.REFUSAL + "（该知识空间当前没有已就绪的文档）");
        assertThat(result.citations()).isEmpty();
        verify(repo).logAnswer(eq(spaceId), eq("doctor01"), anyString(), eq(false),
                eq("SPACE_EMPTY"), eq(0), anyInt(), isNull());
    }

    @Test
    @DisplayName("无命中但空间有文档：拒答原因 NO_EVIDENCE（不是空库，是问的东西没收录）")
    void refusesWhenNoEvidence() {
        outcome(List.of());
        when(repo.stats(spaceId)).thenReturn(new SpaceStats(5, 5, 20, 0));

        AnswerResult result = service.answer(spaceId, new AnswerReq("罕见病 X 的治疗", 3),
                "doctor01", userId);

        assertThat(result.grounded()).isFalse();
        assertThat(result.refusedReason()).isEqualTo("NO_EVIDENCE");
        assertThat(result.answer()).isEqualTo(AnswerService.REFUSAL);
        verify(repo).logAnswer(eq(spaceId), eq("doctor01"), anyString(), eq(false),
                eq("NO_EVIDENCE"), eq(0), anyInt(), isNull());
    }

    @Test
    @DisplayName("topK 受 answer.maxChunks 约束：传 50 也只取 3 条证据（防止答案变成整库复制）")
    void clampsTopKToMaxChunks() {
        outcome(List.of(hit("指南", 0, "正文。")));

        service.answer(spaceId, new AnswerReq("问题", 50), "doctor01", userId);

        ArgumentCaptor<SearchReq> captor = ArgumentCaptor.forClass(SearchReq.class);
        verify(retrieval).retrieve(eq(spaceId), captor.capture(), eq(userId));
        assertThat(captor.getValue().topK()).isEqualTo(3);
        assertThat(captor.getValue().mode()).isEqualTo("HYBRID");
    }

    @Test
    @DisplayName("引用片段按句子边界截断：保留完整句子，不腰斩诊断标准")
    void excerptCutsAtSentenceBoundary() {
        String longText = "甲".repeat(60) + "。" + "乙".repeat(300);

        String quote = AnswerService.excerpt(longText, 100);

        assertThat(quote).hasSize(61);
        assertThat(quote).endsWith("。");
    }

    @Test
    @DisplayName("没有任何句末标点时才硬截断并加省略号（长度绝不超限）")
    void excerptHardCutsWithEllipsis() {
        String quote = AnswerService.excerpt("丙".repeat(500), 100);

        assertThat(quote).hasSize(101);
        assertThat(quote).endsWith("…");
    }

    @Test
    @DisplayName("空指纹/空文档：不调用审计以外的任何写操作")
    void blankQuestionStillGoesToRetrievalForValidation() {
        when(retrieval.retrieve(eq(spaceId), any(SearchReq.class), eq(userId)))
                .thenThrow(new com.medassist.common.exception.BusinessException(
                        com.medassist.common.api.ErrorCode.KB_QUERY_INVALID));

        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                        service.answer(spaceId, new AnswerReq("，。", 3), "doctor01", userId))
                .isInstanceOf(com.medassist.common.exception.BusinessException.class);
        verify(repo, never()).logAnswer(any(), anyString(), anyString(), anyBoolean(),
                any(), anyInt(), anyInt(), any());
    }
}
