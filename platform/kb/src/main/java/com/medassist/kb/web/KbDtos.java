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

    /**
     * 写入文档。
     *
     * <p>第一批只接受文本；第二批起支持显式声明 HTML / MARKDOWN / TEXT，
     * 服务端写入时清洗标签与语法糖（见 {@code DocumentParser}）。
     * 未声明时按内容自动探测，避免"粘贴网页当纯文本入库"导致检索词元全是标签名。
     */
    public record CreateDocumentReq(
            @NotBlank @Size(max = 200) String title,
            @NotBlank @Size(max = 200000) String content,
            @Pattern(regexp = "MANUAL|UPLOAD|URL|IMPORT|HTML|MARKDOWN|TEXT",
                    message = "来源类型不合法") String sourceType,
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

    /**
     * 抽取式问答请求。
     *
     * @param question 问题（复用中文二元组检索，不需要问句模板）
     * @param topK     参与拼接的证据条数上限（服务端再收敛到 answer.max-chunks）
     */
    public record AnswerReq(
            @NotBlank @Size(max = 200) String question,
            Integer topK) {
    }

    /**
     * 引用：{@code quote} 是**原文片段**（不是摘要），{@code charStart/charEnd}
     * 相对解析后正文，前端可据此回指原文高亮。
     */
    public record AnswerCitation(UUID chunkId, UUID documentId, String documentTitle, int seq,
                                 int charStart, int charEnd, String quote, double score,
                                 String citation) {
    }

    /**
     * 抽取式回答。
     *
     * <p>语义约定（比字段本身更重要）：
     * <ul>
     *   <li>{@code extractive=true} 表示答案由检索命中的原文片段拼接而成，**没有任何生成**；</li>
     *   <li>{@code llmUsed=false} 表示本次未调用大模型（当前版本始终为 false）；</li>
     *   <li>{@code grounded=false} 表示没有找到证据，系统**拒答**并给出 {@code refusedReason}，
     *       绝不编造"看起来像答案"的内容；</li>
     *   <li>{@code degraded/degradedReason} 与检索一致（向量不可用时为关键词模式）。</li>
     * </ul>
     */
    public record AnswerResult(boolean grounded, String answer, List<AnswerCitation> citations,
                               boolean extractive, boolean llmUsed, String refusedReason,
                               String mode, boolean degraded, String degradedReason,
                               int latencyMs) {
    }
}
