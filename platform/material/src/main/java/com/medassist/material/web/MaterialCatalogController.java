package com.medassist.material.web;

import com.medassist.common.api.ApiResponse;
import com.medassist.common.security.AuthHeaders;
import com.medassist.common.security.Permissions;
import com.medassist.material.service.ConsumableCatalogService;
import com.medassist.material.web.MaterialDtos.ConsumableView;
import com.medassist.material.web.MaterialDtos.CreateConsumableReq;
import com.medassist.material.web.MaterialDtos.MaterialSummary;
import com.medassist.material.web.MaterialDtos.ToggleConsumableReq;
import com.medassist.material.web.MaterialDtos.UpdatePriceReq;
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

import java.util.List;
import java.util.UUID;

/**
 * 耗材目录 API。
 *
 * <p>权限：{@code material:catalog:manage}（物资科维护）；查询用 {@code material:read}。
 */
@RestController
@RequestMapping("/material")
public class MaterialCatalogController {

    private static final String CATALOG = "material:catalog:manage";
    private static final String READ = "material:read";

    private final ConsumableCatalogService service;

    public MaterialCatalogController(ConsumableCatalogService service) {
        this.service = service;
    }

    @PostMapping("/consumables")
    public ApiResponse<ConsumableView> create(
            @Valid @RequestBody CreateConsumableReq req,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(value = AuthHeaders.ACTOR, defaultValue = "system") String actor) {
        Permissions.require(permissions, CATALOG);
        return ApiResponse.ok(service.create(req, actor));
    }

    @GetMapping("/consumables")
    public ApiResponse<List<ConsumableView>> list(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) Boolean enabled,
            @RequestParam(required = false) Integer limit,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions) {
        Permissions.require(permissions, READ);
        return ApiResponse.ok(service.list(keyword, category, enabled, limit));
    }

    @GetMapping("/consumables/{id}")
    public ApiResponse<ConsumableView> detail(
            @PathVariable UUID id,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions) {
        Permissions.require(permissions, READ);
        return ApiResponse.ok(service.detail(id));
    }

    /** 调价（必须带 version，防并发覆盖）。 */
    @PutMapping("/consumables/{id}/price")
    public ApiResponse<ConsumableView> adjustPrice(
            @PathVariable UUID id,
            @Valid @RequestBody UpdatePriceReq req,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(value = AuthHeaders.ACTOR, defaultValue = "system") String actor) {
        Permissions.require(permissions, CATALOG);
        return ApiResponse.ok(service.adjustPrice(id, req.price(), req.version(), actor));
    }

    /** 启停（停用后不可出库，历史追溯仍可查询）。 */
    @PutMapping("/consumables/{id}/enabled")
    public ApiResponse<ConsumableView> toggle(
            @PathVariable UUID id,
            @Valid @RequestBody ToggleConsumableReq req,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(value = AuthHeaders.ACTOR, defaultValue = "system") String actor) {
        Permissions.require(permissions, CATALOG);
        return ApiResponse.ok(service.toggle(id, req.enabled(), req.version(), actor));
    }

    /** 物资看板（KPI 走聚合，不受列表条数上限影响）。 */
    @GetMapping("/summary")
    public ApiResponse<MaterialSummary> summary(
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions) {
        Permissions.require(permissions, READ);
        return ApiResponse.ok(service.summary());
    }
}
