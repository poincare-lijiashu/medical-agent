package com.medassist.auth.web.dto;

import jakarta.validation.constraints.NotBlank;

/** 内部令牌校验请求（仅供集群内服务排障使用，网关对该路径直接拒绝，不对外暴露）。 */
public record VerifyTokenReq(
        @NotBlank(message = "token 不能为空") String token
) {
}
