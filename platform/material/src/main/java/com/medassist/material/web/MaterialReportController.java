package com.medassist.material.web;

import com.medassist.common.api.ApiResponse;
import com.medassist.common.security.AuthHeaders;
import com.medassist.common.security.Permissions;
import com.medassist.material.service.MaterialReportService;
import com.medassist.material.web.MaterialReportDtos.IssueTrendView;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

/**
 * 物资报表 API（G15）：只读聚合，权限沿用 {@code material:read}。
 */
@RestController
@RequestMapping("/material/reports")
public class MaterialReportController {

    private static final String READ = "material:read";

    private final MaterialReportService service;

    public MaterialReportController(MaterialReportService service) {
        this.service = service;
    }

    /** 出库日趋势：默认最近 14 天，最多 92 天；缺日补 0。 */
    @GetMapping("/issue-trend")
    public ApiResponse<IssueTrendView> issueTrend(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions) {
        Permissions.require(permissions, READ);
        return ApiResponse.ok(service.issueTrend(from, to));
    }
}
