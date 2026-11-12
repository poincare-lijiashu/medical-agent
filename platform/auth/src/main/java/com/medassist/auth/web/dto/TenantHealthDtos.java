package com.medassist.auth.web.dto;

import java.time.Instant;
import java.util.List;

/** 租户健康度视图（§284）：平台运营一眼看出"哪家医院需要人过去"。 */
public final class TenantHealthDtos {

    private TenantHealthDtos() {
    }

    /**
     * @param state  OK / PENDING / SUSPENDED / CLOSED / DEGRADED / DOWN
     * @param issues 已排序的可读问题列表；OK 时为空
     */
    public record HealthView(
            String tenantCode,
            String name,
            String status,
            String instanceCode,
            boolean cellReachable,
            Long latencyMs,
            String authVersion,
            boolean authReady,
            boolean migrationFailure,
            int servicesTracked,
            Instant lastMigrationAt,
            long userCount,
            long activeUserCount,
            String lastProvisionStatus,
            Instant lastProvisionAt,
            String state,
            List<String> issues,
            Instant checkedAt
    ) {
    }

    public record HealthList(List<HealthView> items, int total, Instant generatedAt) {
    }
}
