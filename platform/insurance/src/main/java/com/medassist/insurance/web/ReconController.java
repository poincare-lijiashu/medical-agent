package com.medassist.insurance.web;

import com.medassist.common.api.ApiResponse;
import com.medassist.common.security.AuthHeaders;
import com.medassist.common.security.DataScope;
import com.medassist.common.security.Permissions;
import com.medassist.insurance.domain.InsuranceModel.ReconBatch;
import com.medassist.insurance.domain.InsuranceModel.ReconDiff;
import com.medassist.insurance.service.SettlementReconService;
import com.medassist.insurance.web.InsuranceDtos.ReconBatchList;
import com.medassist.insurance.web.InsuranceDtos.ReconComputeReq;
import com.medassist.insurance.web.InsuranceDtos.ReconResolveReq;
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
 * 医保结算日结/月结对账（§291 / 计划书 W2.5）。
 *
 * <p>权限切分：
 * <ul>
 *   <li>{@code insurance:recon:read}：批次、差异与汇总（医保办/运营/管理员）；</li>
 *   <li>{@code insurance:recon:manage}：生成/重算、处理差异、关闭批次。</li>
 * </ul>
 *
 * <p>安全边界：对账必须全院视角（服务层校验 DataScope），且操作必须带真实操作人；
 * 差异记录不含患者姓名/证件号，只有院内清单号与就诊主键。
 */
@RestController
@RequestMapping("/insurance/recon")
public class ReconController {

    private static final String READ = "insurance:recon:read";
    private static final String MANAGE = "insurance:recon:manage";

    private final SettlementReconService service;

    public ReconController(SettlementReconService service) {
        this.service = service;
    }

    /** 生成或重算批次（同一期间幂等；已关闭批次拒绝重算）。 */
    @PostMapping("/batches")
    public ApiResponse<ReconBatch> compute(
            @Valid @RequestBody ReconComputeReq req,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(value = AuthHeaders.ACTOR, required = false) String actor,
            DataScope scope) {
        Permissions.require(permissions, MANAGE);
        return ApiResponse.ok(service.compute(req.periodType(), req.periodFrom(), req.periodTo(),
                actor, scope));
    }

    @GetMapping("/batches")
    public ApiResponse<ReconBatchList> list(
            @RequestParam(defaultValue = "50") int limit,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions) {
        Permissions.require(permissions, READ);
        List<ReconBatch> items = service.listBatches(limit);
        return ApiResponse.ok(new ReconBatchList(items));
    }

    /** 批次详情：摘要 + 差异（默认 OPEN 优先）+ 当前未处理数。 */
    @GetMapping("/batches/{id}")
    public ApiResponse<SettlementReconService.BatchDetail> detail(
            @PathVariable UUID id,
            @RequestParam(defaultValue = "") String diffStatus,
            @RequestParam(defaultValue = "200") int limit,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions) {
        Permissions.require(permissions, READ);
        return ApiResponse.ok(service.detail(id, diffStatus, limit));
    }

    /** 关闭批次：必须无 OPEN 差异；关闭后不可重算（数据库触发器兜底）。 */
    @PostMapping("/batches/{id}/close")
    public ApiResponse<ReconBatch> close(
            @PathVariable UUID id,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(value = AuthHeaders.ACTOR, required = false) String actor) {
        Permissions.require(permissions, MANAGE);
        return ApiResponse.ok(service.close(id, actor));
    }

    /** 处理差异：RESOLVED / IGNORED，说明必填并写审计。 */
    @PostMapping("/diffs/{id}/resolve")
    public ApiResponse<ReconDiff> resolve(
            @PathVariable UUID id,
            @Valid @RequestBody ReconResolveReq req,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(value = AuthHeaders.ACTOR, required = false) String actor) {
        Permissions.require(permissions, MANAGE);
        return ApiResponse.ok(service.resolveDiff(id, req.status(), req.note(), actor));
    }
}
