package com.medassist.pharmacy.web;

import com.medassist.common.api.ApiResponse;
import com.medassist.common.security.AuthHeaders;
import com.medassist.common.security.Permissions;
import com.medassist.pharmacy.domain.PharmacyModel.StockTake;
import com.medassist.pharmacy.domain.PharmacyModel.StockTakeItem;
import com.medassist.pharmacy.service.StockTakeService;
import com.medassist.pharmacy.web.StockTakeDtos.CancelStockTakeReq;
import com.medassist.pharmacy.web.StockTakeDtos.CountItemReq;
import com.medassist.pharmacy.web.StockTakeDtos.CreateStockTakeReq;
import com.medassist.pharmacy.web.StockTakeDtos.StockTakeDetail;
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
 * 药品盘点 API。
 *
 * <p>权限：查询 {@code pharmacy:read}；建单/实盘/提交/取消 {@code pharmacy:stock:take}；
 * 审批 {@code pharmacy:stock:approve}（审批人 ≠ 建单人，服务层强制）。
 */
@RestController
@RequestMapping("/pharmacy/stock-takes")
public class StockTakeController {

    private static final String READ = "pharmacy:read";
    private static final String TAKE = "pharmacy:stock:take";
    private static final String APPROVE = "pharmacy:stock:approve";

    private final StockTakeService service;

    public StockTakeController(StockTakeService service) {
        this.service = service;
    }

    @GetMapping
    public ApiResponse<List<StockTake>> list(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Integer limit,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions) {
        Permissions.require(permissions, READ);
        return ApiResponse.ok(service.list(status, limit));
    }

    @GetMapping("/{id}")
    public ApiResponse<StockTakeDetail> detail(
            @PathVariable UUID id,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions) {
        Permissions.require(permissions, READ);
        return ApiResponse.ok(service.detail(id));
    }

    /** 建单：快照当前所有有余量的批次（可按药品/批号缩小范围）。 */
    @PostMapping
    public ApiResponse<StockTakeDetail> create(
            @Valid @RequestBody(required = false) CreateStockTakeReq req,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(value = AuthHeaders.ACTOR, defaultValue = "system") String actor) {
        Permissions.require(permissions, TAKE);
        return ApiResponse.ok(service.create(req, actor));
    }

    @PutMapping("/{id}/items/{itemId}")
    public ApiResponse<StockTakeItem> count(
            @PathVariable UUID id,
            @PathVariable UUID itemId,
            @Valid @RequestBody CountItemReq req,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(value = AuthHeaders.ACTOR, defaultValue = "system") String actor) {
        Permissions.require(permissions, TAKE);
        return ApiResponse.ok(service.count(id, itemId, req, actor));
    }

    @PostMapping("/{id}/submit")
    public ApiResponse<StockTakeDetail> submit(
            @PathVariable UUID id,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(value = AuthHeaders.ACTOR, defaultValue = "system") String actor) {
        Permissions.require(permissions, TAKE);
        return ApiResponse.ok(service.submit(id, actor));
    }

    @PostMapping("/{id}/approve")
    public ApiResponse<StockTakeDetail> approve(
            @PathVariable UUID id,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(value = AuthHeaders.ACTOR, defaultValue = "system") String actor) {
        Permissions.require(permissions, APPROVE);
        return ApiResponse.ok(service.approve(id, actor));
    }

    @PostMapping("/{id}/cancel")
    public ApiResponse<StockTakeDetail> cancel(
            @PathVariable UUID id,
            @Valid @RequestBody CancelStockTakeReq req,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(value = AuthHeaders.ACTOR, defaultValue = "system") String actor) {
        Permissions.require(permissions, TAKE);
        return ApiResponse.ok(service.cancel(id, req, actor));
    }
}
