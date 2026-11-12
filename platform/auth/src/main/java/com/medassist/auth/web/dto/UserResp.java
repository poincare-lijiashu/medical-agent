package com.medassist.auth.web.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** 账号对外视图（绝不含 password_hash）。 */
public record UserResp(
        UUID id,
        String username,
        String displayName,
        String employeeNo,
        String orgCode,
        List<UUID> deptIds,
        List<UUID> campusIds,
        String status,
        List<String> roles,
        long version,
        Instant lastLoginAt,
        Instant createdAt,
        /** 账号所属租户（ADR-001）；管理端按此确认"我在维护哪家医院的账号"。 */
        String tenantCode
) {
}
