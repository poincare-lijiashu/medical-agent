package com.medassist.clinical.web;

import com.medassist.clinical.service.InpatientReportService;
import com.medassist.clinical.web.ClinicalDtos.InpatientTrendView;
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
 * 住院报表 API（G16）。
 *
 * <p>全院口径的周转报表，不套用单个就诊/病区的数据范围，单独挂
 * {@code inpatient:report:read}；病区级明细仍走 {@link InpatientController} 的
 * {@code inpatient:read} + 数据范围校验。
 */
@RestController
@RequestMapping("/clinical/reports")
public class InpatientReportController {

    private static final String REPORT_READ = "inpatient:report:read";

    private final InpatientReportService service;

    public InpatientReportController(InpatientReportService service) {
        this.service = service;
    }

    /**
     * 住院周转趋势：按业务日返回入院数、出院数、在院人数与出院者平均住院日。
     *
     * @param from 起始日期（含），缺省为 to 前 13 天
     * @param to   结束日期（含），缺省为当天
     */
    @GetMapping("/inpatient-trend")
    public ApiResponse<InpatientTrendView> inpatientTrend(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions) {
        Permissions.require(permissions, REPORT_READ);
        return ApiResponse.ok(service.trend(from, to));
    }
}
