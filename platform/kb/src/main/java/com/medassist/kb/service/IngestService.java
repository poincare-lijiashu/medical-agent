package com.medassist.kb.service;

import com.medassist.common.api.ErrorCode;
import com.medassist.common.exception.BusinessException;
import com.medassist.kb.config.KbProperties;
import com.medassist.kb.domain.KbModel.Chunk;
import com.medassist.kb.domain.KbModel.Document;
import com.medassist.kb.embedding.EmbeddingProvider;
import com.medassist.kb.embedding.Vectors;
import com.medassist.kb.index.TextChunker;
import com.medassist.kb.index.TextTerms;
import com.medassist.kb.repo.KbRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 文档索引：切片 → 词元 → （可选）向量 → 落库。
 *
 * <p>降级策略（重要）：
 * <ul>
 *   <li>向量模型未配置：只做关键词索引，文档状态照常 READY，但在 {@code error_message}
 *       写入"仅关键词索引"提示，运营侧可见，避免"看起来是语义检索"的误判；</li>
 *   <li>向量调用失败：同样降级为关键词索引并记录原因，**不阻塞入库**；</li>
 *   <li>切片为空：视为真失败（文档没有可用内容），任务重试到上限后置 FAILED。</li>
 * </ul>
 */
@Service
public class IngestService {

    private static final Logger log = LoggerFactory.getLogger(IngestService.class);

    private final KbRepository repo;
    private final KbProperties props;
    private final EmbeddingProvider embedding;

    public IngestService(KbRepository repo, KbProperties props, EmbeddingProvider embedding) {
        this.repo = repo;
        this.props = props;
        this.embedding = embedding;
    }

    /**
     * 处理一个索引任务（独立事务：失败只回滚该文档的索引内容）。
     *
     * @param taskId     任务 ID
     * @param documentId 文档 ID
     */
    @Transactional
    public void process(UUID taskId, UUID documentId) {
        Document document = repo.findDocument(documentId)
                .orElseThrow(() -> new BusinessException(ErrorCode.KB_DOCUMENT_NOT_FOUND));
        if (Document.ARCHIVED.equals(document.status())) {
            repo.finishTask(taskId);
            return;
        }
        repo.markIndexing(documentId);
        String content = repo.findDocumentContent(documentId);
        List<TextChunker.Piece> pieces = TextChunker.split(content,
                props.chunk().maxChars(), props.chunk().overlapChars());
        if (pieces.isEmpty()) {
            throw new BusinessException(ErrorCode.KB_DOCUMENT_STATUS_INVALID,
                    "文档没有可索引的文本内容");
        }
        List<float[]> vectors;
        String warning;
        try {
            vectors = embed(pieces);
            warning = vectors.isEmpty() ? "向量模型未配置：已按关键词索引（语义检索不可用）" : null;
        } catch (BusinessException e) {
            vectors = List.of();
            warning = "向量模型不可用（" + e.getMessage() + "）：已按关键词索引";
            log.warn("kb.ingest.embedding_degraded documentId={} reason={}", documentId, e.getMessage());
        }
        repo.deleteChunks(documentId);
        List<Chunk> chunks = new ArrayList<>(pieces.size());
        for (TextChunker.Piece piece : pieces) {
            chunks.add(new Chunk(UUID.randomUUID(), documentId, piece.seq(), piece.content(),
                    piece.start(), piece.end(), piece.section(), TextTerms.toTerms(piece.content())));
        }
        for (int i = 0; i < chunks.size(); i++) {
            Chunk chunk = chunks.get(i);
            String literal = vectors.size() > i ? Vectors.literal(vectors.get(i)) : null;
            repo.insertChunk(chunk.id(), chunk, chunk.terms(), literal);
        }
        repo.markReady(documentId, chunks.size(), content.length(), warning);
        repo.finishTask(taskId);
        log.info("kb.ingest.done documentId={} chunks={} vectors={}",
                documentId, chunks.size(), vectors.size());
    }

    private List<float[]> embed(List<TextChunker.Piece> pieces) {
        if (!embedding.available()) {
            return List.of();
        }
        List<String> texts = pieces.stream().map(TextChunker.Piece::content).toList();
        return embedding.embed(texts);
    }
}
