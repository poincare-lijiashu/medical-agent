package com.medassist.clinical.web;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * 报表订阅与投递契约（G19 / G25）。
 */
public final class ReportSubscriptionDtos {

    private ReportSubscriptionDtos() {
    }

    /** 订阅视图：发送时段格式化为 HH:mm，便于前端直接展示与编辑。 */
    public record SubscriptionView(UUID id, String name, String reportCode, String channel,
                                   String target, String sendTime, UUID recipientGroupId,
                                   boolean enabled, long version, String createdBy,
                                   Instant updatedAt) {
    }

    /** 新建/更新订阅；更新时必须带当前 version（CAS）。 */
    public record SaveSubscriptionReq(
            @NotBlank(message = "订阅名称不能为空")
            @Size(max = 80, message = "订阅名称最长 80 字")
            String name,
            @NotBlank(message = "渠道不能为空")
            String channel,
            String target,
            @Pattern(regexp = "([01]\\d|2[0-3]):[0-5]\\d", message = "发送时段格式为 HH:mm")
            String sendTime,
            UUID recipientGroupId,
            Boolean enabled,
            Long version) {

        /** 默认发送时段 00:30、不绑定分组（历史调用与测试用）。 */
        public SaveSubscriptionReq(String name, String channel, String target, Boolean enabled,
                                   Long version) {
            this(name, channel, target, null, null, enabled, version);
        }
    }

    public record DeliveryView(UUID id, UUID subscriptionId, String subscriptionName,
                               String reportCode, LocalDate reportDate, String status,
                               int attempts, String lastError, Instant createdAt,
                               Instant sentAt) {
    }

    public record DeliveryListView(List<DeliveryView> deliveries) {
    }

    // ---------------------------------------------------------------- 收件人分组（G25）

    /** 分组成员视图。 */
    public record GroupMemberView(UUID id, String email, String displayName, boolean enabled) {
    }

    /** 分组视图（含成员，便于订阅页展开维护）。 */
    public record RecipientGroupView(UUID id, String code, String name, String description,
                                     boolean enabled, long version, String createdBy,
                                     Instant updatedAt, List<GroupMemberView> members) {
    }

    public record SaveRecipientGroupReq(
            @NotBlank @Size(max = 32)
            @Pattern(regexp = "[A-Za-z0-9_-]{2,32}",
                    message = "分组编码只允许字母、数字、下划线和中划线（2–32 位）")
            String code,
            @NotBlank @Size(max = 64) String name,
            @Size(max = 200) String description,
            Boolean enabled,
            Long version) {
    }

    public record GroupMemberReq(
            @NotBlank @Email(message = "邮箱格式不正确") @Size(max = 254) String email,
            @Size(max = 64) String displayName,
            Boolean enabled) {
    }

    /** 整组替换成员：一次性提交，事务内删旧插新。 */
    public record SaveGroupMembersReq(@Size(max = 10) List<@Valid GroupMemberReq> members) {
    }
}
