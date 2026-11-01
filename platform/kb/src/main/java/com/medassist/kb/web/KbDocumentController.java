package com.medassist.kb.web;

import com.medassist.common.api.ApiResponse;
import com.medassist.common.security.AuthHeaders;
import com.medassist.common.security.Permissions;
import com.medassist.kb.domain.KbModel.Document;
import com.medassist.kb.service.KnowledgeService;
import com.medassist.kb.web.KbDtos.VersionReq;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** 文档 API（详情 / 归档 / 重建索引）。 */
@RestController
@RequestMapping("/kb/documents")
public class KbDocumentController {

    private static final String DOC_READ = "kb:document:read";
    private static final String DOC_WRITE = "kb:document:write";

    private final KnowledgeService knowledgeService;

    public KbDocumentController(KnowledgeService knowledgeService) {
        this.knowledgeService = knowledgeService;
    }

    @GetMapping("/{id}")
    public ApiResponse<Document> get(@PathVariable UUID id,
                                     @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false)
                                     String permissions,
                                     @RequestHeader(value = AuthHeaders.USER_ID, required = false)
                                     UUID userId) {
        Permissions.require(permissions, DOC_READ);
        return ApiResponse.ok(knowledgeService.getDocument(id, userId));
    }

    @PostMapping("/{id}/archive")
    public ApiResponse<Document> archive(
            @PathVariable UUID id, @Valid @RequestBody VersionReq req,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(value = AuthHeaders.ACTOR, defaultValue = "system") String actor,
            @RequestHeader(value = AuthHeaders.USER_ID, required = false) UUID userId) {
        Permissions.require(permissions, DOC_WRITE);
        return ApiResponse.ok(knowledgeService.archiveDocument(id, req.version(), actor, userId));
    }

    @PostMapping("/{id}/reindex")
    public ApiResponse<Document> reindex(
            @PathVariable UUID id,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(value = AuthHeaders.ACTOR, defaultValue = "system") String actor,
            @RequestHeader(value = AuthHeaders.USER_ID, required = false) UUID userId) {
        Permissions.require(permissions, DOC_WRITE);
        return ApiResponse.ok(knowledgeService.reindexDocument(id, actor, userId));
    }
}
