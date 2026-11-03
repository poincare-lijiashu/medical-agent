package com.medassist.pharmacy.web;

import com.medassist.common.api.ApiResponse;
import com.medassist.common.security.AuthHeaders;
import com.medassist.common.security.Permissions;
import com.medassist.pharmacy.service.ProcurementService;
import com.medassist.pharmacy.web.PurchaseDtos.CancelOrderReq;
import com.medassist.pharmacy.web.PurchaseDtos.CreatePurchaseOrderReq;
import com.medassist.pharmacy.web.PurchaseDtos.CreateReceiptReq;
import com.medassist.pharmacy.web.PurchaseDtos.GoodsReceiptView;
import com.medassist.pharmacy.web.PurchaseDtos.OrderVersionReq;
import com.medassist.pharmacy.web.PurchaseDtos.ProcurementSummary;
import com.medassist.pharmacy.web.PurchaseDtos.PurchaseOrderSummary;
import com.medassist.pharmacy.web.PurchaseDtos.PurchaseOrderView;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * 药品采购与到货验收 API。
 *
 * <p>权限分权（与药事管理一致）：
 * <ul>
 *   <li>{@code pharmacy:purchase:read}：查看供应商/采购单/验收单；</li>
 *   <li>{@code pharmacy:purchase:write}：建采购单（草稿）；</li>
 *   <li>{@code pharmacy:purchase:approve}：提交与取消（审批动作）；</li>
 *   <li>{@code pharmacy:receipt:write}：到货验收入库（直接改库存，风险最高）。</li>
 * </ul>
 */
@RestController
@RequestMapping("/pharmacy")
public class PurchaseController {

    private static final String READ = "pharmacy:purchase:read";
    private static final String WRITE = "pharmacy:purchase:write";
    private static final String APPROVE = "pharmacy:purchase:approve";
    private static final String RECEIPT = "pharmacy:receipt:write";

    private final ProcurementService service;

    public PurchaseController(ProcurementService service) {
        this.service = service;
    }

    // ---------------------------------------------------------------- 采购单

    @PostMapping("/purchase-orders")
    public ApiResponse<PurchaseOrderView> create(
            @Valid @RequestBody CreatePurchaseOrderReq req,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(value = AuthHeaders.ACTOR, defaultValue = "system") String actor) {
        Permissions.require(permissions, WRITE);
        return ApiResponse.ok(service.createOrder(req, actor));
    }

    @GetMapping("/purchase-orders")
    public ApiResponse<List<PurchaseOrderSummary>> list(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) UUID supplierId,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) Integer limit,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions) {
        Permissions.require(permissions, READ);
        return ApiResponse.ok(service.list(status, supplierId, keyword, limit));
    }

    /** 采购看板（KPI 走聚合，不受列表条数上限影响）。 */
    @GetMapping("/purchase-orders/summary")
    public ApiResponse<ProcurementSummary> summary(
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions) {
        Permissions.require(permissions, READ);
        return ApiResponse.ok(service.summary());
    }

    @GetMapping("/purchase-orders/{id}")
    public ApiResponse<PurchaseOrderView> detail(
            @PathVariable UUID id,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions) {
        Permissions.require(permissions, READ);
        return ApiResponse.ok(service.detail(id));
    }

    /** 提交采购单（草稿 → 已提交），必须带 version 防并发覆盖。 */
    @PostMapping("/purchase-orders/{id}/submit")
    public ApiResponse<PurchaseOrderView> submit(
            @PathVariable UUID id,
            @Valid @RequestBody OrderVersionReq req,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(value = AuthHeaders.ACTOR, defaultValue = "system") String actor) {
        Permissions.require(permissions, APPROVE);
        return ApiResponse.ok(service.submit(id, req.version(), actor));
    }

    /** 取消采购单（仅草稿/已提交；已到货单必须走退货或报损）。 */
    @PostMapping("/purchase-orders/{id}/cancel")
    public ApiResponse<PurchaseOrderView> cancel(
            @PathVariable UUID id,
            @Valid @RequestBody CancelOrderReq req,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(value = AuthHeaders.ACTOR, defaultValue = "system") String actor) {
        Permissions.require(permissions, APPROVE);
        return ApiResponse.ok(service.cancel(id, req, actor));
    }

    // ---------------------------------------------------------------- 到货验收

    /** 到货验收：一次提交即入库；requestId 为幂等键，重试不会重复加库存。 */
    @PostMapping("/purchase-orders/{id}/receipts")
    public ApiResponse<GoodsReceiptView> receive(
            @PathVariable UUID id,
            @Valid @RequestBody CreateReceiptReq req,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(value = AuthHeaders.ACTOR, defaultValue = "system") String actor) {
        Permissions.require(permissions, RECEIPT);
        return ApiResponse.ok(service.receive(id, req, actor));
    }

    @GetMapping("/purchase-orders/{id}/receipts")
    public ApiResponse<List<GoodsReceiptView>> receipts(
            @PathVariable UUID id,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions) {
        Permissions.require(permissions, READ);
        return ApiResponse.ok(service.receipts(id));
    }

    @GetMapping("/goods-receipts/{id}")
    public ApiResponse<GoodsReceiptView> receipt(
            @PathVariable UUID id,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions) {
        Permissions.require(permissions, READ);
        return ApiResponse.ok(service.receipt(id));
    }
}
