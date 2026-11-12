package com.medassist.auth.web.dto;

/**
 * 管理员重置口令结果（§285）。
 *
 * @param generated         是否由服务端生成临时口令
 * @param temporaryPassword 一次性临时口令；仅 {@code generated=true} 时返回，且只出现这一次
 */
public record ResetPasswordResp(boolean generated, String temporaryPassword) {
}
