package com.medassist.kb.web;

import com.medassist.common.api.ErrorCode;
import com.medassist.common.exception.BusinessException;
import com.medassist.kb.service.AnswerService;
import com.medassist.kb.service.KnowledgeService;
import com.medassist.kb.service.RetrievalService;
import com.medassist.kb.web.KbDtos.AnswerReq;
import com.medassist.kb.web.KbDtos.CreateDocumentReq;
import com.medassist.kb.web.KbDtos.CreateSpaceReq;
import com.medassist.kb.web.KbDtos.SearchReq;
import com.medassist.kb.web.KbDtos.VersionReq;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * 知识库接口级权限矩阵。
 *
 * <p>为什么必须单独测：知识库是"租户隔离 + 权限点"双闸门，权限点写错时
 * 业务单测（服务层）无法发现；例如检索与问答是两个权限点，若都写成
 * {@code kb:search}，所有能检索的人就都能调用问答。
 */
class KbControllerPermissionTest {

    private KnowledgeService knowledgeService;
    private RetrievalService retrievalService;
    private AnswerService answerService;
    private KbSpaceController spaceController;
    private KbDocumentController documentController;

    private final UUID spaceId = UUID.randomUUID();
    private final UUID documentId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        knowledgeService = mock(KnowledgeService.class);
        retrievalService = mock(RetrievalService.class);
        answerService = mock(AnswerService.class);
        spaceController = new KbSpaceController(knowledgeService, retrievalService, answerService);
        documentController = new KbDocumentController(knowledgeService);
    }

    private void assertForbidden(Runnable call) {
        assertThatThrownBy(call::run)
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.FORBIDDEN);
    }

    @Test
    @DisplayName("检索：需要 kb:search，文档读权限不能代替")
    void searchRequiresSearchPermission() {
        SearchReq req = new SearchReq("糖尿病用药", 5, null);
        assertForbidden(() -> spaceController.search(spaceId, req, "kb:document:read",
                "nurse01", userId));
        verify(retrievalService, never()).search(any(), any(), anyString(), any());

        spaceController.search(spaceId, req, "kb:search", "nurse01", userId);
        verify(retrievalService).search(spaceId, req, "nurse01", userId);
    }

    @Test
    @DisplayName("问答：独立权限点 kb:answer，只有检索权限不能提问")
    void answerRequiresAnswerPermission() {
        AnswerReq req = new AnswerReq("糖尿病如何用药？", 3);
        assertForbidden(() -> spaceController.answer(spaceId, req, "kb:search",
                "nurse01", userId));
        verify(answerService, never()).answer(any(), any(), anyString(), any());

        spaceController.answer(spaceId, req, "kb:answer", "nurse01", userId);
        verify(answerService).answer(spaceId, req, "nurse01", userId);
    }

    @Test
    @DisplayName("文档列表：需要 kb:document:read，空间读权限不够")
    void documentsRequireDocumentRead() {
        assertForbidden(() -> spaceController.documents(spaceId, null, 20,
                "kb:space:read", userId));
        verify(knowledgeService, never()).listDocuments(any(), any(), any(), any());

        spaceController.documents(spaceId, null, 20, "kb:document:read", userId);
        verify(knowledgeService).listDocuments(spaceId, null, 20, userId);
    }

    @Test
    @DisplayName("写文档/建空间：分别需要 kb:document:write / kb:space:write")
    void writesRequireWritePermissions() {
        CreateDocumentReq doc = new CreateDocumentReq("制度", "内容", "TEXT", null);
        assertForbidden(() -> spaceController.createDocument(spaceId, doc, "kb:document:read",
                "editor01", userId));
        verify(knowledgeService, never()).createDocument(any(), any(), anyString(), any());

        spaceController.createDocument(spaceId, doc, "kb:document:write", "editor01", userId);
        verify(knowledgeService).createDocument(spaceId, doc, "editor01", userId);

        CreateSpaceReq space = new CreateSpaceReq("cardio", "心内科知识库", null, null, "ORG");
        assertForbidden(() -> spaceController.create(space, "kb:space:read", "admin01", userId));
        verify(knowledgeService, never()).createSpace(any(), anyString(), any());

        spaceController.create(space, "kb:space:write", "admin01", userId);
        verify(knowledgeService).createSpace(space, "admin01", userId);
    }

    @Test
    @DisplayName("文档详情/归档：读与写权限点分离")
    void documentReadAndArchiveAreSeparated() {
        assertForbidden(() -> documentController.get(documentId, "kb:document:write", userId));
        documentController.get(documentId, "kb:document:read", userId);
        verify(knowledgeService).getDocument(documentId, userId);

        VersionReq version = new VersionReq(0L);
        assertForbidden(() -> documentController.archive(documentId, version, "kb:document:read",
                "editor01", userId));
        documentController.archive(documentId, version, "kb:document:write", "editor01", userId);
        verify(knowledgeService).archiveDocument(documentId, 0L, "editor01", userId);

        assertForbidden(() -> documentController.reindex(documentId, "kb:document:read",
                "editor01", userId));
        documentController.reindex(documentId, "kb:document:write", "editor01", userId);
        verify(knowledgeService).reindexDocument(documentId, "editor01", userId);
    }

    @Test
    @DisplayName("空间成员：需要 kb:space:write（成员名单属管理面）")
    void membersRequireSpaceWrite() {
        assertForbidden(() -> spaceController.members(spaceId, "kb:space:read", userId));
        verify(knowledgeService, never()).members(any(), any());

        spaceController.members(spaceId, "kb:space:write", userId);
        verify(knowledgeService).members(spaceId, userId);
    }

    @Test
    @DisplayName("权限头为空：403，不触达服务层")
    void emptyPermissionsForbidden() {
        assertForbidden(() -> spaceController.mySpaces(20, null, userId));
        verify(knowledgeService, never()).mySpaces(any(), eq(20));
    }
}
