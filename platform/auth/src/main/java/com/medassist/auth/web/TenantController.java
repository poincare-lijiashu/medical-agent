package com.medassist.auth.web;

import com.medassist.auth.service.CellMigrationService;
import com.medassist.auth.service.TenantService;
import com.medassist.auth.web.dto.CellMigrationDtos;
import com.medassist.auth.web.dto.TenantDtos;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 租户台账 API（§281 / ADR-001 P0）。
 *
 * <p>授权矩阵：
 * <ul>
 *   <li>{@code tenant:read}：查看租户；没有 {@code tenant:manage} 时只能看到自己那一条；</li>
 *   <li>{@code tenant:self:manage}：租户管理员维护本租户名称/联系人/备注；</li>
 *   <li>{@code tenant:manage}：跨租户开通、改配、停用/注销（平台运营角色唯一持有）。</li>
 * </ul>
 *
 * <p>为什么开通/停用只有平台运营能做：医院管理员若能停用别家租户，
 * 等于把可用性风险交给了不可信租户；而"改自己的入口指向"同样危险（可把登录引到别处）。
 */
@RestController
@RequestMapping("/tenants")
public class TenantController {

    private static final String READ = "tenant:read";
    private static final String SELF_MANAGE = "tenant:self:manage";
    private static final String MANAGE = "tenant:manage";

    private final TenantService service;
    private final CellMigrationService migrations;

    public TenantController(TenantService service, CellMigrationService migrations) {
        this.service = service;
        this.migrations = migrations;
    }

    @GetMapping
    public ApiResponse<TenantDtos.ListResp> list(
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(value = AuthHeaders.TENANT, required = false) String tenant) {
        Permissions.require(permissions, READ);
        return ApiResponse.ok(service.list(permissions, tenant));
    }

    @GetMapping("/{code}")
    public ApiResponse<TenantDtos.Resp> detail(
            @PathVariable String code,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(value = AuthHeaders.TENANT, required = false) String tenant) {
        Permissions.require(permissions, READ);
        return ApiResponse.ok(service.detail(code, permissions, tenant));
    }

    @PostMapping
    public ApiResponse<TenantDtos.Resp> create(
            @Valid @RequestBody TenantDtos.CreateReq req,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(value = AuthHeaders.ACTOR, defaultValue = "system") String actor) {
        Permissions.require(permissions, MANAGE);
        return ApiResponse.ok(service.create(req, actor));
    }

    @PutMapping("/{code}")
    public ApiResponse<TenantDtos.Resp> update(
            @PathVariable String code,
            @Valid @RequestBody TenantDtos.UpdateReq req,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(value = AuthHeaders.ACTOR, defaultValue = "system") String actor) {
        boolean platformLevel = Permissions.has(permissions, MANAGE);
        if (!platformLevel) {
            Permissions.require(permissions, SELF_MANAGE);
        }
        return ApiResponse.ok(service.update(code, req, actor, platformLevel, permissions));
    }

    @PostMapping("/{code}/status")
    public ApiResponse<TenantDtos.Resp> changeStatus(
            @PathVariable String code,
            @Valid @RequestBody TenantDtos.StatusReq req,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(value = AuthHeaders.ACTOR, defaultValue = "system") String actor) {
        Permissions.require(permissions, MANAGE);
        return ApiResponse.ok(service.changeStatus(code, req, actor));
    }

    /**
     * 该院 Cell 的迁移台账（§282）：从 Cell 库的 {@code flyway_schema_history} 汇总"跑到哪版"。
     *
     * <p>权限只给平台运营（{@code tenant:manage}）：该接口会连到院方数据库，
     * 属于基础设施操作，医院管理员不应具备"让控制面连别人库"的能力。
     *
     * @param refresh true 时忽略 30 秒节流，强制重新拉取
     */
    @GetMapping("/{code}/migrations")
    public ApiResponse<CellMigrationDtos.CellMigrationView> migrations(
            @PathVariable String code,
            @RequestParam(defaultValue = "false") boolean refresh,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions) {
        Permissions.require(permissions, MANAGE);
        return ApiResponse.ok(migrations.view(code, refresh));
    }
}
