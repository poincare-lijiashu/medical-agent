package com.medassist.kb.repo;

import com.medassist.kb.domain.KbModel.Chunk;
import com.medassist.kb.domain.KbModel.Document;
import com.medassist.kb.domain.KbModel.Hit;
import com.medassist.kb.domain.KbModel.IngestTask;
import com.medassist.kb.domain.KbModel.Member;
import com.medassist.kb.domain.KbModel.Space;
import com.medassist.kb.domain.KbModel.SpaceStats;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 知识库仓储（JdbcClient 显式 SQL）。
 *
 * <p>关键约束：
 * <ul>
 *   <li>所有读取都必须带 {@code space_id}：知识隔离靠"查询形状"保证，而不是靠调用方自觉；</li>
 *   <li>状态迁移一律 CAS（{@code AND version = :version} 或 {@code AND status = '...'}）；</li>
 *   <li>索引任务用 {@code FOR UPDATE SKIP LOCKED} 抢占，多副本 worker 不重复处理同一文档。</li>
 * </ul>
 */
@Repository
public class KbRepository {

    private static final RowMapper<Space> SPACE_MAPPER = (rs, i) -> new Space(
            rs.getObject("id", UUID.class), rs.getString("code"), rs.getString("name"),
            rs.getString("description"), rs.getString("org_code"), rs.getString("visibility"),
            rs.getString("status"), rs.getString("created_by"), rs.getLong("version"),
            instant(rs, "created_at"));

    private static final RowMapper<Member> MEMBER_MAPPER = (rs, i) -> new Member(
            rs.getObject("space_id", UUID.class), rs.getObject("user_id", UUID.class),
            rs.getString("role"), instant(rs, "created_at"));

    private static final RowMapper<Document> DOCUMENT_MAPPER = (rs, i) -> new Document(
            rs.getObject("id", UUID.class), rs.getObject("space_id", UUID.class),
            rs.getString("title"), rs.getString("source_type"), rs.getString("source_uri"),
            rs.getString("content_hash"), rs.getString("status"), rs.getInt("chunk_count"),
            rs.getInt("char_count"), rs.getInt("raw_char_count"), rs.getString("parse_notes"),
            rs.getString("error_message"), rs.getString("created_by"),
            rs.getLong("version"), instant(rs, "created_at"));

    private static final RowMapper<IngestTask> TASK_MAPPER = (rs, i) -> new IngestTask(
            rs.getObject("id", UUID.class), rs.getObject("document_id", UUID.class),
            rs.getString("type"), rs.getString("status"), rs.getInt("attempts"));

    private static final RowMapper<Hit> HIT_MAPPER = (rs, i) -> new Hit(
            rs.getObject("id", UUID.class), rs.getObject("document_id", UUID.class),
            rs.getString("title"), rs.getInt("seq"), rs.getString("content"),
            rs.getInt("char_start"), rs.getInt("char_end"), rs.getDouble("score"), "");

    private final JdbcClient jdbc;

    public KbRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    // ---------------------------------------------------------------- 知识空间

    public void insertSpace(Space space) {
        jdbc.sql("""
                        INSERT INTO kb_svc.kb_space
                            (id, code, name, description, org_code, visibility, status,
                             created_by, version)
                        VALUES (:id, :code, :name, :description, :orgCode, :visibility, :status,
                                :createdBy, 0)
                        """)
                .param("id", space.id()).param("code", space.code()).param("name", space.name())
                .param("description", space.description()).param("orgCode", space.orgCode())
                .param("visibility", space.visibility()).param("status", space.status())
                .param("createdBy", space.createdBy()).update();
    }

    public Optional<Space> findSpace(UUID id) {
        return jdbc.sql("SELECT * FROM kb_svc.kb_space WHERE id = ?")
                .param(id).query(SPACE_MAPPER).optional();
    }

    public Optional<Space> findSpaceByCode(String code) {
        return jdbc.sql("SELECT * FROM kb_svc.kb_space WHERE code = ?")
                .param(code).query(SPACE_MAPPER).optional();
    }

