package com.medassist.auth.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.UUID;

/** 新建账号请求。 */
public record CreateUserReq(
        @NotBlank(message = "用户名不能为空")
        @Size(max = 64, message = "用户名长度不能超过 64")
        String username,

        @NotBlank(message = "密码不能为空")
        @Size(min = 8, max = 64, message = "密码长度需为 8-64 位")
        String password,

        @NotBlank(message = "姓名不能为空")
        @Size(max = 64, message = "姓名长度不能超过 64")
        String displayName,

        @Size(max = 32, message = "工号长度不能超过 32")
        String employeeNo,

        @Size(max = 32, message = "科室编码长度不能超过 32")
        String orgCode,

        /** 临床科室 UUID 列表（多科室执业）；医师/护士至少绑定一个。 */
        @Size(max = 20, message = "一个账号最多绑定 20 个科室")
        List<UUID> deptIds,

        List<String> roles
) {
}
