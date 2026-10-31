package com.medassist.pharmacy.web;

import com.medassist.common.api.ApiResponse;
import com.medassist.common.security.AuthHeaders;
import com.medassist.common.security.Permissions;
import com.medassist.pharmacy.domain.PharmacyModel.DrugBatch;
import com.medassist.pharmacy.service.StockService;
import com.medassist.pharmacy.web.PharmacyDtos.ReceiveStockReq;
import com.medassist.pharmacy.web.PharmacyDtos.StockWarning;
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
 * 药品库存 API。
 *
 * <p>权限：{@code pharmacy:stock:manage}（入库）；查询用 {@code pharmacy:read}。
 */
@RestController
@RequestMapping("/pharmacy")
public class StockController {

    private static final String STOCK_MANAGE = "pharmacy:stock:manage";
    private static final String READ = "pharmacy:read";

    private final StockService service;

    public StockController(StockService service) {
        this.service = service;
    }

    /** 按批次入库（效期必须 ≥ 业务日）。 */
    @PostMapping("/drugs/{drugId}/batches")
    public ApiResponse<DrugBatch> receive(
            @PathVariable UUID drugId,
            @Valid @RequestBody ReceiveStockReq req,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(value = AuthHeaders.ACTOR, defaultValue = "system") String actor) {
        Permissions.require(permissions, STOCK_MANAGE);
        return ApiResponse.ok(service.receive(drugId, req, actor));
    }

    @GetMapping("/drugs/{drugId}/batches")
    public ApiResponse<List<DrugBatch>> batches(
            @PathVariable UUID drugId,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions) {
        Permissions.require(permissions, READ);
        return ApiResponse.ok(service.batches(drugId));
    }

    /** 近效期/已过期批次预警。 */
    @GetMapping("/stock-warnings")
    public ApiResponse<List<StockWarning>> warnings(
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions) {
        Permissions.require(permissions, READ);
        return ApiResponse.ok(service.warnings());
    }
}
