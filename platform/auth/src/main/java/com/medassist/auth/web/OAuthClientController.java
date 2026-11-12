package com.medassist.auth.web;

import com.medassist.auth.domain.OAuthClient;
import com.medassist.auth.service.OAuthClientService;
import com.medassist.auth.web.dto.OAuthDtos.CreateReq;
import com.medassist.auth.web.dto.OAuthDtos.ScopeCatalog;
import com.medassist.auth.web.dto.OAuthDtos.ScopesReq;
import com.medassist.auth.web.dto.OAuthDtos.SecretResp;
import com.medassist.auth.web.dto.OAuthDtos.StatusReq;
import com.medassist.common.api.ApiResponse;
import com.medassist.common.security.AuthHeaders;
import com.medassist.common.security.Permissions;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * 开放平台客户端管理（控制台）。
 *
 * <p>与令牌端点分离：本接口走用户 JWT + 权限点（`integration:client:read/manage`），
 * 令牌端点 `/oauth/token` 走客户端密钥（网关白名单放行）。
 */
@RestController
@RequestMapping("/oauth-clients")
public class OAuthClientController {

    private static final String READ = "integration:client:read";
    private static final String MANAGE = "integration:client:manage";

    private final OAuthClientService service;

    public OAuthClientController(OAuthClientService service) {
        this.service = service;
    }

    @GetMapping
    public ApiResponse<List<OAuthClient>> list(
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions) {
        Permissions.require(permissions, READ);
        return ApiResponse.ok(service.list());
    }

    /** 可授予范围目录：前端据此渲染多选，避免手输拼错。 */
    @GetMapping("/scopes")
    public ApiResponse<ScopeCatalog> scopes(
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions) {
        Permissions.require(permissions, READ);
        return ApiResponse.ok(new ScopeCatalog(service.scopeCatalogView()));
    }

    @PostMapping
    public ApiResponse<SecretResp> create(
            @RequestBody CreateReq req,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(value = AuthHeaders.ACTOR, required = false) String actor) {
        Permissions.require(permissions, MANAGE);
        OAuthClientService.Created created = service.create(req.name(), req.description(), req.scopes(),
                req.tokenTtlSeconds() == null ? 900 : req.tokenTtlSeconds(),
                req.expiresAt(), actor);
        return ApiResponse.ok(new SecretResp(created.client(), created.secret()));
    }

    @PostMapping("/{id}/rotate")
    public ApiResponse<SecretResp> rotate(
            @PathVariable UUID id,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(value = AuthHeaders.ACTOR, required = false) String actor) {
        Permissions.require(permissions, MANAGE);
        String secret = service.rotate(id, actor);
        return ApiResponse.ok(new SecretResp(service.get(id), secret));
    }

    @PostMapping("/{id}/status")
    public ApiResponse<OAuthClient> status(
            @PathVariable UUID id,
            @RequestBody StatusReq req,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(value = AuthHeaders.ACTOR, required = false) String actor) {
        Permissions.require(permissions, MANAGE);
        boolean enabled = Boolean.TRUE.equals(req.enabled());
        return ApiResponse.ok(service.setStatus(id, enabled, actor));
    }

    @PutMapping("/{id}/scopes")
    public ApiResponse<OAuthClient> updateScopes(
            @PathVariable UUID id,
            @RequestBody ScopesReq req,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(value = AuthHeaders.ACTOR, required = false) String actor) {
        Permissions.require(permissions, MANAGE);
        return ApiResponse.ok(service.updateScopes(id, req.scopes(), actor));
    }
}
