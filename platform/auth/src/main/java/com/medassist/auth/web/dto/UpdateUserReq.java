package com.medassist.auth.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.UUID;

/** 修改账号资料请求（version 用于乐观锁）。 */
public record UpdateUserReq(
        @NotBlank(message = "姓名不能为空")
        @Size(max = 64, message = "姓名长度不能超过 64")
        String displayName,

        @Size(max = 32, message = "工号长度不能超过 32")
        String employeeNo,

        @Size(max = 32, message = "科室编码长度不能超过 32")
        String orgCode,

        /** 科室绑定；null 表示不修改，空数组表示清空（临床角色会被拒绝）。 */
        @Size(max = 20, message = "一个账号最多绑定 20 个科室")
        List<UUID> deptIds,

        @NotNull(message = "版本号不能为空（用于并发控制）")
        Long version
) {
}
