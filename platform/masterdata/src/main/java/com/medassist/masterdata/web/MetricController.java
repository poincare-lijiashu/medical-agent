package com.medassist.masterdata.web;

import com.medassist.common.api.ApiResponse;
import com.medassist.common.security.AuthHeaders;
import com.medassist.common.security.Permissions;
import com.medassist.masterdata.domain.MasterDataModel.MetricDefinition;
import com.medassist.masterdata.domain.MasterDataModel.MetricDetail;
import com.medassist.masterdata.domain.MasterDataModel.MetricImpact;
import com.medassist.masterdata.domain.MasterDataModel.MetricSummary;
import com.medassist.masterdata.service.MetricDictionaryService;
import com.medassist.masterdata.web.MetricDtos.MetricActionReq;
import com.medassist.masterdata.web.MetricDtos.MetricLineageReq;
import com.medassist.masterdata.web.MetricDtos.MetricUpsertReq;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 指标字典与口径治理（§293 / 计划书 W4.5）。
 *
 * <p>权限沿用主数据治理边界，不另造一套：
 * <ul>
 *   <li>{@code mdm:read}：查询、版本链、血缘与影响分析；</li>
 *   <li>{@code mdm:write}：新建/修改草稿、修订、登记血缘；</li>
 *   <li>{@code mdm:approve}：发布与停用（口径对外生效/下线）。</li>
 * </ul>
 *
 * <p>发布后的指标不可直接修改，必须 revise 出新版本；所有动作都要求真实操作人。
 */
@RestController
@RequestMapping("/mdm/metrics")
public class MetricController {

    private static final String READ = "mdm:read";
    private static final String WRITE = "mdm:write";
    private static final String APPROVE = "mdm:approve";

    private final MetricDictionaryService service;

    public MetricController(MetricDictionaryService service) {
        this.service = service;
    }

    @GetMapping
    public ApiResponse<List<MetricDefinition>> list(
            @RequestParam(defaultValue = "") String domain,
            @RequestParam(defaultValue = "") String status,
            @RequestParam(defaultValue = "") String q,
            @RequestParam(defaultValue = "200") int limit,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions) {
        Permissions.require(permissions, READ);
        return ApiResponse.ok(service.list(domain, status, q, limit));
    }

    @GetMapping("/summary")
    public ApiResponse<MetricSummary> summary(
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions) {
        Permissions.require(permissions, READ);
        return ApiResponse.ok(service.summary());
    }

    @GetMapping("/{code}")
    public ApiResponse<MetricDetail> detail(
            @PathVariable String code,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions) {
        Permissions.require(permissions, READ);
        return ApiResponse.ok(service.detail(code));
    }

    /** 影响分析：修订前评估会波及哪些报表/看板/派生指标。 */
    @GetMapping("/{code}/impact")
    public ApiResponse<MetricImpact> impact(
            @PathVariable String code,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions) {
        Permissions.require(permissions, READ);
        return ApiResponse.ok(service.impact(code));
    }

    @PostMapping
    public ApiResponse<MetricDetail> create(
            @Valid @RequestBody MetricUpsertReq req,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(value = AuthHeaders.ACTOR, required = false) String actor) {
        Permissions.require(permissions, WRITE);
        return ApiResponse.ok(service.create(req, actor));
    }

    @PutMapping("/{code}")
    public ApiResponse<MetricDetail> update(
            @PathVariable String code,
            @Valid @RequestBody MetricUpsertReq req,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(value = AuthHeaders.ACTOR, required = false) String actor) {
        Permissions.require(permissions, WRITE);
        return ApiResponse.ok(service.update(code, req, actor));
    }

    /** 修订已发布指标：生成下一版本 DRAFT，对外口径保持不变。 */
    @PostMapping("/{code}/revise")
    public ApiResponse<MetricDetail> revise(
            @PathVariable String code,
            @Valid @RequestBody MetricActionReq req,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(value = AuthHeaders.ACTOR, required = false) String actor) {
        Permissions.require(permissions, WRITE);
        return ApiResponse.ok(service.revise(code, req.version(), actor));
    }

    /** 发布（需审批权限）：落版本快照与血缘，对外口径生效。 */
    @PostMapping("/{code}/publish")
    public ApiResponse<MetricDetail> publish(
            @PathVariable String code,
            @Valid @RequestBody MetricActionReq req,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(value = AuthHeaders.ACTOR, required = false) String actor) {
        Permissions.require(permissions, APPROVE);
        return ApiResponse.ok(service.publish(code, req.version(), actor));
    }

    /** 停用（需审批权限）：口径下线，历史版本保留。 */
    @PostMapping("/{code}/disable")
    public ApiResponse<MetricDetail> disable(
            @PathVariable String code,
            @Valid @RequestBody MetricActionReq req,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(value = AuthHeaders.ACTOR, required = false) String actor) {
        Permissions.require(permissions, APPROVE);
        return ApiResponse.ok(service.disable(code, req.version(), actor));
    }

    /** 登记血缘：报表/看板接入时登记 DOWNSTREAM，源系统登记 UPSTREAM。 */
    @PostMapping("/{code}/lineage")
    public ApiResponse<MetricDetail> lineage(
            @PathVariable String code,
            @Valid @RequestBody MetricLineageReq req,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(value = AuthHeaders.ACTOR, required = false) String actor) {
        Permissions.require(permissions, WRITE);
        return ApiResponse.ok(service.addLineage(code, req, actor));
    }
}
