package com.medassist.auth.web.dto;

import jakarta.validation.constraints.NotBlank;

/** 刷新令牌请求。 */
public record RefreshReq(
        @NotBlank(message = "refreshToken 不能为空") String refreshToken
) {
}
