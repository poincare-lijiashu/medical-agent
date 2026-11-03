package com.medassist.auth.web;

import com.medassist.auth.domain.Org;
import com.medassist.auth.domain.Role;
import com.medassist.auth.repo.RbacRepository;
import com.medassist.common.api.ApiResponse;
import com.medassist.common.security.AuthHeaders;
import com.medassist.common.security.Permissions;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 角色与机构只读参考数据。
 *
 * <p>账号管理页需要把角色/机构编码翻译成人能读的名称，并保证角色下拉不会出现"猜出来的编码"。
 * 角色与机构由授权矩阵维护，这里只读；写操作仍走配置变更流程。
 *
 * <p>权限：{@code auth:user:read}（与账号查询共用，避免为只读参考数据再拆权限点）。
 */
@RestController
@RequestMapping("/rbac")
public class RbacController {

    private static final String READ = "auth:user:read";

    private final RbacRepository repo;

    public RbacController(RbacRepository repo) {
        this.repo = repo;
    }

    @GetMapping("/roles")
    public ApiResponse<List<Role>> roles(
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions) {
        Permissions.require(permissions, READ);
        return ApiResponse.ok(repo.listRoles());
    }

    @GetMapping("/orgs")
    public ApiResponse<List<Org>> orgs(
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions) {
        Permissions.require(permissions, READ);
        return ApiResponse.ok(repo.listOrgs());
    }
}
