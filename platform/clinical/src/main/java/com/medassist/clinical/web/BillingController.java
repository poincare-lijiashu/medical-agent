package com.medassist.clinical.web;

import com.medassist.clinical.service.BillingService;
import com.medassist.clinical.web.ClinicalDtos.BillingPreview;
import com.medassist.clinical.web.ClinicalDtos.ChargeView;
import com.medassist.clinical.web.ClinicalDtos.DailyBillResult;
import com.medassist.clinical.web.ClinicalDtos.SettleReq;
import com.medassist.common.api.ApiResponse;
import com.medassist.common.security.AuthHeaders;
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

    public BillingController(BillingService service) {
        this.service = service;
    }

    @GetMapping("/billing/preview")
    public ApiResponse<BillingPreview> preview(
            @PathVariable UUID visitId,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions) {
        Permissions.require(permissions, CHARGE_READ);
        return ApiResponse.ok(service.preview(visitId));
    }

    @GetMapping("/charge")
    public ApiResponse<ChargeView> charge(
            @PathVariable UUID visitId,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions) {
        Permissions.require(permissions, CHARGE_READ);
        return ApiResponse.ok(service.chargeOf(visitId));
    }

    @PostMapping("/settle")
    public ApiResponse<ChargeView> settle(
            @PathVariable UUID visitId,
            @Valid @RequestBody SettleReq req,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(value = AuthHeaders.ACTOR, defaultValue = "system") String actor) {
        Permissions.require(permissions, CHARGE_SETTLE);
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
            @RequestHeader(value = AuthHeaders.ACTOR, defaultValue = "system") String actor) {
        Permissions.require(permissions, "inpatient:billing:daily");
        return ApiResponse.ok(service.dailyBill(visitId, date, actor));
    }
}
