package com.medassist.kb.web;

import com.medassist.common.api.ApiResponse;
import com.medassist.common.security.AuthHeaders;
import com.medassist.common.security.Permissions;
import com.medassist.kb.domain.KbModel.Document;
import com.medassist.kb.domain.KbModel.Member;
import com.medassist.kb.domain.KbModel.Space;
import com.medassist.kb.service.AnswerService;
import com.medassist.kb.service.KnowledgeService;
import com.medassist.kb.service.RetrievalService;
import com.medassist.kb.web.KbDtos.AddMemberReq;
import com.medassist.kb.web.KbDtos.AnswerReq;
import com.medassist.kb.web.KbDtos.AnswerResult;
import com.medassist.kb.web.KbDtos.CreateDocumentReq;
import com.medassist.kb.web.KbDtos.CreateSpaceReq;
import com.medassist.kb.web.KbDtos.SearchReq;
import com.medassist.kb.web.KbDtos.SearchResult;
import com.medassist.kb.web.KbDtos.VersionReq;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * 知识空间 API。
 *
 * <p>两级授权，缺一不可：
 * <ol>
 *   <li>网关下发的**权限点**（能进这个功能）；</li>
 *   <li>空间**成员角色**（能看/能写哪个空间）——由 {@code SpaceAccessGuard} 在服务层校验。</li>
 * </ol>
 * 只做权限点不做成员校验，等于任何有权限的账号都能读全院知识库。
 */
@RestController
@RequestMapping("/kb/spaces")
public class KbSpaceController {

    private static final String SPACE_READ = "kb:space:read";
    private static final String SPACE_WRITE = "kb:space:write";
    private static final String DOC_READ = "kb:document:read";
    private static final String DOC_WRITE = "kb:document:write";
    private static final String SEARCH = "kb:search";
    private static final String ANSWER = "kb:answer";

    private final KnowledgeService knowledgeService;
    private final RetrievalService retrievalService;
    private final AnswerService answerService;

    public KbSpaceController(KnowledgeService knowledgeService, RetrievalService retrievalService,
                             AnswerService answerService) {
        this.knowledgeService = knowledgeService;
        this.retrievalService = retrievalService;
        this.answerService = answerService;
    }

    @GetMapping
    public ApiResponse<List<Space>> mySpaces(
            @RequestParam(required = false) Integer limit,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(value = AuthHeaders.USER_ID, required = false) UUID userId) {
        Permissions.require(permissions, SPACE_READ);
        return ApiResponse.ok(knowledgeService.mySpaces(userId, limit));
    }

    @GetMapping("/public")
    public ApiResponse<List<Space>> publicSpaces(
            @RequestParam(required = false) Integer limit,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions) {
        Permissions.require(permissions, SPACE_READ);
        return ApiResponse.ok(knowledgeService.publicSpaces(limit));
    }

    @GetMapping("/{spaceId}")
    public ApiResponse<Space> get(@PathVariable UUID spaceId,
                                  @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false)
                                  String permissions,
                                  @RequestHeader(value = AuthHeaders.USER_ID, required = false)
                                  UUID userId) {
        Permissions.require(permissions, SPACE_READ);
        return ApiResponse.ok(knowledgeService.getSpace(spaceId, userId));
    }

    @PostMapping
    public ApiResponse<Space> create(
            @Valid @RequestBody CreateSpaceReq req,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(value = AuthHeaders.ACTOR, defaultValue = "system") String actor,
            @RequestHeader(value = AuthHeaders.USER_ID, required = false) UUID userId) {
        Permissions.require(permissions, SPACE_WRITE);
        return ApiResponse.ok(knowledgeService.createSpace(req, actor, userId));
    }

    @PostMapping("/{spaceId}/archive")
    public ApiResponse<Space> archive(
            @PathVariable UUID spaceId, @Valid @RequestBody VersionReq req,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(value = AuthHeaders.ACTOR, defaultValue = "system") String actor,
            @RequestHeader(value = AuthHeaders.USER_ID, required = false) UUID userId) {
        Permissions.require(permissions, SPACE_WRITE);
        return ApiResponse.ok(knowledgeService.archiveSpace(spaceId, req.version(), actor, userId));
    }

    @PostMapping("/{spaceId}/members")
    public ApiResponse<Member> addMember(
            @PathVariable UUID spaceId, @Valid @RequestBody AddMemberReq req,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(value = AuthHeaders.ACTOR, defaultValue = "system") String actor,
            @RequestHeader(value = AuthHeaders.USER_ID, required = false) UUID userId) {
        Permissions.require(permissions, SPACE_WRITE);
        return ApiResponse.ok(knowledgeService.addMember(spaceId, req, actor, userId));
    }

    @GetMapping("/{spaceId}/members")
    public ApiResponse<List<Member>> members(
            @PathVariable UUID spaceId,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(value = AuthHeaders.USER_ID, required = false) UUID userId) {
        Permissions.require(permissions, SPACE_WRITE);
        return ApiResponse.ok(knowledgeService.members(spaceId, userId));
    }

    @PostMapping("/{spaceId}/documents")
    public ApiResponse<Document> createDocument(
            @PathVariable UUID spaceId, @Valid @RequestBody CreateDocumentReq req,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(value = AuthHeaders.ACTOR, defaultValue = "system") String actor,
            @RequestHeader(value = AuthHeaders.USER_ID, required = false) UUID userId) {
        Permissions.require(permissions, DOC_WRITE);
        return ApiResponse.ok(knowledgeService.createDocument(spaceId, req, actor, userId));
    }

    @GetMapping("/{spaceId}/documents")
    public ApiResponse<List<Document>> documents(
            @PathVariable UUID spaceId,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Integer limit,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(value = AuthHeaders.USER_ID, required = false) UUID userId) {
        Permissions.require(permissions, DOC_READ);
        return ApiResponse.ok(knowledgeService.listDocuments(spaceId, status, limit, userId));
    }

    @PostMapping("/{spaceId}/search")
    public ApiResponse<SearchResult> search(
            @PathVariable UUID spaceId, @Valid @RequestBody SearchReq req,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(value = AuthHeaders.ACTOR, defaultValue = "system") String actor,
            @RequestHeader(value = AuthHeaders.USER_ID, required = false) UUID userId) {
        Permissions.require(permissions, SEARCH);
        return ApiResponse.ok(retrievalService.search(spaceId, req, actor, userId));
    }

    /**
     * 抽取式带引用回答。
     *
     * <p>与检索是两个权限点（{@code kb:answer}）：检索可以全员开放，问答要求更高；
     * 空间成员校验仍由服务层统一做，不会因为带了权限点就越过隔离。
     */
    @PostMapping("/{spaceId}/answer")
    public ApiResponse<AnswerResult> answer(
            @PathVariable UUID spaceId, @Valid @RequestBody AnswerReq req,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(value = AuthHeaders.ACTOR, defaultValue = "system") String actor,
            @RequestHeader(value = AuthHeaders.USER_ID, required = false) UUID userId) {
        Permissions.require(permissions, ANSWER);
        return ApiResponse.ok(answerService.answer(spaceId, req, actor, userId));
    }

    @GetMapping("/{spaceId}/stats")
    public ApiResponse<Object> stats(
            @PathVariable UUID spaceId,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(value = AuthHeaders.USER_ID, required = false) UUID userId) {
        Permissions.require(permissions, SPACE_READ);
        return ApiResponse.ok(knowledgeService.stats(spaceId, userId));
    }
}
