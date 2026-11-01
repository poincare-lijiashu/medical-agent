package com.medassist.kb.service;

import com.medassist.common.api.ErrorCode;
import com.medassist.common.exception.BusinessException;
import com.medassist.kb.config.KbProperties;
import com.medassist.kb.domain.KbModel.Hit;
import com.medassist.kb.embedding.EmbeddingProvider;
import com.medassist.kb.embedding.Vectors;
import com.medassist.kb.index.HybridRanker;
import com.medassist.kb.index.TextTerms;
import com.medassist.kb.repo.KbRepository;
import com.medassist.kb.web.KbDtos.SearchHit;
import com.medassist.kb.web.KbDtos.SearchReq;
import com.medassist.kb.web.KbDtos.SearchResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

/**
 * 企业检索：关键词 + 向量双路召回，RRF 融合，带引用溯源。
 *
 * <p>降级是**显式的**：没有配置向量模型、空间内没有向量、模型调用失败，
 * 都会在响应里返回 {@code degraded=true} 与原因，而不是假装"语义检索正常"。
 *
 * <p>每次检索写一条 {@code kb_query_log}（命中数/是否用向量/时延/top 分数），
 * 这些指标就是后续做召回率回归与容量评估的数据基础。
 */
@Service
public class RetrievalService {

    private static final Logger log = LoggerFactory.getLogger(RetrievalService.class);

    /** 引用片段最大长度（前端展示与人工核对够用，不整段回传）。 */
    private static final int SNIPPET_CHARS = 240;

    private final KbRepository repo;
    private final SpaceAccessGuard guard;
    private final EmbeddingProvider embedding;
    private final KbProperties props;

    public RetrievalService(KbRepository repo, SpaceAccessGuard guard, EmbeddingProvider embedding,
                            KbProperties props) {
        this.repo = repo;
        this.guard = guard;
        this.embedding = embedding;
        this.props = props;
    }

    public SearchResult search(UUID spaceId, SearchReq req, String actor, UUID userId) {
        guard.requireReadable(spaceId, userId);
        String tsQuery = TextTerms.toTsQuery(req.query());
        if (tsQuery.isBlank()) {
            throw new BusinessException(ErrorCode.KB_QUERY_INVALID);
        }
        int topK = req.topK() == null || req.topK() <= 0
                ? 10 : Math.min(req.topK(), props.search().maxTopK());
        int candidates = Math.max(topK, topK * props.search().candidateMultiplier());
        long started = System.nanoTime();

        List<Hit> keywordHits = repo.keywordSearch(spaceId, tsQuery, candidates);

        List<Hit> vectorHits = List.of();
        boolean vectorUsed = false;
        boolean degraded = false;
        String degradedReason = null;
        String requestedMode = req.mode() == null ? "HYBRID" : req.mode();

        if (!"KEYWORD".equals(requestedMode)) {
            if (!embedding.available()) {
                degraded = true;
                degradedReason = "EMBEDDING_UNAVAILABLE";
            } else if (repo.countEmbeddedChunks(spaceId) == 0) {
                degraded = true;
                degradedReason = "NO_VECTORS";
            } else {
                try {
                    float[] vector = embedding.embed(List.of(req.query())).get(0);
                    vectorHits = repo.vectorSearch(spaceId, Vectors.literal(vector), candidates);
                    vectorUsed = !vectorHits.isEmpty();
                    if (!vectorUsed) {
                        degraded = true;
                        degradedReason = "NO_VECTORS";
                    }
                } catch (BusinessException e) {
                    degraded = true;
                    degradedReason = "EMBEDDING_FAILED";
                    log.warn("kb.search.embedding_degraded spaceId={} err={}", spaceId, e.getMessage());
                }
            }
        }

        List<Hit> merged = HybridRanker.merge(keywordHits, vectorHits,
                props.search().rrfK(), topK);
        int latencyMs = (int) ((System.nanoTime() - started) / 1_000_000L);
        String effectiveMode = vectorUsed ? "HYBRID" : "KEYWORD";
        Double topScore = merged.isEmpty() ? null : merged.get(0).score();

        repo.logQuery(spaceId, actor, req.query(), effectiveMode, topK, merged.size(),
                vectorUsed, latencyMs, topScore);

        List<SearchHit> hits = merged.stream().map(RetrievalService::toView).toList();
        return new SearchResult(effectiveMode, vectorUsed, degraded && !vectorUsed,
                (degraded && !vectorUsed) ? degradedReason : null, latencyMs, hits.size(), hits);
    }

    private static SearchHit toView(Hit hit) {
        String snippet = hit.content().length() <= SNIPPET_CHARS
                ? hit.content()
                : hit.content().substring(0, SNIPPET_CHARS) + "…";
        String citation = hit.documentTitle() + " 第 " + (hit.seq() + 1) + " 段";
        return new SearchHit(hit.chunkId(), hit.documentId(), hit.documentTitle(), hit.seq(),
                hit.charStart(), hit.charEnd(), snippet, hit.score(), hit.matchedBy(), citation);
    }
}
