package com.medassist.kb.service;

import com.medassist.common.api.ErrorCode;
import com.medassist.common.exception.BusinessException;
import com.medassist.kb.domain.KbModel.Document;
import com.medassist.kb.domain.KbModel.Member;
import com.medassist.kb.domain.KbModel.Space;
import com.medassist.kb.repo.KbRepository;
import com.medassist.kb.web.KbDtos.AddMemberReq;
import com.medassist.kb.web.KbDtos.CreateDocumentReq;
import com.medassist.kb.web.KbDtos.CreateSpaceReq;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 知识空间与文档规则：创建者即 OWNER、内容去重、归档 CAS、重建索引互斥。 */
class KnowledgeServiceTest {

    private KbRepository repo;
    private SpaceAccessGuard guard;
    private KnowledgeService service;

    private final UUID userId = UUID.randomUUID();
    private final UUID spaceId = UUID.randomUUID();
    private final UUID documentId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        repo = mock(KbRepository.class);
        guard = mock(SpaceAccessGuard.class);
        service = new KnowledgeService(repo, guard, JsonMapper.builder().build());
    }

    private Space space() {
        return new Space(spaceId, "kb-cardio", "心血管指南", null, null, Space.PRIVATE,
                Space.ACTIVE, "doctor01", 0L, Instant.now());
    }

    private Document document(String status, long version) {
        return new Document(documentId, spaceId, "急性心衰诊疗规范", "MANUAL", null,
                "hash-1", status, 3, 1200, 1200, "解析方式=PLAIN", null, "doctor01", version,
                Instant.now());
    }

    @Test
    @DisplayName("创建空间：创建者自动成为 OWNER，并留审计")
    void createSpaceMakesOwner() {
        when(repo.findSpaceByCode("kb-cardio")).thenReturn(Optional.empty());

        Space created = service.createSpace(new CreateSpaceReq("kb-cardio", "心血管指南",
                null, null, "PRIVATE"), "doctor01", userId);

        ArgumentCaptor<Space> captor = ArgumentCaptor.forClass(Space.class);
        verify(repo).insertSpace(captor.capture());
        assertThat(captor.getValue().code()).isEqualTo("kb-cardio");
        assertThat(captor.getValue().status()).isEqualTo(Space.ACTIVE);
        verify(repo).insertMember(created.id(), userId, Member.OWNER);
        verify(repo).logChange(eq("doctor01"), eq("KB_SPACE_CREATE"), eq("KB_SPACE"),
                eq(created.id().toString()), anyString());
    }

    @Test
    @DisplayName("归档空间：返回的是归档后的状态（不能再用 requireActive 自我否决）")
    void archiveSpaceReturnsArchivedEntity() {
        when(guard.requireRole(spaceId, userId, Member.OWNER)).thenReturn(space());
        when(repo.archiveSpace(spaceId, 0L)).thenReturn(true);
        Space archived = new Space(spaceId, "kb-cardio", "心血管指南", null, null, Space.PRIVATE,
                Space.ARCHIVED, "doctor01", 1L, Instant.now());
        when(repo.findSpace(spaceId)).thenReturn(Optional.of(archived));

        Space result = service.archiveSpace(spaceId, 0L, "doctor01", userId);

        assertThat(result.status()).isEqualTo(Space.ARCHIVED);
        assertThat(result.version()).isEqualTo(1L);
    }

    @Test
    @DisplayName("创建空间：编码重复 → 409，不写库")
    void createSpaceDuplicateCode() {
        when(repo.findSpaceByCode("kb-cardio")).thenReturn(Optional.of(space()));

        assertThatThrownBy(() -> service.createSpace(
                new CreateSpaceReq("kb-cardio", "重复", null, null, null), "doctor01", userId))
                .isInstanceOfSatisfying(BusinessException.class, e ->
                        assertThat(e.errorCode()).isEqualTo(ErrorCode.KB_SPACE_DUPLICATE));
        verify(repo, never()).insertSpace(any());
    }

    @Test
    @DisplayName("创建空间：缺少 X-User-Id → 403（知识库不做匿名写入）")
    void createSpaceRequiresUserId() {
        assertThatThrownBy(() -> service.createSpace(
                new CreateSpaceReq("kb-x", "x", null, null, null), "doctor01", null))
                .isInstanceOfSatisfying(BusinessException.class, e ->
                        assertThat(e.errorCode()).isEqualTo(ErrorCode.KB_SPACE_ACCESS_DENIED));
        verify(repo, never()).insertSpace(any());
    }

    @Test
    @DisplayName("写入文档：同空间内容哈希重复 → 409（增量更新靠归档后重入）")
    void createDocumentDuplicatesConflict() {
        when(guard.requireRole(spaceId, userId, Member.EDITOR)).thenReturn(space());
        when(repo.findActiveByHash(eq(spaceId), anyString()))
                .thenReturn(Optional.of(document(Document.READY, 1L)));

        assertThatThrownBy(() -> service.createDocument(spaceId,
                new CreateDocumentReq("重复文档", "同样的正文", "MANUAL", null), "doctor01", userId))
                .isInstanceOfSatisfying(BusinessException.class, e ->
                        assertThat(e.errorCode()).isEqualTo(ErrorCode.KB_DOCUMENT_DUPLICATE));
        verify(repo, never()).insertDocument(any(), anyString());
    }

    @Test
    @DisplayName("写入文档：落 PENDING + 入队 INDEX 任务（接口不阻塞向量化）")
    void createDocumentEnqueuesIndexTask() {
        when(guard.requireRole(spaceId, userId, Member.EDITOR)).thenReturn(space());
        when(repo.findActiveByHash(eq(spaceId), anyString())).thenReturn(Optional.empty());

        Document created = service.createDocument(spaceId,
                new CreateDocumentReq("急性心衰", "正文内容", null, null), "doctor01", userId);

        ArgumentCaptor<Document> captor = ArgumentCaptor.forClass(Document.class);
        verify(repo).insertDocument(captor.capture(), eq("正文内容"));
        assertThat(captor.getValue().status()).isEqualTo(Document.PENDING);
        assertThat(captor.getValue().contentHash()).hasSize(64);
        assertThat(created.charCount()).isEqualTo(4);
        assertThat(created.parseNotes()).isEqualTo("解析方式=PLAIN");
        verify(repo).insertTask(any(), eq(created.id()), eq("INDEX"));
    }

    @Test
    @DisplayName("写入 HTML 文档：落库的是清洗后正文，rawCharCount 记录原始长度，parseNotes 留解析方式")
    void createDocumentParsesHtmlBeforePersisting() {
        when(guard.requireRole(spaceId, userId, Member.EDITOR)).thenReturn(space());
        when(repo.findActiveByHash(eq(spaceId), anyString())).thenReturn(Optional.empty());

        Document created = service.createDocument(spaceId, new CreateDocumentReq("阑尾炎",
                "<p>急性&nbsp;阑尾炎</p><script>track()</script>", "HTML", null),
                "doctor01", userId);

        ArgumentCaptor<String> content = ArgumentCaptor.forClass(String.class);
        verify(repo).insertDocument(any(), content.capture());
        assertThat(content.getValue()).isEqualTo("急性 阑尾炎");
        assertThat(created.charCount()).isEqualTo(6);
        assertThat(created.rawCharCount()).isGreaterThan(created.charCount());
        assertThat(created.parseNotes()).startsWith("解析方式=HTML");
    }

    @Test
    @DisplayName("写入 PDF：422 显式拒绝，不落库也不入队（不留半成品文档）")
    void createDocumentRejectsUnsupportedFormat() {
        when(guard.requireRole(spaceId, userId, Member.EDITOR)).thenReturn(space());

        assertThatThrownBy(() -> service.createDocument(spaceId, new CreateDocumentReq(
                "诊疗指南", "%PDF-1.7 ...", "UPLOAD", "guide.pdf"), "doctor01", userId))
                .isInstanceOfSatisfying(BusinessException.class, e ->
                        assertThat(e.errorCode()).isEqualTo(ErrorCode.KB_PARSE_UNSUPPORTED));
        verify(repo, never()).insertDocument(any(), anyString());
        verify(repo, never()).insertTask(any(), any(), anyString());
    }

    @Test
    @DisplayName("归档文档：版本号已变化 → 409（CAS）")
    void archiveDocumentStaleVersion() {
        when(repo.findDocument(documentId)).thenReturn(Optional.of(document(Document.READY, 3L)));
        when(guard.requireRole(spaceId, userId, Member.EDITOR)).thenReturn(space());
        when(repo.archiveDocument(documentId, 1L)).thenReturn(false);

        assertThatThrownBy(() -> service.archiveDocument(documentId, 1L, "doctor01", userId))
                .isInstanceOfSatisfying(BusinessException.class, e ->
                        assertThat(e.errorCode()).isEqualTo(ErrorCode.CONFLICT));
        verify(repo, never()).logChange(anyString(), anyString(), anyString(), anyString(),
                anyString());
    }

    @Test
    @DisplayName("重建索引：已有排队任务 → 409，避免连点把 CPU 打满")
    void reindexRejectsWhenTaskActive() {
        when(repo.findDocument(documentId)).thenReturn(Optional.of(document(Document.READY, 1L)));
        when(guard.requireRole(spaceId, userId, Member.EDITOR)).thenReturn(space());
        when(repo.hasActiveTask(documentId)).thenReturn(true);

        assertThatThrownBy(() -> service.reindexDocument(documentId, "doctor01", userId))
                .isInstanceOfSatisfying(BusinessException.class, e ->
                        assertThat(e.errorCode()).isEqualTo(ErrorCode.KB_DOCUMENT_STATUS_INVALID));
        verify(repo, never()).insertTask(any(), any(), anyString());
    }

    @Test
    @DisplayName("成员管理：已有他人 OWNER 时不能再加第二个 OWNER（避免所有权不清）")
    void cannotHaveTwoOwners() {
        when(guard.requireRole(spaceId, userId, Member.OWNER)).thenReturn(space());
        when(repo.listMembers(spaceId)).thenReturn(List.of(
                new Member(spaceId, userId, Member.OWNER, Instant.now())));
        UUID other = UUID.randomUUID();

        assertThatThrownBy(() -> service.addMember(spaceId,
                new AddMemberReq(other, Member.OWNER), "doctor01", userId))
                .isInstanceOfSatisfying(BusinessException.class, e ->
                        assertThat(e.errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED));
        verify(repo, never()).insertMember(any(), any(), anyString());
    }

    @Test
    @DisplayName("哈希稳定：同一内容两次计算结果一致（去重依赖它）")
    void sha256IsStable() {
        assertThat(KnowledgeService.sha256("abc")).isEqualTo(KnowledgeService.sha256("abc"));
        assertThat(KnowledgeService.sha256("abc")).hasSize(64).isNotEqualTo(
                KnowledgeService.sha256("abd"));
    }
}
