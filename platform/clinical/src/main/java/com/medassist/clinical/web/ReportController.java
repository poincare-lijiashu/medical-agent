package com.medassist.clinical.web;

import com.medassist.clinical.domain.ReportModel.OperationsReport;
import com.medassist.common.api.ApiResponse;
import com.medassist.common.security.AuthHeaders;
import com.medassist.common.security.Permissions;
import com.medassist.clinical.service.ReportService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

/**
 * 运营指标 API（N7 第一批）。
 *
 * <p>权限：{@code clinical:report:read}（管理员 / 挂号收费 / 医师）；只读，不含患者级明细，
 * 因此不需要像 CDR 那样逐次留痕。
 */
@RestController
@RequestMapping("/clinical/reports")
public class ReportController {

    private static final String READ = "clinical:report:read";

    private final ReportService service;

    public ReportController(ReportService service) {
        this.service = service;
    }

    /** 运营报表：就诊人次、收费/实收/未结/退费、药占比、分类与科室收入。 */
    @GetMapping("/operations")
    public ApiResponse<OperationsReport> operations(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions) {
        Permissions.require(permissions, READ);
        return ApiResponse.ok(service.operations(from, to));
    }
}
