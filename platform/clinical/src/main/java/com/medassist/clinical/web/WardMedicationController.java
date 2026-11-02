package com.medassist.clinical.web;

import com.medassist.clinical.service.DailyDispenseService;
import com.medassist.clinical.web.ClinicalDtos.DispenseBoard;
import com.medassist.clinical.web.ClinicalDtos.WardReceiptReq;
import com.medassist.clinical.web.ClinicalDtos.WardReceiptView;
import com.medassist.clinical.web.ClinicalDtos.WardReturnReq;
import com.medassist.clinical.web.ClinicalDtos.WardReturnView;
import com.medassist.clinical.web.ClinicalDtos.WardStockView;
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
import java.util.UUID;

/**
 * 病区备药与执行对账（B2 后续第二步）。
 *
 * <p>看板回答"今天这条长嘱：请领了吗 / 处方是哪张 / 病区收到了吗 / 护理执行了吗"；
 * 收药按发药单幂等，重复扫码不会产生第二条记录。
 */
@RestController
@RequestMapping("/clinical/visits/{visitId}")
public class WardMedicationController {

    private static final String VISIT_READ = "clinical:visit:read";
    private static final String ORDER_CREATE = "clinical:order:create";

    private final DailyDispenseService service;

    public WardMedicationController(DailyDispenseService service) {
        this.service = service;
    }

    /** 备药看板（date 可空=今天）。 */
    @GetMapping("/dispense-board")
    public ApiResponse<DispenseBoard> board(
            @PathVariable UUID visitId,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions) {
        Permissions.require(permissions, VISIT_READ);
        return ApiResponse.ok(service.board(visitId, date));
    }

    /** 病区收药确认（按 dispenseId 幂等）。 */
    @PostMapping("/orders/{orderId}/ward-receipts")
    public ApiResponse<WardReceiptView> receive(
            @PathVariable UUID visitId,
            @PathVariable UUID orderId,
            @Valid @RequestBody WardReceiptReq req,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(value = AuthHeaders.ACTOR, defaultValue = "system") String actor) {
        Permissions.require(permissions, ORDER_CREATE);
        return ApiResponse.ok(service.receive(visitId, orderId, req, actor));
    }

    /**
     * 病区退药（按 returnId 幂等）。
     *
     * <p>只允许退"当日护理执行 REFUSED / OMITTED"的药品；已执行的退药返回 422。
     */
    @PostMapping("/ward-receipts/{receiptId}/returns")
    public ApiResponse<WardReturnView> returnToStock(
            @PathVariable UUID visitId,
            @PathVariable UUID receiptId,
            @Valid @RequestBody WardReturnReq req,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(value = AuthHeaders.ACTOR, defaultValue = "system") String actor) {
        Permissions.require(permissions, ORDER_CREATE);
        return ApiResponse.ok(service.returnToPharmacy(visitId, receiptId, req, actor));
    }

    /** 病区二级库存：按药品汇总余额 + 最近流水（跨日期累计）。 */
    @GetMapping("/ward-stock")
    public ApiResponse<WardStockView> stock(
            @PathVariable UUID visitId,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions) {
        Permissions.require(permissions, VISIT_READ);
        return ApiResponse.ok(service.stock(visitId));
    }
}
