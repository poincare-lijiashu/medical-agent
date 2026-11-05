package com.medassist.pharmacy.web;

import com.medassist.common.api.ApiResponse;
import com.medassist.common.security.AuthHeaders;
import com.medassist.common.security.DataScope;
import com.medassist.common.security.Permissions;
import com.medassist.pharmacy.service.PharmacyBillingService;
import com.medassist.pharmacy.service.PrescriptionAccessGuard;
import com.medassist.pharmacy.service.PrescriptionService;
import com.medassist.pharmacy.web.PharmacyDtos.DispenseView;
import com.medassist.pharmacy.web.PharmacyDtos.ReturnReq;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * 发药单查询与退药 API。
 *
 * <p>权限：查询 {@code pharmacy:read}；退药 {@code pharmacy:dispense:return}。
 * 三个接口统一过 {@link PrescriptionAccessGuard#requireDispense}：
 * 发药单是就诊级财务数据，跨科可见（药师岗位）但**不跨院区**。
 */
@RestController
@RequestMapping("/pharmacy/dispenses")
public class DispenseController {

    private static final String READ = "pharmacy:read";
    private static final String RETURN = "pharmacy:dispense:return";
    private static final String EXECUTE = "pharmacy:dispense:execute";

    private final PrescriptionService service;
    private final PharmacyBillingService billing;
    private final PrescriptionAccessGuard guard;

    public DispenseController(PrescriptionService service, PharmacyBillingService billing,
                              PrescriptionAccessGuard guard) {
        this.service = service;
        this.billing = billing;
        this.guard = guard;
    }

    @GetMapping("/{id}")
    public ApiResponse<DispenseView> detail(
            @PathVariable UUID id,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            DataScope scope) {
        Permissions.require(permissions, READ);
        guard.requireDispense(scope, id);
        return ApiResponse.ok(service.dispenseDetail(id));
    }

    /** 退药：数量不得超过该明细已发未退数量，回补原批次。 */
    @PostMapping("/{id}/returns")
    public ApiResponse<DispenseView> returnDrug(
            @PathVariable UUID id,
            @Valid @RequestBody ReturnReq req,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(value = AuthHeaders.ACTOR, defaultValue = "system") String actor,
            DataScope scope) {
        Permissions.require(permissions, RETURN);
        guard.requireDispense(scope, id);
        return ApiResponse.ok(service.returnDrug(id, req, actor));
    }

    /**
     * 发药计费补偿重试（仅 billing_mode=DISPENSE 的处方）。
     *
     * <p>收费域抖动时发药单会置 {@code CHARGE_FAILED / REFUND_FAILED}，由药房窗口或运维重试；
     * 临床侧按发药明细 id 幂等，重复调用不会重复记账。
     */
    @PostMapping("/{id}/billing-retry")
    public ApiResponse<DispenseView> retryBilling(
            @PathVariable UUID id,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(value = AuthHeaders.ACTOR, defaultValue = "system") String actor,
            DataScope scope) {
        Permissions.require(permissions, EXECUTE);
        guard.requireDispense(scope, id);
        billing.retry(id, actor);
        return ApiResponse.ok(service.dispenseDetail(id));
    }
}
