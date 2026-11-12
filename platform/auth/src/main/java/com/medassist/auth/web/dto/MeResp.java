package com.medassist.auth.web.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 当前登录人视图（前端用于菜单/按钮级授权渲染）。
 *
 * <p>{@code roles} 与 {@code permissions} 均为服务端实时计算，不依赖令牌内快照，
 * 因此角色变更后下一次请求即生效（令牌只用于身份识别）。
 */
public record MeResp(
        UUID id,
        String username,
        String displayName,
        String employeeNo,
        String orgCode,
        List<UUID> deptIds,
        List<String> roles,
        List<String> permissions,
        Instant lastLoginAt,
        /** 账号所属租户（ADR-001）；前端在页头展示"当前院区/实例"，避免在错误的入口操作。 */
        String tenantCode
) {
}
