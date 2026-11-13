package com.medassist.insurance.web;

import com.medassist.common.api.ApiResponse;
import com.medassist.common.security.AuthHeaders;
import com.medassist.common.security.Permissions;
import com.medassist.insurance.domain.InsuranceModel.AllocationBillRow;
import com.medassist.insurance.domain.InsuranceModel.AllocationView;
import com.medassist.insurance.domain.InsuranceModel.PayerConfig;
import com.medassist.insurance.domain.InsuranceModel.SettlementAllocation;
import com.medassist.insurance.service.PayerAllocationService;
import com.medassist.insurance.web.InsuranceDtos.AllocateReq;
import com.medassist.insurance.web.InsuranceDtos.AllocationBillList;
import com.medassist.insurance.web.InsuranceDtos.AllocationRefundReq;
import com.medassist.insurance.web.InsuranceDtos.CreatePayerReq;
import com.medassist.insurance.web.InsuranceDtos.PayerToggleReq;
import com.medassist.insurance.web.InsuranceDtos.UpdatePayerReq;
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
 * 支付方配置与结算分摊（§292 / 计划书 W2.6）。
 *
 * <p>权限切分：
 * <ul>
 *   <li>{@code insurance:payer:read}：支付方字典、待分摊清单与分摊台账（医保办/运营/管理员）；</li>
 *   <li>{@code insurance:payer:manage}：维护支付方、首次分摊、退费（医保办/管理员）。</li>
 * </ul>
 *
 * <p>所有写操作必须带真实操作人并写审计；分摊台账只增不改，
 * 退费以 REFUND 记录追溯，不允许"覆盖式修改"。
 */
@RestController
@RequestMapping("/insurance")
public class PayerController {

    private static final String READ = "insurance:payer:read";
    private static final String MANAGE = "insurance:payer:manage";

    private final PayerAllocationService service;

    public PayerController(PayerAllocationService service) {
        this.service = service;
    }

    @GetMapping("/payers")
    public ApiResponse<List<PayerConfig>> listPayers(
            @RequestParam(defaultValue = "false") boolean enabledOnly,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions) {
        Permissions.require(permissions, READ);
        return ApiResponse.ok(service.listPayers(enabledOnly));
    }

    @PostMapping("/payers")
    public ApiResponse<PayerConfig> createPayer(
            @Valid @RequestBody CreatePayerReq req,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(value = AuthHeaders.ACTOR, required = false) String actor) {
        Permissions.require(permissions, MANAGE);
        return ApiResponse.ok(service.createPayer(req, actor));
    }

    @PutMapping("/payers/{code}")
    public ApiResponse<PayerConfig> updatePayer(
            @PathVariable String code,
            @Valid @RequestBody UpdatePayerReq req,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(value = AuthHeaders.ACTOR, required = false) String actor) {
        Permissions.require(permissions, MANAGE);
        return ApiResponse.ok(service.updatePayer(code, req, actor));
    }

    @PutMapping("/payers/{code}/enabled")
    public ApiResponse<PayerConfig> togglePayer(
            @PathVariable String code,
            @Valid @RequestBody PayerToggleReq req,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(value = AuthHeaders.ACTOR, required = false) String actor) {
        Permissions.require(permissions, MANAGE);
        return ApiResponse.ok(service.togglePayer(code, req.enabled(), req.version(), actor));
    }

    /** 待分摊（pending=true）或最近已分摊的已确认清单。 */
    @GetMapping("/allocations/bills")
    public ApiResponse<AllocationBillList> listBills(
            @RequestParam(defaultValue = "true") boolean pending,
            @RequestParam(defaultValue = "50") int limit,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions) {
        Permissions.require(permissions, READ);
        List<AllocationBillRow> items = service.listBills(pending, limit);
        return ApiResponse.ok(new AllocationBillList(items));
    }

    /** 单张清单分摊视图：清单 + 台账 + 支付方汇总。 */
    @GetMapping("/allocations/bills/{billId}")
    public ApiResponse<AllocationView> view(
            @PathVariable UUID billId,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions) {
        Permissions.require(permissions, READ);
        return ApiResponse.ok(service.view(billId));
    }

    /** 首次分摊：各支付方金额合计必须等于清单总额。 */
    @PostMapping("/allocations/bills/{billId}")
    public ApiResponse<AllocationView> allocate(
            @PathVariable UUID billId,
            @Valid @RequestBody AllocateReq req,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(value = AuthHeaders.ACTOR, required = false) String actor) {
        Permissions.require(permissions, MANAGE);
        return ApiResponse.ok(service.allocate(billId, req.items(), actor));
    }

    /** 退费：追加 REFUND 台账，不得超过原分摊余额。 */
    @PostMapping("/allocations/{id}/refund")
    public ApiResponse<SettlementAllocation> refund(
            @PathVariable UUID id,
            @Valid @RequestBody AllocationRefundReq req,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(value = AuthHeaders.ACTOR, required = false) String actor) {
        Permissions.require(permissions, MANAGE);
        return ApiResponse.ok(service.refund(id, req, actor));
    }
}
