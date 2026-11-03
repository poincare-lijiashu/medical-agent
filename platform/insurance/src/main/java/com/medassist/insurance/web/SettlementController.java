package com.medassist.insurance.web;

import com.medassist.common.api.ApiResponse;
import com.medassist.common.security.AuthHeaders;
import com.medassist.common.security.Permissions;
import com.medassist.insurance.domain.InsuranceModel.SettlementBill;
import com.medassist.insurance.service.InsuranceService;
import com.medassist.insurance.web.InsuranceDtos.BillView;
import com.medassist.insurance.web.InsuranceDtos.GenerateBillReq;
import com.medassist.insurance.web.InsuranceDtos.ReconcileView;
import com.medassist.insurance.web.InsuranceDtos.VoidReq;
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
 * 医保结算清单。
 *
 * <p>生成时从临床域拉收费明细并本地测算；确认后清单不可再改。
 *
 * <p>职责分离：窗口（REGISTRAR）只能生成，确认/作废需要独立的
 * {@code insurance:settlement:confirm}（医保办/管理员），避免"一个人改目录→出单→确认"全链闭环。
 */
@RestController
@RequestMapping("/insurance/settlements")
public class SettlementController {

    private static final String WRITE = "insurance:settlement:write";
    private static final String CONFIRM = "insurance:settlement:confirm";
    private static final String READ = "insurance:settlement:read";

    private final InsuranceService service;

    public SettlementController(InsuranceService service) {
        this.service = service;
    }

    /** 生成或重算（DRAFT 可重算，CONFIRMED 拒绝）。 */
    @PostMapping
    public ApiResponse<BillView> generate(
            @Valid @RequestBody GenerateBillReq req,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(value = AuthHeaders.ACTOR, defaultValue = "system") String actor) {
        Permissions.require(permissions, WRITE);
        return ApiResponse.ok(service.generate(req.visitId(), actor));
    }

    @GetMapping("/{id}")
    public ApiResponse<BillView> detail(
            @PathVariable UUID id,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            com.medassist.common.security.DataScope scope) {
        Permissions.require(permissions, READ);
        return ApiResponse.ok(maskBill(service.detail(id), scope));
    }

    @GetMapping("/by-visit/{visitId}")
    public ApiResponse<BillView> byVisit(
            @PathVariable UUID visitId,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            com.medassist.common.security.DataScope scope) {
        Permissions.require(permissions, READ);
        return ApiResponse.ok(maskBill(service.byVisit(visitId), scope));
    }

    @PostMapping("/{id}/confirm")
    public ApiResponse<BillView> confirm(
            @PathVariable UUID id,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(value = AuthHeaders.ACTOR, defaultValue = "system") String actor) {
        Permissions.require(permissions, CONFIRM);
        return ApiResponse.ok(service.confirm(id, actor));
    }

    /** 作废（红冲）：已确认清单单向作废，作废后该就诊可重新生成。 */
    @PostMapping("/{id}/void")
    public ApiResponse<BillView> voidBill(
            @PathVariable UUID id,
            @Valid @RequestBody VoidReq req,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(value = AuthHeaders.ACTOR, defaultValue = "system") String actor) {
        Permissions.require(permissions, CONFIRM);
        return ApiResponse.ok(service.voidBill(id, req.reason(), actor));
    }

    /** 按就诊对账：清单金额 vs 当前收费重算结果。 */
    @GetMapping("/reconcile/{visitId}")
    public ApiResponse<ReconcileView> reconcile(
            @PathVariable UUID visitId,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions) {
        Permissions.require(permissions, READ);
        return ApiResponse.ok(service.reconcile(visitId));
    }

    @GetMapping
    public ApiResponse<List<SettlementBill>> list(
            @RequestParam(required = false) UUID patientId,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Integer limit,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            com.medassist.common.security.DataScope scope) {
        Permissions.require(permissions, READ);
        return ApiResponse.ok(PhiMask.bills(service.list(patientId, status, limit), scope));
    }

    /** BillView 里的清单抬头含患者号，按 phi:read 决定掩码（P0-S2）。 */
    private static BillView maskBill(BillView view, com.medassist.common.security.DataScope scope) {
        if (view == null || scope.phiReadable()) {
            return view;
        }
        return new BillView(PhiMask.bill(view.bill(), scope), view.lines(),
                view.capUsed(), view.capRemaining());
    }
}
