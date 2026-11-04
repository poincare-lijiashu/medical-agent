package com.medassist.clinical.web;

import com.medassist.clinical.service.BillingService;
import com.medassist.clinical.web.ClinicalDtos.OutpatientDailyView;
import com.medassist.clinical.web.ClinicalDtos.OutpatientTrendView;
import com.medassist.common.api.ApiResponse;
import com.medassist.common.security.AuthHeaders;
import com.medassist.common.security.Permissions;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

/**
 * 门诊财务 API（G1）。
 *
 * <p>与 {@link BillingController} 分开：后者是"某个就诊的收费"（路径含 visitId，走
 * 就诊级科室权限），本控制器是**全院口径**的报表，不能用某个就诊的科室范围来约束，
 * 因此单独挂权限点 {@code clinical:charge:daily}。
 */
@RestController
@RequestMapping("/clinical/finance")
public class OutpatientFinanceController {

    private static final String DAILY_READ = "clinical:charge:daily";

    private final BillingService service;

    public OutpatientFinanceController(BillingService service) {
        this.service = service;
    }

    /**
     * 门诊日结：当日实收笔数/金额 + 科室分账 + 支付方式分账 + 当日退费额。
     *
     * @param date 业务日期，缺省为当天（按 {@code BusinessTime} 时区归日）
     */
    @GetMapping("/outpatient-daily")
    public ApiResponse<OutpatientDailyView> outpatientDaily(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions) {
        Permissions.require(permissions, DAILY_READ);
        return ApiResponse.ok(service.outpatientDaily(date));
    }

    /**
     * 门诊实收趋势：按收款日聚合，缺日补 0；返回上一等长周期合计用于环比。
     *
     * @param from 起始日期（含），缺省为 to 前 13 天
     * @param to   结束日期（含），缺省为当天
     */
    @GetMapping("/outpatient-trend")
    public ApiResponse<OutpatientTrendView> outpatientTrend(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions) {
        Permissions.require(permissions, DAILY_READ);
        return ApiResponse.ok(service.outpatientTrend(from, to));
    }
}
