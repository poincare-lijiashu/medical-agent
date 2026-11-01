package com.medassist.clinical.web;

import com.medassist.clinical.service.SurgeryChargeService;
import com.medassist.clinical.web.SurgeryChargeDtos.SurgeryChargePreview;
import com.medassist.clinical.web.SurgeryChargeDtos.SurgeryChargeResult;
import com.medassist.common.api.ApiResponse;
import com.medassist.common.security.AuthHeaders;
import com.medassist.common.security.Permissions;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * 术中收费打包 API（手术费 / 麻醉费 / 耗材）。
 *
 * <p>权限：预览用 {@code clinical:charge:read}，计费用 {@code clinical:charge:settle}。
 * 刻意**不用** {@code clinical:surgery:write}：能开刀的医师不等于能记账，
 * 计费动作与收费处/护士站同权限，避免术者自行改价改账。
 *
 * <p>计费接口幂等：重复调用返回 200 且 {@code addedAmount=0}。
 */
@RestController
@RequestMapping("/clinical/surgery-cases/{caseId}")
public class SurgeryChargeController {

    private static final String CHARGE_READ = "clinical:charge:read";
    private static final String CHARGE_SETTLE = "clinical:charge:settle";

    private final SurgeryChargeService service;

    public SurgeryChargeController(SurgeryChargeService service) {
        this.service = service;
    }

    @GetMapping("/charge-preview")
    public ApiResponse<SurgeryChargePreview> preview(
            @PathVariable UUID caseId,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions) {
        Permissions.require(permissions, CHARGE_READ);
        return ApiResponse.ok(service.preview(caseId));
    }

    @PostMapping("/charges")
    public ApiResponse<SurgeryChargeResult> charge(
            @PathVariable UUID caseId,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(value = AuthHeaders.ACTOR, defaultValue = "system") String actor) {
        Permissions.require(permissions, CHARGE_SETTLE);
        return ApiResponse.ok(service.charge(caseId, actor));
    }
}
