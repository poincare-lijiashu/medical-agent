package com.medassist.kb.domain;

import java.time.Instant;
import java.util.UUID;

/** 知识库领域模型（不可变 record，与数据库行一一对应）。 */
public final class KbModel {

    private KbModel() {
    }

    /** 知识空间：隔离与授权的最小单位。 */
    public record Space(UUID id, String code, String name, String description, String orgCode,
                        String visibility, String status, String createdBy, long version,
                        Instant createdAt) {

        public static final String ACTIVE = "ACTIVE";
        public static final String ARCHIVED = "ARCHIVED";
        public static final String PRIVATE = "PRIVATE";
        public static final String PUBLIC = "PUBLIC";

        public boolean active() {
            return ACTIVE.equals(status);
        }

        public boolean openToAll() {
            return PUBLIC.equals(visibility);
        }
    }

    /** 空间成员（user_id 是 auth 账号主键，不是用户名）。 */
    public record Member(UUID spaceId, UUID userId, String role, Instant createdAt) {

        public static final String OWNER = "OWNER";
        public static final String EDITOR = "EDITOR";
        public static final String VIEWER = "VIEWER";

        /** 角色权重：OWNER 3 > EDITOR 2 > VIEWER 1。 */
        public int weight() {
            return switch (role) {
                case OWNER -> 3;
                case EDITOR -> 2;
                default -> 1;
            };
        }
    }

    /**
     * 知识文档（正文原文保存，用于引用溯源）。
     *
     * @param charCount    解析后正文长度（切片偏移相对它计算）
     * @param rawCharCount 解析前原始输入长度（与 charCount 的差值即清洗掉的噪声）
     * @param parseNotes   解析告警（实际解析方式 + 清洗动作）
     * @param errorMessage 索引告警（如"仅关键词索引"），与 parseNotes 分开
     */
    public record Document(UUID id, UUID spaceId, String title, String sourceType, String sourceUri,
                           String contentHash, String status, int chunkCount, int charCount,
                           int rawCharCount, String parseNotes, String errorMessage,
                           String createdBy, long version, Instant createdAt) {

        public static final String PENDING = "PENDING";
        public static final String INDEXING = "INDEXING";
        public static final String READY = "READY";
        public static final String FAILED = "FAILED";
        public static final String ARCHIVED = "ARCHIVED";
    }

    /** 切片（char_start/char_end 相对原文，用于把命中片段精确回指原文）。 */
    public record Chunk(UUID id, UUID documentId, int seq, String content, int charStart,
                        int charEnd, String section, String terms) {
    }

    /** 索引任务。 */
    public record IngestTask(UUID id, UUID documentId, String type, String status, int attempts) {

        public static final String PENDING = "PENDING";
        public static final String RUNNING = "RUNNING";
        public static final String DONE = "DONE";
        public static final String FAILED = "FAILED";
    }

    /** 检索命中（带引用信息；snippet 即引用片段）。 */
    public record Hit(UUID chunkId, UUID documentId, String documentTitle, int seq, String content,
                      int charStart, int charEnd, double score, String matchedBy) {
    }

    /** 空间统计（文档数/切片数/已就绪数/最大更新时间之外的最小观测面）。 */
    public record SpaceStats(long documents, long readyDocuments, long chunks, long indexedChunks) {
    }
}
