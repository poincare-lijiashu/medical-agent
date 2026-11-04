package com.medassist.auth.web.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** 修改院区（名称/状态/排序；编码不可改，避免历史数据归属漂移）。 */
public record UpdateCampusReq(
        @NotBlank(message = "院区名称不能为空")
        @Size(max = 64, message = "院区名称长度不能超过 64")
        String name,

        @NotBlank(message = "状态不能为空")
        @Pattern(regexp = "ACTIVE|DISABLED", message = "状态只能是 ACTIVE 或 DISABLED")
        String status,

        @Min(value = 0, message = "排序号不能为负")
        @Max(value = 9999, message = "排序号不能超过 9999")
        Integer sortNo,

        @NotNull(message = "版本号不能为空")
        Long version
) {
}
