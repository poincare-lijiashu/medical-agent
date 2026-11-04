package com.medassist.clinical.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * 报表订阅与投递契约（G19）。
 */
public final class ReportSubscriptionDtos {

    private ReportSubscriptionDtos() {
    }

    public record SubscriptionView(UUID id, String name, String reportCode, String channel,
                                   String target, boolean enabled, long version,
                                   String createdBy, Instant updatedAt) {
    }

    /** 新建/更新订阅；更新时必须带当前 version（CAS）。 */
    public record SaveSubscriptionReq(
            @NotBlank(message = "订阅名称不能为空")
            @Size(max = 80, message = "订阅名称最长 80 字")
            String name,
            @NotBlank(message = "渠道不能为空")
            String channel,
            String target,
            Boolean enabled,
            Long version) {
    }

    public record DeliveryView(UUID id, UUID subscriptionId, String subscriptionName,
                               String reportCode, LocalDate reportDate, String status,
                               int attempts, String lastError, Instant createdAt,
                               Instant sentAt) {
    }

    public record DeliveryListView(List<DeliveryView> deliveries) {
    }
}
