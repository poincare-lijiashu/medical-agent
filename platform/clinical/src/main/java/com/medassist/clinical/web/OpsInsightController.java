package com.medassist.clinical.web;

import com.medassist.clinical.service.OpsInsightService;
import com.medassist.clinical.web.OpsInsightDtos.OpsInsightView;
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

/** 运营洞察 API（G27）：同比与科室/病区下钻，只读。 */
@RestController
@RequestMapping("/clinical/reports")
public class OpsInsightController {

    private static final String READ = "clinical:report:read";

    private final OpsInsightService service;

    public OpsInsightController(OpsInsightService service) {
        this.service = service;
    }

    /** 门诊按科室、住院按病区的同比洞察；默认最近 14 天。 */
    @GetMapping("/ops-insights")
    public ApiResponse<OpsInsightView> insights(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            LocalDate to,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions) {
        Permissions.require(permissions, READ);
        return ApiResponse.ok(service.insight(from, to));
    }
}
