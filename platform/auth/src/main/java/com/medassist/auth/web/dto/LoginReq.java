package com.medassist.auth.web.dto;

import jakarta.validation.constraints.NotBlank;

/** 登录请求（工号或登录名 + 密码）。 */
public record LoginReq(
        @NotBlank(message = "请输入用户名") String username,
        @NotBlank(message = "请输入密码") String password
) {
}
