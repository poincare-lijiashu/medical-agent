package com.medassist.auth.web.dto;

import com.medassist.auth.domain.TenantQuotas;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** 租户开通编排 API 出入参（§284）。 */
public final class ProvisioningDtos {

    private ProvisioningDtos() {
    }

    /** 首个管理员：口令由服务端生成，只在开通结果里出现一次。 */
    public record InitialAdmin(
            @NotBlank
            @Pattern(regexp = "[A-Za-z0-9._-]{3,64}",
                    message = "管理员登录名只能包含字母、数字、点、下划线与短横线，长度 3-64")
            String username,
            @NotBlank @Size(max = 64, message = "姓名长度不能超过 64") String displayName,
            @Size(max = 64, message = "工号长度不能超过 64") String employeeNo
    ) {
    }

    public record ProvisionReq(
            @NotBlank
            @Pattern(regexp = "[a-z0-9][a-z0-9-]{1,31}",
                    message = "租户编码只能是小写字母、数字与短横线，长度 2-32")
            String tenantCode,

            @NotBlank
            @Pattern(regexp = "[A-Za-z0-9._-]{4,64}",
                    message = "实例码只能包含字母、数字、点、下划线与短横线，长度 4-64")
            String instanceCode,

            @NotBlank @Size(max = 128, message = "租户名称长度不能超过 128") String name,
            @Size(max = 32) String editionCode,

            @NotBlank @Size(max = 200, message = "数据库主机不能为空")
            @Pattern(regexp = "[A-Za-z0-9.:_\\[\\]-]{3,200}",
                    message = "数据库主机格式应为 host 或 host:port")
            String dbHost,

            @Pattern(regexp = "[A-Za-z0-9_][A-Za-z0-9_-]{0,62}",
                    message = "数据库名只能包含字母、数字、下划线与短横线")
            String dbName,

            @Size(max = 200) String cellEndpoint,
            @Size(max = 32) String region,
            TenantQuotas quotas,
            @Size(max = 64) String contactName,
            @Size(max = 64) String contactPhone,
            @Size(max = 500) String notes,

            @NotNull @Valid InitialAdmin initialAdmin,

            /** 是否要求实例已有可用授权；为空时取服务端默认（本地/离线默认不阻塞）。 */
            Boolean requireLicense
    ) {
    }

    public record StepView(
            int stepNo,
            String code,
            String status,
            Map<String, Object> detail,
            String error,
            Instant startedAt,
            Instant finishedAt,
            long durationMs
    ) {
    }

    /**
     * 开通任务视图。
     *
     * @param oneTimeAdminPassword 仅在本次调用真正创建了管理员时返回一次；列表/查询永远为空
     */
    public record JobView(
            UUID id,
            String tenantCode,
            String instanceCode,
            String status,
            String requestedBy,
            Instant startedAt,
            Instant finishedAt,
            List<StepView> steps,
            String oneTimeAdminPassword
    ) {
    }

    public record JobList(List<JobView> items, int total) {
    }
}
