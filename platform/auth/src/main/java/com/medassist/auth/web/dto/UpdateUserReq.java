package com.medassist.auth.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** 修改账号资料请求（version 用于乐观锁）。 */
public record UpdateUserReq(
        @NotBlank(message = "姓名不能为空")
        @Size(max = 64, message = "姓名长度不能超过 64")
        String displayName,

        @Size(max = 32, message = "工号长度不能超过 32")
        String employeeNo,

        @Size(max = 32, message = "科室编码长度不能超过 32")
        String orgCode,

        @NotNull(message = "版本号不能为空（用于并发控制）")
        Long version
) {
}
