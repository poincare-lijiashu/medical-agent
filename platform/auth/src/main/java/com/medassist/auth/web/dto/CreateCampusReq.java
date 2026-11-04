package com.medassist.auth.web.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** 新建院区（G46 管理端维护）。 */
public record CreateCampusReq(
        @NotBlank(message = "院区编码不能为空")
        @Pattern(regexp = "[A-Za-z0-9_-]{2,32}", message = "院区编码只能包含字母、数字、下划线与短横线，长度 2-32")
        String code,

        @NotBlank(message = "院区名称不能为空")
        @Size(max = 64, message = "院区名称长度不能超过 64")
        String name,

        @Min(value = 0, message = "排序号不能为负")
        @Max(value = 9999, message = "排序号不能超过 9999")
        Integer sortNo
) {
}
