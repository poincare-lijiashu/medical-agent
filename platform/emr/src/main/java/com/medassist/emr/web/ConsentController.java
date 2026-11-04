package com.medassist.emr.web;

import com.medassist.common.api.ApiResponse;
import com.medassist.common.security.AuthHeaders;
import com.medassist.common.security.DataScope;
import com.medassist.common.security.Permissions;
import com.medassist.emr.service.ConsentAccessGuard;
import com.medassist.emr.service.ConsentService;
import com.medassist.emr.web.ConsentDtos.BoardView;
import com.medassist.emr.web.ConsentDtos.ConsentView;
import com.medassist.emr.web.ConsentDtos.CreateConsentReq;
import com.medassist.emr.web.ConsentDtos.CreateTemplateReq;
import com.medassist.emr.web.ConsentDtos.RevokeReq;
import com.medassist.emr.web.ConsentDtos.SignReq;
import com.medassist.emr.web.ConsentDtos.TemplateView;
import com.medassist.emr.web.ConsentDtos.VerifyView;
import com.medassist.emr.web.ConsentDtos.VersionReq;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
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
 * 知情同意书 API（G44）。
 *
 * <p>权限点：read 查看/验签/打印、write 创建、sign 签署、revoke 撤销、manage 模板维护。
 */
@RestController
@RequestMapping("/emr/consents")
public class ConsentController {

    private static final String READ = "emr:consent:read";
    private static final String WRITE = "emr:consent:write";
    private static final String SIGN = "emr:consent:sign";
    private static final String REVOKE = "emr:consent:revoke";
    private static final String MANAGE = "emr:consent:manage";

    private final ConsentService service;
    private final ConsentAccessGuard guard;

    public ConsentController(ConsentService service, ConsentAccessGuard guard) {
        this.service = service;
        this.guard = guard;
    }

    // ---------------------------------------------------------------- templates

    @GetMapping("/templates")
    public ApiResponse<List<TemplateView>> templates(
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "100") int limit,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions) {
        Permissions.require(permissions, READ);
        return ApiResponse.ok(service.templates(status, limit));
    }

    @PostMapping("/templates")
    public ApiResponse<TemplateView> createTemplate(
            @Valid @RequestBody CreateTemplateReq req,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(value = AuthHeaders.ACTOR, defaultValue = "system") String actor) {
        Permissions.require(permissions, MANAGE);
        return ApiResponse.ok(service.createTemplate(req, actor));
    }

    @PostMapping("/templates/{id}/publish")
    public ApiResponse<TemplateView> publishTemplate(
            @PathVariable UUID id, @Valid @RequestBody VersionReq req,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(value = AuthHeaders.ACTOR, defaultValue = "system") String actor) {
        Permissions.require(permissions, MANAGE);
        return ApiResponse.ok(service.publishTemplate(id, req.version(), actor));
    }

    @PostMapping("/templates/{id}/disable")
    public ApiResponse<TemplateView> disableTemplate(
            @PathVariable UUID id, @Valid @RequestBody VersionReq req,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(value = AuthHeaders.ACTOR, defaultValue = "system") String actor) {
        Permissions.require(permissions, MANAGE);
        return ApiResponse.ok(service.disableTemplate(id, req.version(), actor));
    }

    // ---------------------------------------------------------------- consents

    @GetMapping
    public ApiResponse<List<ConsentView>> list(
            @RequestParam(required = false) UUID patientId,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "100") int limit,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            DataScope scope) {
        Permissions.require(permissions, READ);
        return ApiResponse.ok(service.list(patientId, status, limit, scope.campusFilter()));
    }

    @PostMapping
    public ApiResponse<ConsentView> create(
            @Valid @RequestBody CreateConsentReq req,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(value = AuthHeaders.ACTOR, defaultValue = "system") String actor,
            DataScope scope) {
        Permissions.require(permissions, WRITE);
        return ApiResponse.ok(service.create(req, actor, scope.campusForWrite()));
    }

    @GetMapping("/{id}")
    public ApiResponse<ConsentView> detail(
            @PathVariable UUID id,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            DataScope scope) {
        Permissions.require(permissions, READ);
        guard.require(scope, id);
        return ApiResponse.ok(service.detail(id));
    }

    @GetMapping("/{id}/verify")
    public ApiResponse<VerifyView> verify(
            @PathVariable UUID id,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            DataScope scope) {
        Permissions.require(permissions, READ);
        guard.require(scope, id);
        return ApiResponse.ok(service.verify(id));
    }

    @PostMapping("/{id}/sign")
    public ApiResponse<ConsentView> sign(
            @PathVariable UUID id, @Valid @RequestBody SignReq req,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(value = AuthHeaders.ACTOR, defaultValue = "system") String actor,
            DataScope scope) {
        Permissions.require(permissions, SIGN);
        guard.require(scope, id);
        return ApiResponse.ok(service.sign(id, req, actor));
    }

    @PostMapping("/{id}/revoke")
    public ApiResponse<ConsentView> revoke(
            @PathVariable UUID id, @Valid @RequestBody RevokeReq req,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(value = AuthHeaders.ACTOR, defaultValue = "system") String actor,
            DataScope scope) {
        Permissions.require(permissions, REVOKE);
        guard.require(scope, id);
        return ApiResponse.ok(service.revoke(id, req, actor));
    }

    @GetMapping("/board")
    public ApiResponse<BoardView> board(
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions) {
        Permissions.require(permissions, READ);
        var board = service.board();
        return ApiResponse.ok(new BoardView(board.drafts(), board.signedToday(),
                board.revokedTotal()));
    }

    @GetMapping("/{id}/print")
    public ResponseEntity<String> print(
            @PathVariable UUID id,
            @RequestHeader(value = AuthHeaders.ACTOR, defaultValue = "system") String actor,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            DataScope scope) {
        Permissions.require(permissions, READ);
        guard.require(scope, id);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("text/html; charset=UTF-8"))
                .header("Content-Security-Policy", "default-src 'none'; style-src 'unsafe-inline'")
                .header("X-Content-Type-Options", "nosniff")
                .header("Cache-Control", "no-store, no-cache, must-revalidate, private")
                .header("Pragma", "no-cache")
                .body(service.printHtml(id, actor));
    }
}