    /** 我参与的空间（含自己创建的）。 */
    public List<Space> listSpacesForUser(UUID userId, int limit) {
        return jdbc.sql("""
                        SELECT s.* FROM kb_svc.kb_space s
                          JOIN kb_svc.kb_space_member m ON m.space_id = s.id
                         WHERE m.user_id = :userId
                         ORDER BY s.created_at DESC
                         LIMIT :limit
                        """)
                .param("userId", userId).param("limit", limit).query(SPACE_MAPPER).list();
    }

    /** 公开空间（只读发现用）。 */
    public List<Space> listPublicSpaces(int limit) {
        return jdbc.sql("""
                        SELECT * FROM kb_svc.kb_space
                         WHERE visibility = 'PUBLIC' AND status = 'ACTIVE'
                         ORDER BY created_at DESC LIMIT :limit
                        """)
                .param("limit", limit).query(SPACE_MAPPER).list();
    }

    /** 归档空间（CAS）。 */
    public boolean archiveSpace(UUID id, long expectedVersion) {
        return jdbc.sql("""
                        UPDATE kb_svc.kb_space
                           SET status = 'ARCHIVED', version = version + 1, updated_at = now()
                         WHERE id = :id AND status = 'ACTIVE' AND version = :version
                        """)
                .param("id", id).param("version", expectedVersion).update() > 0;
    }

    // ---------------------------------------------------------------- 成员

    public void insertMember(UUID spaceId, UUID userId, String role) {
        jdbc.sql("""
                        INSERT INTO kb_svc.kb_space_member (space_id, user_id, role)
                        VALUES (:spaceId, :userId, :role)
                        ON CONFLICT (space_id, user_id) DO NOTHING
                        """)
                .param("spaceId", spaceId).param("userId", userId).param("role", role).update();
    }

    public Optional<Member> findMember(UUID spaceId, UUID userId) {
        return jdbc.sql("SELECT * FROM kb_svc.kb_space_member WHERE space_id = ? AND user_id = ?")
                .param(spaceId).param(userId).query(MEMBER_MAPPER).optional();
    }

    public List<Member> listMembers(UUID spaceId) {
        return jdbc.sql("""
                        SELECT * FROM kb_svc.kb_space_member
                         WHERE space_id = ? ORDER BY role, created_at
                        """).param(spaceId).query(MEMBER_MAPPER).list();
    }

    // ---------------------------------------------------------------- 文档

    public void insertDocument(Document d, String content) {
        jdbc.sql("""
                        INSERT INTO kb_svc.kb_document
                            (id, space_id, title, source_type, source_uri, content, content_hash,
                             status, chunk_count, char_count, raw_char_count, parse_notes,
                             created_by, version)
                        VALUES (:id, :spaceId, :title, :sourceType, :sourceUri, :content, :hash,
                                :status, 0, :charCount, :rawCharCount, :parseNotes, :createdBy, 0)
                        """)
                .param("id", d.id()).param("spaceId", d.spaceId()).param("title", d.title())
                .param("sourceType", d.sourceType()).param("sourceUri", d.sourceUri())
                .param("content", content).param("hash", d.contentHash())
                .param("status", d.status()).param("charCount", d.charCount())
                .param("rawCharCount", d.rawCharCount())
                .param("parseNotes", truncate(d.parseNotes(), 500))
                .param("createdBy", d.createdBy()).update();
    }

    public Optional<Document> findDocument(UUID id) {
        return jdbc.sql("SELECT * FROM kb_svc.kb_document WHERE id = ?")
                .param(id).query(DOCUMENT_MAPPER).optional();
    }

    public Optional<Document> findActiveByHash(UUID spaceId, String contentHash) {
        return jdbc.sql("""
                        SELECT * FROM kb_svc.kb_document
                         WHERE space_id = :spaceId AND content_hash = :hash
                           AND status <> 'ARCHIVED'
                        """)
                .param("spaceId", spaceId).param("hash", contentHash)
                .query(DOCUMENT_MAPPER).optional();
    }

