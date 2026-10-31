package com.medassist.insurance.web;

import com.medassist.common.api.ApiResponse;
import com.medassist.common.security.AuthHeaders;
import com.medassist.common.security.Permissions;
import com.medassist.insurance.domain.InsuranceModel.ChargeMapping;
import com.medassist.insurance.domain.InsuranceModel.InsuranceItem;
import com.medassist.insurance.service.InsuranceService;
import com.medassist.insurance.web.InsuranceDtos.ImportItemsReq;
import com.medassist.insurance.web.InsuranceDtos.ImportResult;
import com.medassist.insurance.web.InsuranceDtos.ItemReq;
import com.medassist.insurance.web.InsuranceDtos.MappingReq;
import com.medassist.insurance.web.InsuranceDtos.ToggleReq;
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

/** 医保目录与院内项目映射。 */
@RestController
@RequestMapping("/insurance")
public class CatalogController {

    private static final String MANAGE = "insurance:catalog:manage";
    private static final String READ = "insurance:settlement:read";

    private final InsuranceService service;

    public CatalogController(InsuranceService service) {
        this.service = service;
    }

    @GetMapping("/items")
    public ApiResponse<List<InsuranceItem>> items(
            @RequestParam(required = false) String category,
            @RequestParam(required = false) Boolean enabled,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) Integer limit,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions) {
        Permissions.require(permissions, READ);
        return ApiResponse.ok(service.listItems(category, enabled, q, limit));
    }

    @PostMapping("/items")
    public ApiResponse<InsuranceItem> create(
            @Valid @RequestBody ItemReq req,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(value = AuthHeaders.ACTOR, defaultValue = "system") String actor) {
        Permissions.require(permissions, MANAGE);
        return ApiResponse.ok(service.createItem(req, actor));
    }

    /** 批量导入（按医保编码 UPSERT；供省级目录下载后灌库）。 */
    @PostMapping("/items/import")
    public ApiResponse<ImportResult> importItems(
            @Valid @RequestBody ImportItemsReq req,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(value = AuthHeaders.ACTOR, defaultValue = "system") String actor) {
        Permissions.require(permissions, MANAGE);
        return ApiResponse.ok(service.importItems(req.items(), actor));
    }

    @PutMapping("/items/{id}/enabled")
    public ApiResponse<InsuranceItem> toggle(
            @PathVariable UUID id,
            @Valid @RequestBody ToggleReq req,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(value = AuthHeaders.ACTOR, defaultValue = "system") String actor) {
        Permissions.require(permissions, MANAGE);
        return ApiResponse.ok(service.toggleItem(id, req.enabled(), req.version(), actor));
    }

    @GetMapping("/mappings")
    public ApiResponse<List<ChargeMapping>> mappings(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) Integer limit,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions) {
        Permissions.require(permissions, READ);
        return ApiResponse.ok(service.listMappings(q, limit));
    }

    /** 新增/更新一个院内收费项目的医保映射。 */
    @PutMapping("/mappings/{chargeItemCode}")
    public ApiResponse<ChargeMapping> upsertMapping(
            @PathVariable String chargeItemCode,
            @Valid @RequestBody MappingReq req,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(value = AuthHeaders.ACTOR, defaultValue = "system") String actor) {
        Permissions.require(permissions, MANAGE);
        return ApiResponse.ok(service.upsertMapping(chargeItemCode, req, actor));
    }
}
