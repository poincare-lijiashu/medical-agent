package com.medassist.auth.web;

import com.medassist.auth.domain.Campus;
import com.medassist.auth.service.CampusService;
import com.medassist.auth.web.dto.CreateCampusReq;
import com.medassist.auth.web.dto.UpdateCampusReq;
import com.medassist.common.api.ApiResponse;
import com.medassist.common.security.AuthHeaders;
import com.medassist.common.security.Permissions;
import jakarta.validation.Valid;
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
 * 院区字典维护 API（G46）。
 *
 * <p>权限：查看 {@code auth:campus:read}，维护 {@code auth:campus:manage}。
 * 账号与院区的绑定仍走 {@code PUT /users/{id}/campuses}（{@code auth:user:update}），
 * 避免"能改院区字典"顺带获得"能给账号扩权"。
 */
@RestController
@RequestMapping("/campuses")
public class CampusController {

    private static final String READ = "auth:campus:read";
    private static final String MANAGE = "auth:campus:manage";

    private final CampusService service;

    public CampusController(CampusService service) {
        this.service = service;
    }

    @GetMapping
    public ApiResponse<List<Campus>> list(
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions) {
        Permissions.require(permissions, READ);
        return ApiResponse.ok(service.list());
    }

    @PostMapping
    public ApiResponse<Campus> create(
            @Valid @RequestBody CreateCampusReq req,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(value = AuthHeaders.ACTOR, defaultValue = "system") String actor) {
        Permissions.require(permissions, MANAGE);
        return ApiResponse.ok(service.create(req, actor));
    }

    @PutMapping("/{id}")
    public ApiResponse<Campus> update(
            @PathVariable UUID id,
            @Valid @RequestBody UpdateCampusReq req,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(value = AuthHeaders.ACTOR, defaultValue = "system") String actor) {
        Permissions.require(permissions, MANAGE);
        return ApiResponse.ok(service.update(id, req, actor));
    }
}