    public String findDocumentContent(UUID id) {
        return jdbc.sql("SELECT content FROM kb_svc.kb_document WHERE id = ?")
                .param(id).query(String.class).optional().orElse("");
    }

    public List<Document> listDocuments(UUID spaceId, String status, int limit) {
        return jdbc.sql("""
                        SELECT * FROM kb_svc.kb_document
                         WHERE space_id = :spaceId
                           AND (CAST(:status AS text) IS NULL OR status = CAST(:status AS text))
                         ORDER BY created_at DESC LIMIT :limit
                        """)
                .param("spaceId", spaceId).param("status", status).param("limit", limit)
                .query(DOCUMENT_MAPPER).list();
    }

    public boolean archiveDocument(UUID id, long expectedVersion) {
        return jdbc.sql("""
                        UPDATE kb_svc.kb_document
                           SET status = 'ARCHIVED', version = version + 1, updated_at = now()
                         WHERE id = :id AND status <> 'ARCHIVED' AND version = :version
                        """)
                .param("id", id).param("version", expectedVersion).update() > 0;
    }

    public boolean markIndexing(UUID id) {
        return jdbc.sql("""
                        UPDATE kb_svc.kb_document
                           SET status = 'INDEXING', error_message = NULL,
                               version = version + 1, updated_at = now()
                         WHERE id = :id AND status IN ('PENDING', 'FAILED')
                        """).param("id", id).update() > 0;
    }

    public boolean markReady(UUID id, int chunkCount, int charCount, String warning) {
        return jdbc.sql("""
                        UPDATE kb_svc.kb_document
                           SET status = 'READY', chunk_count = :chunkCount, char_count = :charCount,
                               error_message = :warning, version = version + 1, updated_at = now()
                         WHERE id = :id AND status IN ('PENDING', 'INDEXING', 'FAILED', 'READY')
                        """)
                .param("chunkCount", chunkCount).param("charCount", charCount)
                .param("warning", truncate(warning, 500))
                .param("id", id).update() > 0;
    }

    public void markFailed(UUID id, String error) {
        jdbc.sql("""
                        UPDATE kb_svc.kb_document
                           SET status = 'FAILED', error_message = :error,
                               version = version + 1, updated_at = now()
                         WHERE id = :id AND status <> 'ARCHIVED'
                        """)
                .param("error", error == null ? "索引失败" : truncate(error, 500))
                .param("id", id).update();
    }

    // ---------------------------------------------------------------- 切片

    public void deleteChunks(UUID documentId) {
        jdbc.sql("DELETE FROM kb_svc.kb_chunk WHERE document_id = ?")
                .param(documentId).update();
    }

    public void insertChunk(UUID id, Chunk chunk, String terms, String embeddingLiteral) {
        jdbc.sql("""
                        INSERT INTO kb_svc.kb_chunk
                            (id, document_id, seq, content, char_start, char_end, section, terms,
                             embedding)
                        VALUES (:id, :documentId, :seq, :content, :charStart, :charEnd, :section,
                                :terms, CAST(:embedding AS real[]))
                        """)
                .param("id", id).param("documentId", chunk.documentId()).param("seq", chunk.seq())
                .param("content", chunk.content()).param("charStart", chunk.charStart())
                .param("charEnd", chunk.charEnd()).param("section", chunk.section())
                .param("terms", terms).param("embedding", embeddingLiteral).update();
    }

    public long countEmbeddedChunks(UUID spaceId) {
        Long value = jdbc.sql("""
                        SELECT count(*) FROM kb_svc.kb_chunk c
                          JOIN kb_svc.kb_document d ON d.id = c.document_id
                         WHERE d.space_id = ? AND c.embedding IS NOT NULL
                        """).param(spaceId).query(Long.class).single();
        return value == null ? 0 : value;
    }

    // ---------------------------------------------------------------- 索引任务

    public void insertTask(UUID id, UUID documentId, String type) {
        jdbc.sql("""
                        INSERT INTO kb_svc.kb_ingest_task (id, document_id, type, status)
                        VALUES (:id, :documentId, :type, 'PENDING')
                        ON CONFLICT DO NOTHING
                        """)
                .param("id", id).param("documentId", documentId).param("type", type).update();
    }

