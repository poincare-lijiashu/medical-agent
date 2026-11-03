package com.medassist.clinical.web;

import com.medassist.clinical.service.BillingService;
import com.medassist.clinical.service.VisitAccessGuard;
import com.medassist.clinical.web.ClinicalDtos.BillingPreview;
import com.medassist.clinical.web.ClinicalDtos.ChargeView;
import com.medassist.clinical.web.ClinicalDtos.DailyBillResult;
import com.medassist.clinical.web.ClinicalDtos.DailyReversalResult;
import com.medassist.clinical.web.ClinicalDtos.DailyReverseReq;
import com.medassist.clinical.web.ClinicalDtos.SettleReq;
import com.medassist.common.api.ApiResponse;
import com.medassist.common.security.AuthHeaders;
import com.medassist.common.security.DataScope;
import com.medassist.common.security.Permissions;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.UUID;

/**
 * 门诊计费 API。
 *
 * <p>幂等：{@code POST /settle} 对已收费的就诊返回原收费单（200），收费窗口重复点击不会多收。
 */
@RestController
@RequestMapping("/clinical/visits/{visitId}")
public class BillingController {

    private static final String CHARGE_READ = "clinical:charge:read";
    private static final String CHARGE_SETTLE = "clinical:charge:settle";

    private final BillingService service;
    private final VisitAccessGuard guard;

    public BillingController(BillingService service, VisitAccessGuard guard) {
        this.service = service;
        this.guard = guard;
    }

    @GetMapping("/billing/preview")
    public ApiResponse<BillingPreview> preview(
            @PathVariable UUID visitId,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            DataScope scope) {
        Permissions.require(permissions, CHARGE_READ);
        // 只读：会诊医师需要看费用摘要来给意见，但结算仍按主诊科室权限
        guard.requireRead(scope, visitId);
        return ApiResponse.ok(service.preview(visitId));
    }

    @GetMapping("/charge")
    public ApiResponse<ChargeView> charge(
            @PathVariable UUID visitId,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            DataScope scope) {
        Permissions.require(permissions, CHARGE_READ);
        // 只读：会诊医师可看收费单明细，不可结算
        guard.requireRead(scope, visitId);
        return ApiResponse.ok(service.chargeOf(visitId));
    }

    @PostMapping("/settle")
    public ApiResponse<ChargeView> settle(
            @PathVariable UUID visitId,
            @Valid @RequestBody SettleReq req,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(value = AuthHeaders.ACTOR, defaultValue = "system") String actor,
            DataScope scope) {
        Permissions.require(permissions, CHARGE_SETTLE);
        guard.require(scope, visitId);
        return ApiResponse.ok(service.settle(visitId, req.paymentMethod(), actor));
    }

    /**
     * 住院长嘱按日计费（可重复调用：同一天同一长嘱只计一次）。
     *
     * @param date 计费日，缺省为当天；补计历史日期由住院处按实际执行日传入
     */
    @PostMapping("/daily-bill")
    public ApiResponse<DailyBillResult> dailyBill(
            @PathVariable UUID visitId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(value = AuthHeaders.ACTOR, defaultValue = "system") String actor,
            DataScope scope) {
        Permissions.require(permissions, "inpatient:billing:daily");
        guard.require(scope, visitId);
        return ApiResponse.ok(service.dailyBill(visitId, date, actor));
    }

    /**
     * 按日计费红冲（P1-1）：护理把当日改判为"未执行/患者拒绝"后，把当日费用冲回。
     *
     * <p>必须二次确认 + 原因（资金动作，误操作只能靠审计追溯）；同一医嘱同一天幂等。
     * 已结算的日期不允许直接红冲，需走退费流程。
     */
    @PostMapping("/daily-bill/reverse")
    public ApiResponse<DailyReversalResult> reverseDaily(
            @PathVariable UUID visitId,
            @Valid @RequestBody DailyReverseReq req,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(value = AuthHeaders.ACTOR, defaultValue = "system") String actor,
            DataScope scope) {
        Permissions.require(permissions, "inpatient:billing:daily");
        guard.require(scope, visitId);
        return ApiResponse.ok(service.reverseDaily(visitId, req.orderId(), req.serviceDate(),
                req.reason(), Boolean.TRUE.equals(req.confirmed()), actor));
    }
}
