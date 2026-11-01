package com.medassist.kb.service;

import com.medassist.kb.config.KbProperties;
import com.medassist.kb.domain.KbModel.Hit;
import com.medassist.kb.repo.KbRepository;
import com.medassist.kb.web.KbDtos.AnswerCitation;
import com.medassist.kb.web.KbDtos.AnswerReq;
import com.medassist.kb.web.KbDtos.AnswerResult;
import com.medassist.kb.web.KbDtos.SearchReq;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 抽取式问答：**只用检索到的原文回答问题**，不做任何生成。
 *
 * <p>为什么现在不做生成式问答：离线环境没有可用的大模型，任何"生成"都只能是模板拼接或
 * 凭空编造。临床场景里编造的答案比没有答案更危险，因此本批明确约定：
 * <ol>
 *   <li>答案 = 命中的原文片段按序拼接（带【序号】），{@code extractive=true}、{@code llmUsed=false}；</li>
 *   <li>没有命中 → {@code grounded=false} 拒答，并写明原因（NO_EVIDENCE / SPACE_EMPTY）；</li>
 *   <li>每条引用都带 {@code charStart/charEnd} 与原文 {@code quote}，可回指、可核对、可追责；</li>
 *   <li>截断只按句子边界，不切断半句诊断标准，避免"引用看起来像原文但其实被腰斩"。</li>
 * </ol>
 *
 * <p>等接入大模型时，只需在 {@code llmUsed} 分支里加入"以片段为唯一上下文"的生成，
 * 拒答与引用结构保持不变，前端与审计无需改动。
 */
@Service
public class AnswerService {

    /** 拒答文案：必须明确告诉用户"没有证据"，而不是给一个模糊答案。 */
    static final String REFUSAL = "未在知识库中检索到与该问题相关的原文，为避免给出无依据的结论，本系统不作回答。"
            + "请补充关键词、确认知识空间是否已收录相关资料，或联系知识库管理员。";

    private static final String REFUSAL_SPACE_EMPTY = "（该知识空间当前没有已就绪的文档）";
    private static final String SENTENCE_END = "。！？；!?;\n";

    private final RetrievalService retrieval;
    private final KbRepository repo;
    private final KbProperties props;

    public AnswerService(RetrievalService retrieval, KbRepository repo, KbProperties props) {
        this.retrieval = retrieval;
        this.repo = repo;
        this.props = props;
    }

    /**
     * 回答一个问题。
     *
     * @param actor  操作者（审计用）
     * @param userId 当前登录账号主键（空间成员校验用，缺失即拒绝）
     */
    public AnswerResult answer(UUID spaceId, AnswerReq req, String actor, UUID userId) {
        long started = System.nanoTime();
        int maxChunks = props.answer().maxChunks();
        int requested = req.topK() == null || req.topK() <= 0 ? maxChunks : req.topK();
        int topK = Math.min(Math.max(requested, 1), maxChunks);

        RetrievalService.RetrievalOutcome outcome = retrieval.retrieve(spaceId,
                new SearchReq(req.question(), topK, "HYBRID"), userId);

        List<AnswerCitation> citations = new ArrayList<>(outcome.hits().size());
        StringBuilder answer = new StringBuilder();
        for (Hit hit : outcome.hits()) {
            String quote = excerpt(hit.content(), props.answer().maxCharsPerChunk());
            if (quote.isBlank()) {
                continue;
            }
            if (!answer.isEmpty()) {
                answer.append("\n\n");
            }
            answer.append("【").append(citations.size() + 1).append("】").append(quote);
            citations.add(new AnswerCitation(hit.chunkId(), hit.documentId(), hit.documentTitle(),
                    hit.seq(), hit.charStart(), hit.charEnd(), quote, hit.score(),
                    hit.documentTitle() + " 第 " + (hit.seq() + 1) + " 段"));
        }

        int latencyMs = elapsed(started);
        if (citations.isEmpty()) {
            String reason = repo.stats(spaceId).readyDocuments() == 0 ? "SPACE_EMPTY" : "NO_EVIDENCE";
            repo.logAnswer(spaceId, actor, req.question(), false, reason, 0, latencyMs, null);
            return new AnswerResult(false,
                    REFUSAL + ("SPACE_EMPTY".equals(reason) ? REFUSAL_SPACE_EMPTY : ""),
                    List.of(), true, false, reason, outcome.mode(), outcome.degraded(),
                    outcome.degradedReason(), latencyMs);
        }

        repo.logAnswer(spaceId, actor, req.question(), true, null, citations.size(), latencyMs,
                outcome.topScore());
        return new AnswerResult(true, answer.toString(), List.copyOf(citations), true, false,
                null, outcome.mode(), outcome.degraded(), outcome.degradedReason(), latencyMs);
    }

    /**
     * 按句子边界截断：优先在 [max/2, max] 内最后一个句末标点处截断，找不到才硬截。
     * 返回的片段一定是原文的**前缀**（不做任何改写）。
     */
    static String excerpt(String content, int maxChars) {
        String text = content == null ? "" : content.strip();
        if (text.length() <= maxChars) {
            return text;
        }
        int floor = Math.max(1, maxChars / 2);
        for (int i = maxChars; i > floor; i--) {
            if (SENTENCE_END.indexOf(text.charAt(i - 1)) >= 0) {
                return text.substring(0, i).strip();
            }
        }
        return text.substring(0, maxChars).strip() + "…";
    }

    private static int elapsed(long startedNanos) {
        return (int) ((System.nanoTime() - startedNanos) / 1_000_000L);
    }
}
