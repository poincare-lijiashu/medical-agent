package com.medassist.auth.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * 账号聚合（只读视图对象）。
 *
 * <p>不含角色集合——角色经 {@code user_role} 关联查询，避免聚合过大；
 * 调用方需要角色时显式查询 {@code RoleRepository#codesOfUser}。
 *
 * <p>{@code mustChangePassword}（§285）不参与鉴权：它约束的是"首登必须改密"的交互闭环，
 * 因此放在账号聚合上，由登录响应与 {@code /auth/me} 一并下发，前端据此阻断工作台。
 *
 * <p>{@code tenantCode}（§281 / ADR-001）是<b>身份归属</b>：一个账号只属于一家医院。
 * 它不参与临床数据过滤——临床数据面按 Cell 物理隔离，不存在跨租户数据；
 * 它的用途是"这家医院的账号只能出现在这家医院的管理清单里"以及令牌 {@code tenant} 声明。
 */
public record UserAccount(
        UUID id,
        String username,
        String passwordHash,
        String displayName,
        String employeeNo,
        String orgCode,
        String status,
        /** 口令是他人代设的临时凭据（§285）：登录后必须自行改密，改密成功即清零。 */
        boolean mustChangePassword,
        long version,
        Instant lastLoginAt,
        Instant createdAt,
        Instant updatedAt,
        String tenantCode
) {

    public static final String STATUS_ACTIVE = "ACTIVE";
    public static final String STATUS_DISABLED = "DISABLED";
    public static final String STATUS_LOCKED = "LOCKED";

    public boolean active() {
        return STATUS_ACTIVE.equals(status);
    }
}
