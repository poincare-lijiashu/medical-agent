package com.medassist.clinical.web;

import com.medassist.clinical.domain.ClinicalModel.OperationLevel;
import com.medassist.clinical.domain.ClinicalModel.OperationLevelRow;
import com.medassist.clinical.service.OperationLevelCatalogService;
import com.medassist.clinical.web.SurgeryLevelDtos.UpsertOperationLevelReq;
import com.medassist.common.api.ApiResponse;
import com.medassist.common.security.AuthHeaders;
import com.medassist.common.security.Permissions;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 手术分级目录 API（G81）：术种 → 默认分级。
 *
 * <p>读写权限分离：读随手术记录（临床岗位都能查），写单列
 * {@code clinical:surgery:catalog:write}（全院口径的维护，仅医务/管理员）。
 * 能录手术（{@code clinical:surgery:write}）不等于能改全院默认分级。
 */
@RestController
@RequestMapping("/clinical/operation-levels")
public class OperationLevelController {

    private static final String READ = "clinical:surgery:read";
    private static final String WRITE = "clinical:surgery:catalog:write";

    private final OperationLevelCatalogService service;

    public OperationLevelController(OperationLevelCatalogService service) {
        this.service = service;
    }

    @GetMapping
    public ApiResponse<List<OperationLevelRow>> list(
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "100") int limit,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions) {
        Permissions.require(permissions, READ);
        return ApiResponse.ok(service.list(keyword, limit));
    }

    @PutMapping("/{code}")
    public ApiResponse<OperationLevel> upsert(
            @PathVariable String code, @Valid @RequestBody UpsertOperationLevelReq req,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(value = AuthHeaders.ACTOR, defaultValue = "system") String actor) {
        Permissions.require(permissions, WRITE);
        return ApiResponse.ok(service.upsert(code, req, actor));
    }

    @DeleteMapping("/{code}")
    public ApiResponse<Void> delete(
            @PathVariable String code, @RequestParam long version,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(value = AuthHeaders.ACTOR, defaultValue = "system") String actor) {
        Permissions.require(permissions, WRITE);
        service.delete(code, version, actor);
        return ApiResponse.ok(null);
    }
}
