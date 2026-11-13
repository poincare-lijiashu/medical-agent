package com.medassist.masterdata.web;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/** 指标字典请求契约（金额/口径均由服务层校验，请求体不接受状态/发布字段）。 */
public final class MetricDtos {

    private MetricDtos() {
    }

    /**
     * 新建/修改指标。
     *
     * <p>{@code code} 仅新建必填；修改时以路径为准。{@code version} 仅修改必填（乐观锁）。
     */
    public record MetricUpsertReq(
            @Size(max = 64) String code,
            @NotBlank @Size(max = 128) String name,
            @NotBlank String domain,
            @NotBlank @Size(min = 10, max = 2000) String definition,
            @NotBlank @Size(min = 3, max = 2000) String formula,
            @NotBlank String aggregation,
            @NotBlank @Size(max = 32) String unit,
            @NotBlank String timeWindow,
            @NotBlank String granularity,
            @Size(max = 64) String sourceSchema,
            @Size(max = 20) List<@Size(max = 128) String> sourceTables,
            @NotBlank @Size(min = 2, max = 64) String ownerDept,
            @Size(max = 64) String ownerRole,
            Long version) {
    }

    /** 发布/修订/停用的版本号（必须与当前 head 一致）。 */
    public record MetricActionReq(@NotNull @Min(1) Long version) {
    }

    /** 血缘登记：UPSTREAM 源表/接口，DOWNSTREAM 报表/看板/派生指标。 */
    public record MetricLineageReq(
            @NotBlank String direction,
            @NotBlank String refType,
            @NotBlank @Size(min = 2, max = 128) String refCode,
            @Size(max = 256) String description) {
    }
}
