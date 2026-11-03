package com.medassist.pharmacy.web;

import com.medassist.common.api.ApiResponse;
import com.medassist.common.security.AuthHeaders;
import com.medassist.common.security.Permissions;
import com.medassist.pharmacy.service.SupplierService;
import com.medassist.pharmacy.web.PurchaseDtos.CreateSupplierReq;
import com.medassist.pharmacy.web.PurchaseDtos.SupplierStatusReq;
import com.medassist.pharmacy.web.PurchaseDtos.SupplierView;
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
 * 供应商 API。
 *
 * <p>权限：{@code pharmacy:supplier:manage}（新建/启停）；查询用 {@code pharmacy:purchase:read}。
 * 供应商是采购的前置主数据，只给药房主任与管理员维护，药师日常只读。
 */
@RestController
@RequestMapping("/pharmacy/suppliers")
public class SupplierController {

    private static final String MANAGE = "pharmacy:supplier:manage";
    private static final String READ = "pharmacy:purchase:read";

    private final SupplierService service;

    public SupplierController(SupplierService service) {
        this.service = service;
    }

    @PostMapping
    public ApiResponse<SupplierView> create(
            @Valid @RequestBody CreateSupplierReq req,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(value = AuthHeaders.ACTOR, defaultValue = "system") String actor) {
        Permissions.require(permissions, MANAGE);
        return ApiResponse.ok(service.create(req, actor));
    }

    @GetMapping
    public ApiResponse<List<SupplierView>> list(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Integer limit,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions) {
        Permissions.require(permissions, READ);
        return ApiResponse.ok(service.list(keyword, status, limit));
    }

    /** 启用/停用（必须带 version）：停用只影响新采购单，在途单据仍可验收。 */
    @PutMapping("/{id}/status")
    public ApiResponse<SupplierView> updateStatus(
            @PathVariable UUID id,
            @Valid @RequestBody SupplierStatusReq req,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(value = AuthHeaders.ACTOR, defaultValue = "system") String actor) {
        Permissions.require(permissions, MANAGE);
        return ApiResponse.ok(service.updateStatus(id, req.status(), req.version(), actor));
    }
}
