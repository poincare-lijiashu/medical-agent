package com.medassist.pharmacy.web;

import com.medassist.common.api.ApiResponse;
import com.medassist.common.security.AuthHeaders;
import com.medassist.common.security.Permissions;
import com.medassist.pharmacy.web.PharmacyDtos.CreateDrugReq;
import com.medassist.pharmacy.web.PharmacyDtos.DrugView;
import com.medassist.pharmacy.web.PharmacyDtos.ToggleDrugReq;
import com.medassist.pharmacy.web.PharmacyDtos.UpdatePriceReq;
import com.medassist.pharmacy.service.DrugCatalogService;
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
 * 药品字典 API。
 *
 * <p>权限：{@code pharmacy:drug:manage}（药房主任/管理员）；查询用 {@code pharmacy:read}。
 */
@RestController
@RequestMapping("/pharmacy/drugs")
public class DrugController {

    private static final String DRUG_MANAGE = "pharmacy:drug:manage";
    private static final String READ = "pharmacy:read";

    private final DrugCatalogService service;

    public DrugController(DrugCatalogService service) {
        this.service = service;
    }

    @PostMapping
    public ApiResponse<DrugView> create(
            @Valid @RequestBody CreateDrugReq req,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(value = AuthHeaders.ACTOR, defaultValue = "system") String actor) {
        Permissions.require(permissions, DRUG_MANAGE);
        return ApiResponse.ok(service.create(req, actor));
    }

    @GetMapping
    public ApiResponse<List<DrugView>> list(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) Boolean enabled,
            @RequestParam(required = false) Integer limit,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions) {
        Permissions.require(permissions, READ);
        return ApiResponse.ok(service.list(keyword, enabled, limit));
    }

    @GetMapping("/{id}")
    public ApiResponse<DrugView> detail(
            @PathVariable UUID id,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions) {
        Permissions.require(permissions, READ);
        return ApiResponse.ok(service.detail(id));
    }

    /** 调价（必须带 version，防并发覆盖）。 */
    @PutMapping("/{id}/price")
    public ApiResponse<DrugView> adjustPrice(
            @PathVariable UUID id,
            @Valid @RequestBody UpdatePriceReq req,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(value = AuthHeaders.ACTOR, defaultValue = "system") String actor) {
        Permissions.require(permissions, DRUG_MANAGE);
        return ApiResponse.ok(service.adjustPrice(id, req.price(), req.version(), actor));
    }

    /** 启用/停用（停用后不可开方，已开处方仍可发药）。 */
    @PutMapping("/{id}/enabled")
    public ApiResponse<DrugView> toggle(
            @PathVariable UUID id,
            @Valid @RequestBody ToggleDrugReq req,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(value = AuthHeaders.ACTOR, defaultValue = "system") String actor) {
        Permissions.require(permissions, DRUG_MANAGE);
        return ApiResponse.ok(service.toggle(id, req.enabled(), req.version(), actor));
    }
}
