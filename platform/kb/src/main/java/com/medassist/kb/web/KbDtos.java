package com.medassist.kb.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.UUID;

/** 知识库请求/响应体。 */
public final class KbDtos {

    private KbDtos() {
    }

    /** 新建知识空间。{@code code} 用于路由与权限点拼接，因此限制字符集。 */
    public record CreateSpaceReq(
            @NotBlank @Pattern(regexp = "[A-Za-z0-9_-]{2,40}", message = "编码只能是字母/数字/下划线/中划线")
            String code,
            @NotBlank @Size(max = 120) String name,
            @Size(max = 500) String description,
            @Size(max = 40) String orgCode,
            @Pattern(regexp = "PRIVATE|ORG|PUBLIC", message = "可见性不合法") String visibility) {
    }

    /** 添加/调整成员。 */
    public record AddMemberReq(
            @NotNull UUID userId,
            @NotBlank @Pattern(regexp = "OWNER|EDITOR|VIEWER", message = "角色不合法") String role) {
    }

    /** 写入文档（第一批只支持文本，URL/文件导入在后续批次）。 */
    public record CreateDocumentReq(
            @NotBlank @Size(max = 200) String title,
            @NotBlank @Size(max = 200000) String content,
            @Pattern(regexp = "MANUAL|UPLOAD|URL|IMPORT", message = "来源类型不合法") String sourceType,
            @Size(max = 500) String sourceUri) {
    }

    /** CAS 版本号（归档/重建索引等状态迁移必须带）。 */
    public record VersionReq(@NotNull Long version) {
    }

    /** 检索请求。{@code mode} 缺省由服务端决定（有向量走混合，无向量走关键词）。 */
    public record SearchReq(
            @NotBlank @Size(max = 200) String query,
            Integer topK,
            @Pattern(regexp = "KEYWORD|HYBRID|VECTOR", message = "检索模式不合法") String mode) {
    }

    /** 单条命中（含引用与原文偏移，前端可据此高亮原文）。 */
    public record SearchHit(UUID chunkId, UUID documentId, String documentTitle, int seq,
                            int charStart, int charEnd, String snippet, double score,
                            String matchedBy, String citation) {
    }

    /**
     * 检索结果。
     *
     * @param mode           实际生效模式（KEYWORD / HYBRID）
     * @param vectorUsed     本次是否真的用到了向量（未配置或全部失败为 false）
     * @param degraded       是否发生降级（用户应能看到"仅关键词"的提示）
     * @param degradedReason 降级原因（EMBEDDING_UNAVAILABLE / EMBEDDING_FAILED / NO_VECTORS）
     */
    public record SearchResult(String mode, boolean vectorUsed, boolean degraded,
                               String degradedReason, int latencyMs, int hitCount,
                               List<SearchHit> hits) {
    }

    /** 空间统计（运营与排障用）。 */
    public record SpaceStatsView(long documents, long readyDocuments, long chunks,
                                 long indexedChunks) {
    }
}
