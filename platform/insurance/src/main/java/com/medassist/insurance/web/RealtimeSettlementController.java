package com.medassist.insurance.web;

import com.medassist.common.api.ApiResponse;
import com.medassist.common.security.AuthHeaders;
import com.medassist.common.security.Permissions;
import com.medassist.insurance.service.RealtimeSettlementService;
import com.medassist.insurance.web.InsuranceDtos.RealtimeSettlementView;
import com.medassist.insurance.web.InsuranceDtos.RealtimeSubmitReq;
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
 * 医保实时结算交易（对外适配层）。
 *
 * <p>提交/重试属于对外出账动作，独立授权给医保办与管理员；
 * 查询沿用 {@code insurance:realtime:read}，窗口只读。
 */
@RestController
@RequestMapping("/insurance/realtime-settlements")
public class RealtimeSettlementController {

    private static final String WRITE = "insurance:realtime:write";
    private static final String READ = "insurance:realtime:read";

    private final RealtimeSettlementService service;
    private final com.medassist.insurance.service.SettlementAccessGuard guard;

    public RealtimeSettlementController(RealtimeSettlementService service,
                                        com.medassist.insurance.service.SettlementAccessGuard guard) {
        this.service = service;
        this.guard = guard;
    }

    /** 提交实时结算（同一清单同一请求号幂等）。 */
    @PostMapping("/bill/{billId}")
    public ApiResponse<RealtimeSettlementView> submit(
            @PathVariable UUID billId,
            @Valid @RequestBody RealtimeSubmitReq req,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(value = AuthHeaders.ACTOR, defaultValue = "system") String actor,
            com.medassist.common.security.DataScope scope) {
        Permissions.require(permissions, WRITE);
        guard.requireBill(scope, billId);
        return ApiResponse.ok(service.submit(billId, req.requestId(), req.bizType(), actor));
    }

    /** 失败交易手动重试；已成功幂等返回。 */
    @PostMapping("/{id}/retry")
    public ApiResponse<RealtimeSettlementView> retry(
            @PathVariable UUID id,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(value = AuthHeaders.ACTOR, defaultValue = "system") String actor,
            com.medassist.common.security.DataScope scope) {
        Permissions.require(permissions, WRITE);
        guard.requireRealtime(scope, id);
        return ApiResponse.ok(service.retry(id, actor));
    }

    @GetMapping("/{id}")
    public ApiResponse<RealtimeSettlementView> detail(
            @PathVariable UUID id,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            com.medassist.common.security.DataScope scope) {
        Permissions.require(permissions, READ);
        guard.requireRealtime(scope, id);
        return ApiResponse.ok(service.detail(id));
    }

    @GetMapping
    public ApiResponse<List<RealtimeSettlementView>> list(
            @RequestParam(required = false) UUID billId,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Integer limit,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            com.medassist.common.security.DataScope scope) {
        Permissions.require(permissions, READ);
        return ApiResponse.ok(service.list(billId, status, limit, scope.campusFilter()));
    }
}
