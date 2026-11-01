package com.medassist.kb.service;

import com.medassist.common.api.ErrorCode;
import com.medassist.common.exception.BusinessException;
import com.medassist.kb.domain.KbModel.Document;
import com.medassist.kb.domain.KbModel.Member;
import com.medassist.kb.domain.KbModel.Space;
import com.medassist.kb.parse.DocumentParser;
import com.medassist.kb.repo.KbRepository;
import com.medassist.kb.web.KbDtos.AddMemberReq;
import com.medassist.kb.web.KbDtos.CreateDocumentReq;
import com.medassist.kb.web.KbDtos.CreateSpaceReq;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 知识空间与文档管理。
 *
 * <p>不变量：
 * <ol>
 *   <li>创建者自动成为 OWNER（不需要额外的"初始化成员"操作）；</li>
 *   <li>同一空间内内容完全相同的文档不重复入库（SHA-256 去重，归档后可重新入库）；</li>
 *   <li>文档写入即入队索引任务，索引由后台 worker 异步完成（接口不阻塞在向量化上）；</li>
 *   <li>所有写操作都落 {@code kb_change_log}，且归档是"状态迁移 + 版本号 CAS"。</li>
 * </ol>
 */
@Service
public class KnowledgeService {

    private final KbRepository repo;
    private final SpaceAccessGuard guard;
    private final ObjectMapper objectMapper;

    public KnowledgeService(KbRepository repo, SpaceAccessGuard guard, ObjectMapper objectMapper) {
        this.repo = repo;
        this.guard = guard;
        this.objectMapper = objectMapper;
    }

    // ---------------------------------------------------------------- 空间

    @Transactional
    public Space createSpace(CreateSpaceReq req, String actor, UUID userId) {
        requireUser(userId);
        String code = req.code().trim();
        if (repo.findSpaceByCode(code).isPresent()) {
            throw new BusinessException(ErrorCode.KB_SPACE_DUPLICATE);
        }
        Space space = new Space(UUID.randomUUID(), code, req.name().trim(), blankToNull(req.description()),
                blankToNull(req.orgCode()),
                req.visibility() == null ? Space.PRIVATE : req.visibility(),
                Space.ACTIVE, actor, 0L, Instant.now());
        try {
            repo.insertSpace(space);
        } catch (DataIntegrityViolationException e) {
            throw new BusinessException(ErrorCode.KB_SPACE_DUPLICATE);
        }
        repo.insertMember(space.id(), userId, Member.OWNER);
        audit(actor, "KB_SPACE_CREATE", "KB_SPACE", space.id().toString(),
                Map.of("code", space.code(), "visibility", space.visibility()));
        return space;
    }

    public List<Space> mySpaces(UUID userId, Integer limit) {
        requireUser(userId);
        int size = limit == null || limit <= 0 ? 50 : Math.min(limit, 200);
        return repo.listSpacesForUser(userId, size);
    }

    public List<Space> publicSpaces(Integer limit) {
        int size = limit == null || limit <= 0 ? 50 : Math.min(limit, 200);
        return repo.listPublicSpaces(size);
    }

    public Space getSpace(UUID spaceId, UUID userId) {
        return guard.requireReadable(spaceId, userId);
    }

    @Transactional
    public Space archiveSpace(UUID spaceId, long version, String actor, UUID userId) {
        Space space = guard.requireRole(spaceId, userId, Member.OWNER);
        if (!repo.archiveSpace(space.id(), version)) {
            throw new BusinessException(ErrorCode.CONFLICT, "空间状态已变化，请刷新后重试");
        }
        audit(actor, "KB_SPACE_ARCHIVE", "KB_SPACE", spaceId.toString(),
                Map.of("code", space.code()));
        // 归档后不能再走 requireActive（那会把自己刚归档的空间判为"不可访问"）
        return repo.findSpace(spaceId)
                .orElseThrow(() -> new BusinessException(ErrorCode.KB_SPACE_NOT_FOUND));
    }

    // ---------------------------------------------------------------- 成员

