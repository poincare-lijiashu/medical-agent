package com.medassist.tech.web;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;

/** 设备维保工单请求体（G82）。 */
public final class DeviceWorkOrderDtos {

    private DeviceWorkOrderDtos() {
    }

    /** 报修/建单：类型与标题必填，上报时间缺省取当前时刻。 */
    public record CreateWorkOrderReq(
            @NotBlank @Pattern(regexp = "(?i)PREVENTIVE|CORRECTIVE|INSPECTION",
                    message = "工单类型只能是 PREVENTIVE / CORRECTIVE / INSPECTION")
            String orderType,
            @Pattern(regexp = "(?i)LOW|NORMAL|HIGH|URGENT", message = "优先级不合法")
            String priority,
            @NotBlank @Size(max = 120) String title,
            @Size(max = 2000) String description,
            Instant reportedAt,
            @Size(max = 120) String vendor) {
    }

    /** 开始维修（CAS）。 */
    public record StartWorkOrderReq(@NotNull Long version) {
    }

    /** 完工：处理措施必填；费用可空（计划保养可能无费用）。 */
    public record FinishWorkOrderReq(
            @NotNull Long version,
            Instant finishedAt,
            @NotBlank @Size(max = 2000) String resolution,
            @DecimalMin("0") @Digits(integer = 10, fraction = 2) BigDecimal costAmount) {
    }

    /** 验收（四眼）：必须由建单人以外的人验收。 */
    public record AcceptWorkOrderReq(
            @NotNull Long version,
            @Size(max = 500) String note) {
    }

    /** 取消：必须写原因。 */
    public record CancelWorkOrderReq(
            @NotNull Long version,
            @NotBlank @Size(max = 256) String reason) {
    }
}
