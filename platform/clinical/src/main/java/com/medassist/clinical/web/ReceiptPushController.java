package com.medassist.clinical.web;

import com.medassist.clinical.service.ReceiptPushService;
import com.medassist.clinical.web.ReceiptDtos.ReceiptPushView;
import com.medassist.common.api.ApiResponse;
import com.medassist.common.security.AuthHeaders;
import com.medassist.common.security.Permissions;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * 电子票据推送 API（G21）。
 *
 * <p>只有有效票据可推送；重复推送已成功的票据幂等返回；失败可再次调用重试。
 */
@RestController
@RequestMapping("/clinical/receipts")
public class ReceiptPushController {

    private static final String PUSH = "clinical:receipt:push";
    private static final String READ = "clinical:charge:read";

    private final ReceiptPushService service;

    public ReceiptPushController(ReceiptPushService service) {
        this.service = service;
    }

    /** 推送票据到电子票据平台（已成功则幂等返回）。 */
    @PostMapping("/{id}/push")
    public ApiResponse<ReceiptPushView> push(
            @PathVariable UUID id,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(value = AuthHeaders.ACTOR, defaultValue = "system") String actor) {
        Permissions.require(permissions, PUSH);
        return ApiResponse.ok(service.push(id, actor));
    }

    /** 票据的推送记录（含失败原因与平台票据号）。 */
    @GetMapping("/{id}/pushes")
    public ApiResponse<List<ReceiptPushView>> pushes(
            @PathVariable UUID id,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions) {
        Permissions.require(permissions, READ);
        return ApiResponse.ok(service.listPushes(id));
    }
}
