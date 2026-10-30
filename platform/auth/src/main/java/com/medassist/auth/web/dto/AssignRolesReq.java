package com.medassist.auth.web.dto;

import jakarta.validation.constraints.NotEmpty;

import java.util.List;

/** 角色分配请求（全量替换语义）。 */
public record AssignRolesReq(
        @NotEmpty(message = "至少选择一个角色")
        List<String> roles
) {
}