    /** 是否已有排队/执行中的索引任务。 */
    public boolean hasActiveTask(UUID documentId) {
        Boolean exists = jdbc.sql("""
                        SELECT EXISTS (
                            SELECT 1 FROM kb_svc.kb_ingest_task
                             WHERE document_id = ? AND status IN ('PENDING', 'RUNNING'))
                        """).param(documentId).query(Boolean.class).single();
        return Boolean.TRUE.equals(exists);
    }

    /** 原子抢占（多副本安全）。 */
    public List<IngestTask> claimTasks(int limit, int maxAttempts) {
        return jdbc.sql("""
                        UPDATE kb_svc.kb_ingest_task t
                           SET status = 'RUNNING', attempts = t.attempts + 1, updated_at = now()
                         WHERE t.id IN (
                             SELECT id FROM kb_svc.kb_ingest_task
                              WHERE status IN ('PENDING', 'FAILED')
                                AND next_run_at <= now()
                                AND attempts < :maxAttempts
                              ORDER BY created_at
                              FOR UPDATE SKIP LOCKED
                              LIMIT :limit)
                        RETURNING id, document_id, type, status, attempts
                        """)
                .param("maxAttempts", maxAttempts).param("limit", limit)
                .query(TASK_MAPPER).list();
    }

    public void finishTask(UUID id) {
        jdbc.sql("""
                        UPDATE kb_svc.kb_ingest_task
                           SET status = 'DONE', last_error = NULL, updated_at = now()
                         WHERE id = ?
                        """).param(id).update();
    }

    /** 失败退避：30s × 已尝试次数（worker 只抢占 attempts < maxAttempts 的任务）。 */
    public void failTask(UUID id, String error, int attempts) {
        jdbc.sql("""
                        UPDATE kb_svc.kb_ingest_task
                           SET status = 'FAILED', last_error = :error,
                               next_run_at = now() + (interval '30 seconds' * :backoff),
                               updated_at = now()
                         WHERE id = :id
                        """)
                .param("error", truncate(error, 500)).param("backoff", Math.max(1, attempts))
                .param("id", id).update();
    }

    // ---------------------------------------------------------------- 检索

    public List<Hit> keywordSearch(UUID spaceId, String tsQuery, int limit) {
        return jdbc.sql("""
                        SELECT c.id, c.document_id, d.title, c.seq, c.content,
                               c.char_start, c.char_end,
                               ts_rank(to_tsvector('simple', c.terms),
                                       to_tsquery('simple', CAST(:tsQuery AS text))) AS score
                          FROM kb_svc.kb_chunk c
                          JOIN kb_svc.kb_document d ON d.id = c.document_id
                         WHERE d.space_id = :spaceId
                           AND d.status = 'READY'
                           AND to_tsvector('simple', c.terms)
                               @@ to_tsquery('simple', CAST(:tsQuery AS text))
                         ORDER BY score DESC
                         LIMIT :limit
                        """)
                .param("spaceId", spaceId).param("tsQuery", tsQuery).param("limit", limit)
                .query(HIT_MAPPER).list();
    }

    /**
     * 向量召回：embedding 已 L2 归一化，余弦相似度 = 点积。
     *
     * <p>未装 pgvector 也能用；数据量大后应迁移到 pgvector + HNSW（见 HANDOFF 遗留项）。
     */
    public List<Hit> vectorSearch(UUID spaceId, String vectorLiteral, int limit) {
        return jdbc.sql("""
                        SELECT c.id, c.document_id, d.title, c.seq, c.content,
                               c.char_start, c.char_end,
                               COALESCE((SELECT SUM(x.v * y.v)
                                           FROM unnest(c.embedding) WITH ORDINALITY AS x(v, i)
                                           JOIN unnest(CAST(:vector AS real[])) WITH ORDINALITY AS y(v, i)
                                             ON x.i = y.i), 0) AS score
                          FROM kb_svc.kb_chunk c
                          JOIN kb_svc.kb_document d ON d.id = c.document_id
                         WHERE d.space_id = :spaceId
                           AND d.status = 'READY'
                           AND c.embedding IS NOT NULL
                         ORDER BY score DESC
                         LIMIT :limit
                        """)
                .param("spaceId", spaceId).param("vector", vectorLiteral).param("limit", limit)
                .query(HIT_MAPPER).list();
    }

