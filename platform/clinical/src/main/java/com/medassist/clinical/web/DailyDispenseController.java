package com.medassist.clinical.web;

import com.medassist.clinical.service.DailyDispenseService;
import com.medassist.clinical.web.ClinicalDtos.DailyDispenseReq;
import com.medassist.clinical.web.ClinicalDtos.DailyDispenseResult;
import com.medassist.common.api.ApiResponse;
import com.medassist.common.security.AuthHeaders;
import com.medassist.common.security.Permissions;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * 长期药品医嘱按日请领（病区护士/医师操作，B2 后续）。
 *
 * <p>请领一次 = 为当日所有有效长嘱药品各生成一张当日处方；药房按 {@code (orderId, date)}
 * 幂等，重复点击或网络重试不会多开处方。
 */
@RestController
@RequestMapping("/clinical/visits/{visitId}/daily-dispense-requests")
public class DailyDispenseController {

    private static final String ORDER_CREATE = "clinical:order:create";

    private final DailyDispenseService service;

    public DailyDispenseController(DailyDispenseService service) {
        this.service = service;
    }

    @PostMapping
    public ApiResponse<DailyDispenseResult> request(
            @PathVariable UUID visitId,
            @RequestBody(required = false) DailyDispenseReq req,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(value = AuthHeaders.ACTOR, defaultValue = "system") String actor) {
        Permissions.require(permissions, ORDER_CREATE);
        return ApiResponse.ok(service.request(visitId, req == null ? null : req.date(), actor));
    }
}
