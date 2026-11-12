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
        /** 口令为他人代设的临时凭据（§285）：管理端可据此识别"尚未完成首登改密"的账号。 */
        boolean mustChangePassword,
        List<String> roles,
        long version,
        Instant lastLoginAt,
        Instant createdAt,
        /** 账号所属租户（ADR-001）；管理端按此确认"我在维护哪家医院的账号"。 */
        String tenantCode
) {
}
