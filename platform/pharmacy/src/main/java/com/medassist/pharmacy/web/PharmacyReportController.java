package com.medassist.pharmacy.web;

import com.medassist.common.api.ApiResponse;
import com.medassist.common.security.AuthHeaders;
import com.medassist.common.security.Permissions;
import com.medassist.pharmacy.service.PharmacyReportService;
import com.medassist.pharmacy.web.PharmacyReportDtos.DispenseTrendView;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

/**
 * 药事报表 API（G15）：只读聚合，权限沿用 {@code pharmacy:read}。
 */
@RestController
@RequestMapping("/pharmacy/reports")
public class PharmacyReportController {

    private static final String READ = "pharmacy:read";

    private final PharmacyReportService service;

    public PharmacyReportController(PharmacyReportService service) {
        this.service = service;
    }

    /** 发药日趋势：默认最近 14 天，最多 92 天；缺日补 0。 */
    @GetMapping("/dispense-trend")
    public ApiResponse<DispenseTrendView> dispenseTrend(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions) {
        Permissions.require(permissions, READ);
        return ApiResponse.ok(service.dispenseTrend(from, to));
    }
}
