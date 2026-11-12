package com.medassist.auth.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 自助改密请求（§285）。
 *
 * <p>必须携带当前口令：仅有访问令牌不足以改密。否则共用工作站上"人走屏留"时，
 * 下一位使用者可以直接把口令换掉，把账号锁在门外。
 */
public record ChangePasswordReq(
        @NotBlank(message = "当前密码不能为空")
        @Size(max = 64, message = "当前密码长度不能超过 64 位")
        String currentPassword,

        @NotBlank(message = "新密码不能为空")
        @Size(min = 8, max = 64, message = "新密码长度需为 8-64 位")
        String newPassword
) {
}