    public SpaceStats stats(UUID spaceId) {
        return jdbc.sql("""
                        SELECT
                          (SELECT count(*) FROM kb_svc.kb_document
                            WHERE space_id = :spaceId AND status <> 'ARCHIVED') AS documents,
                          (SELECT count(*) FROM kb_svc.kb_document
                            WHERE space_id = :spaceId AND status = 'READY') AS ready_documents,
                          (SELECT count(*) FROM kb_svc.kb_chunk c
                             JOIN kb_svc.kb_document d ON d.id = c.document_id
                            WHERE d.space_id = :spaceId) AS chunks,
                          (SELECT count(*) FROM kb_svc.kb_chunk c
                             JOIN kb_svc.kb_document d ON d.id = c.document_id
                            WHERE d.space_id = :spaceId AND c.embedding IS NOT NULL) AS indexed_chunks
                        """)
                .param("spaceId", spaceId)
                .query((rs, i) -> new SpaceStats(rs.getLong("documents"),
                        rs.getLong("ready_documents"), rs.getLong("chunks"),
                        rs.getLong("indexed_chunks"))).single();
    }

    // ---------------------------------------------------------------- 审计

    public void logQuery(UUID spaceId, String actor, String query, String mode, int topK,
                         int hitCount, boolean vectorUsed, int latencyMs, Double topScore) {
        jdbc.sql("""
                        INSERT INTO kb_svc.kb_query_log
                            (space_id, actor, query_text, mode, top_k, hit_count, vector_used,
                             latency_ms, top_score)
                        VALUES (:spaceId, :actor, :query, :mode, :topK, :hitCount, :vectorUsed,
                                :latencyMs, CAST(:topScore AS numeric))
                        """)
                .param("spaceId", spaceId).param("actor", actor).param("query", truncate(query, 500))
                .param("mode", mode).param("topK", topK).param("hitCount", hitCount)
                .param("vectorUsed", vectorUsed).param("latencyMs", latencyMs)
                .param("topScore", topScore).update();
    }

    /**
     * 抽取式回答审计：无论是否命中都记录，拒答率与问答时延都从这里取数。
     *
     * @param refusedReason 仅 grounded=false 时有值（NO_EVIDENCE / SPACE_EMPTY）
     */
    public void logAnswer(UUID spaceId, String actor, String question, boolean grounded,
                          String refusedReason, int citedCount, int latencyMs, Double topScore) {
        jdbc.sql("""
                        INSERT INTO kb_svc.kb_answer_log
                            (space_id, actor, question, grounded, refused_reason, cited_count,
                             latency_ms, top_score)
                        VALUES (:spaceId, :actor, :question, :grounded, :refusedReason, :citedCount,
                                :latencyMs, CAST(:topScore AS numeric))
                        """)
                .param("spaceId", spaceId).param("actor", actor)
                .param("question", truncate(question, 500)).param("grounded", grounded)
                .param("refusedReason", refusedReason).param("citedCount", citedCount)
                .param("latencyMs", latencyMs).param("topScore", topScore).update();
    }

    public void logChange(String actor, String action, String targetType, String targetId,
                          String detailJson) {
        jdbc.sql("""
                        INSERT INTO kb_svc.kb_change_log (actor, action, target_type, target_id, detail)
                        VALUES (:actor, :action, :targetType, :targetId, CAST(:detail AS jsonb))
                        """)
                .param("actor", actor).param("action", action).param("targetType", targetType)
                .param("targetId", targetId)
                .param("detail", detailJson == null ? "{}" : detailJson).update();
    }

    // ---------------------------------------------------------------- 内部工具

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp ts = rs.getTimestamp(column);
        return ts == null ? null : ts.toInstant();
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }
}
