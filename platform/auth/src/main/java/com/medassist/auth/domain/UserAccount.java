package com.medassist.auth.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * 账号聚合（只读视图对象）。
 *
 * <p>不含角色集合——角色经 {@code user_role} 关联查询，避免聚合过大；
 * 调用方需要角色时显式查询 {@code RoleRepository#codesOfUser}。
 */
public record UserAccount(
        UUID id,
        String username,
        String passwordHash,
        String displayName,
        String employeeNo,
        String orgCode,
        String status,
        long version,
        Instant lastLoginAt,
        Instant createdAt,
        Instant updatedAt
) {

    public static final String STATUS_ACTIVE = "ACTIVE";
    public static final String STATUS_DISABLED = "DISABLED";
    public static final String STATUS_LOCKED = "LOCKED";

    public boolean active() {
        return STATUS_ACTIVE.equals(status);
    }
}
