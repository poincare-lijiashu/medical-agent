package com.medassist.auth.web.dto;

import jakarta.validation.constraints.Size;

/**
 * 管理员重置口令请求（§285）。
 *
 * <p>{@code newPassword} 为空表示"由系统生成一次性临时口令"——这是推荐路径：
 * 管理员不必替别人想口令，也就不会出现全院共用 {@code Hospital123} 的现场习惯。
 * 非空时按 {@code PasswordPolicy} 校验（最小长度留给策略，避免"生成模式"被 DTO 校验挡住）。
 */
public record ResetPasswordReq(
        @Size(max = 64, message = "密码长度不能超过 64 位")
        String newPassword
) {
}
