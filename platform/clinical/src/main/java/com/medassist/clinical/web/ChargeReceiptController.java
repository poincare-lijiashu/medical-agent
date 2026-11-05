package com.medassist.clinical.web;

import com.medassist.clinical.domain.ClinicalModel.ChargeReceipt;
import com.medassist.clinical.service.ChargeReceiptService;
import com.medassist.clinical.service.VisitAccessGuard;
import com.medassist.clinical.web.ReceiptDtos.IssueReceiptReq;
import com.medassist.clinical.web.ReceiptDtos.VoidReceiptReq;
import com.medassist.common.api.ApiResponse;
import com.medassist.common.security.AuthHeaders;
import com.medassist.common.security.DataScope;
import com.medassist.common.security.Permissions;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * 收费票据 API（G14）。
 *
 * <p>权限：查询 {@code clinical:charge:read}；开票/重打 {@code clinical:receipt:issue}；
 * 作废 {@code clinical:receipt:void}。作废与开票分开授权，避免"能开就能抹"。
 */
@RestController
@RequestMapping("/clinical/receipts")
public class ChargeReceiptController {

    private static final String READ = "clinical:charge:read";
    private static final String ISSUE = "clinical:receipt:issue";
    private static final String VOID = "clinical:receipt:void";

    private final ChargeReceiptService service;
    private final VisitAccessGuard guard;

    public ChargeReceiptController(ChargeReceiptService service, VisitAccessGuard guard) {
        this.service = service;
        this.guard = guard;
    }

    @GetMapping
    public ApiResponse<List<ChargeReceipt>> list(
            @RequestParam UUID visitId,
            @RequestParam(required = false) Integer limit,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            DataScope scope) {
        Permissions.require(permissions, READ);
        guard.requireRead(scope, visitId);
        return ApiResponse.ok(service.list(visitId, limit));
    }

    // G51：票据是 visit 级财务数据，五个入口全部按就诊归属（科室 + 院区）校验。
    // 原实现只检查全局权限串，持有 clinical:receipt:issue 的账号可以对
    // 他科/他院区的收费单开票、重打、作废。

    @GetMapping("/{id}")
    public ApiResponse<ChargeReceipt> detail(
            @PathVariable UUID id,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            DataScope scope) {
        Permissions.require(permissions, READ);
        guard.requireRead(scope, service.visitIdOfReceipt(id));
        return ApiResponse.ok(service.detail(id));
    }

    /** 开票：已结算收费单，一张收费单同时只能有一张有效票据。 */
    @PostMapping
    public ApiResponse<ChargeReceipt> issue(
            @Valid @RequestBody IssueReceiptReq req,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(value = AuthHeaders.ACTOR, defaultValue = "system") String actor,
            DataScope scope) {
        Permissions.require(permissions, ISSUE);
        guard.require(scope, service.visitIdOfCharge(req.chargeId()));
        return ApiResponse.ok(service.issue(req.chargeId(), actor));
    }

    /** 重打：不换号，只累加次数。 */
    @PostMapping("/{id}/reprint")
    public ApiResponse<ChargeReceipt> reprint(
            @PathVariable UUID id,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(value = AuthHeaders.ACTOR, defaultValue = "system") String actor,
            DataScope scope) {
        Permissions.require(permissions, ISSUE);
        guard.require(scope, service.visitIdOfReceipt(id));
        return ApiResponse.ok(service.reprint(id, actor));
    }

    /** 作废：必须写原因；作废后可换开新票。 */
    @PostMapping("/{id}/void")
    public ApiResponse<ChargeReceipt> voidReceipt(
            @PathVariable UUID id,
            @Valid @RequestBody VoidReceiptReq req,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(value = AuthHeaders.ACTOR, defaultValue = "system") String actor,
            DataScope scope) {
        Permissions.require(permissions, VOID);
        guard.require(scope, service.visitIdOfReceipt(id));
        return ApiResponse.ok(service.voidReceipt(id, req, actor));
    }
}
