package com.medassist.pharmacy.web;

import com.medassist.common.api.ApiResponse;
import com.medassist.common.security.AuthHeaders;
import com.medassist.common.security.Permissions;
import com.medassist.pharmacy.domain.PharmacyModel.StockLoss;
import com.medassist.pharmacy.service.StockLossService;
import com.medassist.pharmacy.web.StockLossDtos.AutoDraftResult;
import com.medassist.pharmacy.web.StockLossDtos.CreateStockLossReq;
import com.medassist.pharmacy.web.StockLossDtos.ReviewStockLossReq;
import com.medassist.pharmacy.web.StockLossDtos.StockLossView;
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
 * 药品报损 API。
 *
 * <p>权限：查询 {@code pharmacy:read}；提交 {@code pharmacy:loss:write}；
 * 审批/驳回 {@code pharmacy:loss:approve}（审批人 ≠ 申请人，服务层强制）。
 */
@RestController
@RequestMapping("/pharmacy/stock-losses")
public class StockLossController {

    private static final String READ = "pharmacy:read";
    private static final String WRITE = "pharmacy:loss:write";
    private static final String APPROVE = "pharmacy:loss:approve";

    private final StockLossService service;

    public StockLossController(StockLossService service) {
        this.service = service;
    }

    @GetMapping
    public ApiResponse<List<StockLoss>> list(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Integer limit,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions) {
        Permissions.require(permissions, READ);
        return ApiResponse.ok(service.list(status, limit));
    }

    @GetMapping("/{id}")
    public ApiResponse<StockLossView> detail(
            @PathVariable UUID id,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions) {
        Permissions.require(permissions, READ);
        return ApiResponse.ok(service.detail(id));
    }

    @PostMapping
    public ApiResponse<StockLossView> create(
            @Valid @RequestBody CreateStockLossReq req,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(value = AuthHeaders.ACTOR, defaultValue = "system") String actor) {
        Permissions.require(permissions, WRITE);
        return ApiResponse.ok(service.create(req, actor));
    }

    /** 审批（approve=true）或驳回（approve=false，必须写原因）。 */
    @PostMapping("/{id}/review")
    public ApiResponse<StockLossView> review(
            @PathVariable UUID id,
            @Valid @RequestBody ReviewStockLossReq req,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(value = AuthHeaders.ACTOR, defaultValue = "system") String actor) {
        Permissions.require(permissions, APPROVE);
        return ApiResponse.ok(service.review(id, req, actor));
    }

    /** 提交草稿（DRAFT → SUBMITTED），提交前再次校验批次余量。 */
    @PostMapping("/{id}/submit")
    public ApiResponse<StockLossView> submit(
            @PathVariable UUID id,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(value = AuthHeaders.ACTOR, defaultValue = "system") String actor) {
        Permissions.require(permissions, WRITE);
        return ApiResponse.ok(service.submit(id, actor));
    }

    /** 手动触发效期自动草稿（定时任务同样调用；只生成 DRAFT，不扣库存）。 */
    @PostMapping("/auto-draft")
    public ApiResponse<AutoDraftResult> autoDraft(
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(value = AuthHeaders.ACTOR, defaultValue = "system") String actor) {
        Permissions.require(permissions, WRITE);
        return ApiResponse.ok(service.autoDraftExpired(actor));
    }
}