    @Transactional
    public Member addMember(UUID spaceId, AddMemberReq req, String actor, UUID userId) {
        guard.requireRole(spaceId, userId, Member.OWNER);
        if (req.userId().equals(userId) && !Member.OWNER.equals(req.role())) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "不能降低自己的所有者角色");
        }
        if (Member.OWNER.equals(req.role())) {
            // 不允许"静默转移所有权"：先显式降级原 OWNER，避免出现两个所有者
            repo.listMembers(spaceId).stream()
                    .filter(m -> Member.OWNER.equals(m.role()) && !m.userId().equals(req.userId()))
                    .findFirst()
                    .ifPresent(m -> {
                        throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                                "该空间已有所有者，请先调整原所有者角色");
                    });
        }
        repo.insertMember(spaceId, req.userId(), req.role());
        audit(actor, "KB_MEMBER_UPSERT", "KB_SPACE", spaceId.toString(),
                Map.of("userId", req.userId().toString(), "role", req.role()));
        return repo.findMember(spaceId, req.userId())
                .orElseThrow(() -> new BusinessException(ErrorCode.KB_SPACE_NOT_FOUND));
    }

    public List<Member> members(UUID spaceId, UUID userId) {
        guard.requireRole(spaceId, userId, Member.OWNER);
        return repo.listMembers(spaceId);
    }

    // ---------------------------------------------------------------- 文档

    @Transactional
    public Document createDocument(UUID spaceId, CreateDocumentReq req, String actor, UUID userId) {
        guard.requireRole(spaceId, userId, Member.EDITOR);
        // 写入时一次解析（HTML/Markdown 清洗），之后的切片、去重哈希、引用偏移都基于这份正文
        DocumentParser.Parsed parsed = DocumentParser.parse(req.sourceType(), req.sourceUri(),
                req.content());
        String content = parsed.content();
        String hash = sha256(content);
        repo.findActiveByHash(spaceId, hash).ifPresent(existing -> {
            throw new BusinessException(ErrorCode.KB_DOCUMENT_DUPLICATE,
                    "该知识空间已存在内容完全相同的文档：" + existing.title());
        });
        Document document = new Document(UUID.randomUUID(), spaceId, req.title().trim(),
                req.sourceType() == null ? "MANUAL" : req.sourceType(), blankToNull(req.sourceUri()),
                hash, Document.PENDING, 0, content.length(), parsed.rawChars(), parsed.notesText(),
                null, actor, 0L, Instant.now());
        try {
            repo.insertDocument(document, content);
        } catch (DataIntegrityViolationException e) {
            throw new BusinessException(ErrorCode.KB_DOCUMENT_DUPLICATE);
        }
        repo.insertTask(UUID.randomUUID(), document.id(), "INDEX");
        audit(actor, "KB_DOCUMENT_CREATE", "KB_DOCUMENT", document.id().toString(),
                Map.of("spaceId", spaceId.toString(), "title", document.title(),
                        "charCount", document.charCount(), "rawCharCount", document.rawCharCount(),
                        "parseKind", parsed.kind()));
        return document;
    }

    public List<Document> listDocuments(UUID spaceId, String status, Integer limit, UUID userId) {
        guard.requireReadable(spaceId, userId);
        String normalized = blankToNull(status) == null ? null : status.trim().toUpperCase();
        int size = limit == null || limit <= 0 ? 50 : Math.min(limit, 200);
        return repo.listDocuments(spaceId, normalized, size);
    }

    public Document getDocument(UUID documentId, UUID userId) {
        Document document = requireDocument(documentId);
        guard.requireReadable(document.spaceId(), userId);
        return document;
    }

    @Transactional
    public Document archiveDocument(UUID documentId, long version, String actor, UUID userId) {
        Document document = requireDocument(documentId);
        guard.requireRole(document.spaceId(), userId, Member.EDITOR);
        if (!repo.archiveDocument(documentId, version)) {
            throw new BusinessException(ErrorCode.CONFLICT, "文档状态已变化，请刷新后重试");
        }
        audit(actor, "KB_DOCUMENT_ARCHIVE", "KB_DOCUMENT", documentId.toString(),
                Map.of("title", document.title()));
        return requireDocument(documentId);
    }

    /** 重建索引：已有排队任务时返回 409，避免重复点击把 CPU 打满。 */
    @Transactional
    public Document reindexDocument(UUID documentId, String actor, UUID userId) {
        Document document = requireDocument(documentId);
        guard.requireRole(document.spaceId(), userId, Member.EDITOR);
        if (Document.ARCHIVED.equals(document.status())) {
            throw new BusinessException(ErrorCode.KB_DOCUMENT_STATUS_INVALID, "已归档文档不能重建索引");
        }
        if (repo.hasActiveTask(documentId)) {
            throw new BusinessException(ErrorCode.KB_DOCUMENT_STATUS_INVALID,
                    "该文档已有排队中的索引任务，请稍后再试");
        }
        repo.insertTask(UUID.randomUUID(), documentId, "REINDEX");
        audit(actor, "KB_DOCUMENT_REINDEX", "KB_DOCUMENT", documentId.toString(),
                Map.of("title", document.title()));
        return requireDocument(documentId);
    }

    public Object stats(UUID spaceId, UUID userId) {
        guard.requireReadable(spaceId, userId);
        return repo.stats(spaceId);
    }

    // ---------------------------------------------------------------- 内部工具

    private Document requireDocument(UUID documentId) {
        return repo.findDocument(documentId)
                .orElseThrow(() -> new BusinessException(ErrorCode.KB_DOCUMENT_NOT_FOUND));
    }

    private static void requireUser(UUID userId) {
        if (userId == null) {
            throw new BusinessException(ErrorCode.KB_SPACE_ACCESS_DENIED,
                    "缺少操作者身份（X-User-Id），不能访问知识库");
        }
    }

    /** SHA-256(content)：用于同空间去重与增量更新判定。 */
    static String sha256(String content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(content.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JVM 缺少 SHA-256 实现", e);
        }
    }

    private void audit(String actor, String action, String targetType, String targetId,
                       Map<String, Object> detail) {
        try {
            repo.logChange(actor, action, targetType, targetId,
                    objectMapper.writeValueAsString(new LinkedHashMap<>(detail)));
        } catch (RuntimeException e) {
            repo.logChange(actor, action, targetType, targetId, "{\"_detail_serialize_failed\":true}");
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
