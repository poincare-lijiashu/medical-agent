package com.medassist.material.web;

import com.medassist.common.api.ApiResponse;
import com.medassist.common.security.AuthHeaders;
import com.medassist.common.security.Permissions;
import com.medassist.material.domain.MaterialModel.ConsumableBatch;
import com.medassist.material.service.ConsumableStockService;
import com.medassist.material.web.MaterialDtos.ReceiveBatchReq;
import com.medassist.material.web.MaterialDtos.StockWarning;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * 耗材批次库存 API。
 *
 * <p>权限：{@code material:stock:manage}（入库）；查询用 {@code material:read}。
 */
@RestController
@RequestMapping("/material")
public class MaterialStockController {

    private static final String STOCK = "material:stock:manage";
    private static final String READ = "material:read";

    private final ConsumableStockService service;

    public MaterialStockController(ConsumableStockService service) {
        this.service = service;
    }

    /** 按批次入库；高值/植入类必须带有效期。 */
    @PostMapping("/consumables/{consumableId}/batches")
    public ApiResponse<ConsumableBatch> receive(
            @PathVariable UUID consumableId,
            @Valid @RequestBody ReceiveBatchReq req,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(value = AuthHeaders.ACTOR, defaultValue = "system") String actor) {
        Permissions.require(permissions, STOCK);
        return ApiResponse.ok(service.receive(consumableId, req, actor));
    }

    @GetMapping("/consumables/{consumableId}/batches")
    public ApiResponse<List<ConsumableBatch>> batches(
            @PathVariable UUID consumableId,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions) {
        Permissions.require(permissions, READ);
        return ApiResponse.ok(service.batches(consumableId));
    }

    /** 近效期/已过期批次预警。 */
    @GetMapping("/stock-warnings")
    public ApiResponse<List<StockWarning>> warnings(
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions) {
        Permissions.require(permissions, READ);
        return ApiResponse.ok(service.warnings());
    }
}
