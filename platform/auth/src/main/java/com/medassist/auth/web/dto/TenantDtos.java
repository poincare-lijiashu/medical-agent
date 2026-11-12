package com.medassist.auth.web.dto;

import com.medassist.auth.domain.TenantQuotas;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;

/** 租户台账 API 出入参（对应前端 {@code api/tenant.ts}）。 */
public final class TenantDtos {

    private TenantDtos() {
    }

    /** 新建租户（平台运营）。状态默认 PENDING：目录先建，Cell 交付后再开通。 */
    public record CreateReq(
            @NotBlank(message = "租户编码不能为空")
            @Pattern(regexp = "[a-z0-9][a-z0-9-]{1,31}",
                    message = "租户编码只能是小写字母、数字与短横线，长度 2-32")
            String code,

            @NotBlank(message = "实例码不能为空")
            @Pattern(regexp = "[A-Za-z0-9._-]{4,64}",
                    message = "实例码只能包含字母、数字、点、下划线与短横线，长度 4-64")
            String instanceCode,

            @NotBlank(message = "租户名称不能为空")
            @Size(max = 128, message = "租户名称长度不能超过 128")
            String name,

            @Size(max = 32, message = "版本编码长度不能超过 32")
            String editionCode,

            @Pattern(regexp = "PENDING|ACTIVE", message = "新建租户状态只能是 PENDING 或 ACTIVE")
            String status,

            @Size(max = 200, message = "入口地址长度不能超过 200")
            String cellEndpoint,

            @Size(max = 200, message = "数据库主机长度不能超过 200")
            String dbHost,

            @Pattern(regexp = "[A-Za-z0-9_][A-Za-z0-9_-]{0,62}",
                    message = "数据库名只能包含字母、数字、下划线与短横线")
            String dbName,

            @Size(max = 32, message = "区域长度不能超过 32")
            String region,

            TenantQuotas quotas,

            @Size(max = 64) String contactName,
            @Size(max = 64) String contactPhone,
            @Size(max = 500, message = "备注长度不能超过 500") String notes
    ) {
    }

    /**
     * 更新租户元数据。
     *
     * <p>平台运营可改全部字段；租户管理员（tenant:self:manage）只改名称/联系人/备注，
     * 其余字段会被忽略并保留原值——避免医院管理员把自己的入口指向别处。
     */
    public record UpdateReq(
            @Size(max = 128, message = "租户名称长度不能超过 128") String name,
            @Size(max = 32) String editionCode,
            @Size(max = 200) String cellEndpoint,
            @Size(max = 200) String dbHost,
            @Size(max = 32) String region,
            TenantQuotas quotas,
            @Size(max = 64) String contactName,
            @Size(max = 64) String contactPhone,
            @Size(max = 500) String notes,
            @NotNull(message = "缺少版本号") Long version
    ) {
    }

    /** 状态流转：PENDING→ACTIVE、ACTIVE→SUSPENDED、SUSPENDED→ACTIVE、*→CLOSED。 */
    public record StatusReq(
            @NotBlank(message = "目标状态不能为空")
            @Pattern(regexp = "ACTIVE|SUSPENDED|CLOSED", message = "目标状态只能是 ACTIVE / SUSPENDED / CLOSED")
            String status,
            @Size(max = 200, message = "原因长度不能超过 200") String reason,
            /** 注销时的数据保留截止日；缺省按 365 天写入，避免"保留期只存在于合同里"。 */
            java.time.LocalDate retentionUntil,
            @NotNull(message = "缺少版本号") Long version
    ) {
    }

    /** 租户视图（列表与详情共用；{@code recentChanges} 仅详情返回）。 */
    public record Resp(
            String code,
            String instanceCode,
            String name,
            String editionCode,
            String status,
            String cellEndpoint,
            String dbHost,
            String region,
            TenantQuotas quotas,
            String contactName,
            String contactPhone,
            String notes,
            long userCount,
            long activeUserCount,
            Instant closedAt,
            String closedReason,
            java.time.LocalDate retentionUntil,
            long version,
            Instant createdAt,
            Instant updatedAt,
            List<ChangeEntry> recentChanges
    ) {
    }

    public record ChangeEntry(Instant ts, String actor, String action, String detail) {
    }

    /** 列表结果：附带当前调用者视角，前端据此决定是否显示"开通/停用"。 */
    public record ListResp(List<Resp> items, int total, String currentTenant, boolean platformLevel) {
    }
}
