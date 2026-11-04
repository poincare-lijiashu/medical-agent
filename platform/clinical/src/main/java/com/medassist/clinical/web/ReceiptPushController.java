package com.medassist.clinical.web;

import com.medassist.clinical.domain.ClinicalModel.ReceiptPushLedger;
import com.medassist.clinical.service.ReceiptPushService;
import com.medassist.clinical.web.ReceiptDtos.RedFlushReq;
import com.medassist.clinical.web.ReceiptDtos.ReceiptPushSummaryView;
import com.medassist.clinical.web.ReceiptDtos.ReceiptPushView;
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
import java.util.List;
import java.util.UUID;

/**
 * 电子票据推送 API（G21 / G24）。
 *
 * <p>只有有效票据可开具推送；作废票据走红冲（需成功的原开具报文）；
 * 重复推送已成功的报文幂等返回；失败可再次调用重试。
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

    /** 红冲已作废票据（必须存在成功的原开具报文）。 */
    @PostMapping("/{id}/red-flush")
    public ApiResponse<ReceiptPushView> redFlush(
            @PathVariable UUID id,
            @Valid @RequestBody RedFlushReq req,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(value = AuthHeaders.ACTOR, defaultValue = "system") String actor) {
        Permissions.require(permissions, PUSH);
        return ApiResponse.ok(service.redFlush(id, req.reason(), actor));
    }

    /** 票据的推送记录（开具 + 红冲，含失败原因与平台票据号）。 */
    @GetMapping("/{id}/pushes")
    public ApiResponse<List<ReceiptPushView>> pushes(
            @PathVariable UUID id,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions) {
        Permissions.require(permissions, READ);
        return ApiResponse.ok(service.listPushes(id));
    }

    /** 电子票据对账台账（默认最近 7 天，区间最长 92 天）。 */
    @GetMapping("/pushes")
    public ApiResponse<List<ReceiptPushLedger>> ledger(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            LocalDate to,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String messageType,
            @RequestParam(required = false) String vendor,
            @RequestParam(required = false) Integer limit,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions) {
        Permissions.require(permissions, READ);
        return ApiResponse.ok(service.listLedger(from, to, status, messageType, vendor, limit));
    }

    /** 对账汇总：总/成功/失败/待处理/红冲与开具成功金额。 */
    @GetMapping("/pushes/summary")
    public ApiResponse<ReceiptPushSummaryView> summary(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            LocalDate to,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions) {
        Permissions.require(permissions, READ);
        return ApiResponse.ok(service.summary(from, to));
    }
}
