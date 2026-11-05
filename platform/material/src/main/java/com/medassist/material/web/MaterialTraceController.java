package com.medassist.material.web;

import com.medassist.common.api.ApiResponse;
import com.medassist.common.security.AuthHeaders;
import com.medassist.common.security.DataScope;
import com.medassist.common.security.Permissions;
import com.medassist.material.domain.MaterialModel.ConsumableTrace;
import com.medassist.material.service.ConsumableTraceService;
import com.medassist.material.service.MaterialAccessGuard;
import com.medassist.material.web.MaterialDtos.CreateTraceReq;
import com.medassist.material.web.MaterialDtos.RecallView;
import com.medassist.material.web.MaterialDtos.TraceView;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 高值/植入耗材使用追溯与批次召回 API。
 *
 * <p>权限：
 * <ul>
 *   <li>{@code material:trace:write}——使用登记（手术室/病区护士）；</li>
 *   <li>{@code material:trace:read}——追溯查询与召回分析（院感/质控/物资科）。</li>
 * </ul>
 * 患者号按 {@code phi:read} 掩码：无该权限仍可召回（看批次影响面），但看不到明文患者号。
 */
@RestController
@RequestMapping("/material/traces")
public class MaterialTraceController {

    private static final String WRITE = "material:trace:write";
    private static final String READ = "material:trace:read";

    private final ConsumableTraceService service;
    private final MaterialAccessGuard guard;

    public MaterialTraceController(ConsumableTraceService service, MaterialAccessGuard guard) {
        this.service = service;
        this.guard = guard;
    }

    /** 使用登记：耗材与批次由出库明细回填，登记数量不得超过未追溯数量。 */
    @PostMapping
    public ApiResponse<TraceView> register(
            @Valid @RequestBody CreateTraceReq req,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(value = AuthHeaders.ACTOR, defaultValue = "system") String actor,
            DataScope scope) {
        Permissions.require(permissions, WRITE);
        guard.requireIssueItem(scope, req.issueItemId());
        ConsumableTrace trace = service.register(req, actor).trace();
        return ApiResponse.ok(new TraceView(PhiMask.trace(trace, scope)));
    }

    @GetMapping
    public ApiResponse<List<ConsumableTrace>> list(
            @RequestParam(required = false) String batchNo,
            @RequestParam(required = false) String patientNo,
            @RequestParam(required = false) UUID consumableId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(required = false) Integer limit,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            DataScope scope) {
        Permissions.require(permissions, READ);
        return ApiResponse.ok(service.list(batchNo, patientNo, consumableId, from, to,
                        scope.campusFilter(), limit)
                .stream().map(t -> PhiMask.trace(t, scope)).toList());
    }

    /** 批次召回：统计影响患者/手术数与明细分段（最多 500 条）。 */
    @GetMapping("/recall")
    public ApiResponse<RecallView> recall(
            @RequestParam String batchNo,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            DataScope scope) {
        Permissions.require(permissions, READ);
        RecallView view = service.recall(batchNo, scope.campusFilter());
        return ApiResponse.ok(new RecallView(view.batchNo(), view.traceCount(),
                view.totalQuantity(), view.patientCount(), view.surgeryCount(),
                view.traces().stream().map(t -> PhiMask.trace(t, scope)).toList()));
    }
}
