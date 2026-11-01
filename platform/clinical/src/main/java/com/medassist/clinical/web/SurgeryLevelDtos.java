package com.medassist.clinical.web;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** 手术分级请求体（第四批：分级提交 / 审批 / 驳回）。 */
public final class SurgeryLevelDtos {

    private SurgeryLevelDtos() {
    }

    /**
     * 提交或调整分级。
     *
     * <p>{@code version} 首次提交可不传；对已有记录调整时必须传（乐观锁）。
     */
    public record SetLevelReq(
            @NotNull @Min(1) @Max(4) Integer level,
            Long version) {
    }

    /** 驳回分级：必须写原因。 */
    public record RejectLevelReq(
            @NotNull Long version,
            @NotBlank @Size(max = 256) String reason) {
    }
}
