package com.medassist.auth.domain;

/**
 * 租户资源配额（Cell 规划用，不是授权书里的商务配额）。
 *
 * <p>授权配额（床位数/账号数/AI 调用数）由 license 服务按合同签发并计费；
 * 这里的数字回答的是另一个问题：<b>这个 Cell 需要多大</b>——
 * 数据库容量、并发账号、AI 日调用上限，用于开通时选择 Cell 规格与容量预警。
 *
 * <p>字段全部可空：{@code null} 表示"按版本默认值"，0 是非法的（会造成开通即超限）。
 */
public record TenantQuotas(
        Integer maxBeds,
        Integer maxUsers,
        Integer maxStorageGb,
        Integer maxAiCallsPerDay
) {

    /** 新建/更新时的上界：防止一个手滑的 10000000 把容量模型打穿。 */
    private static final int MAX_VALUE = 1_000_000;

    public static TenantQuotas empty() {
        return new TenantQuotas(null, null, null, null);
    }

    /** 非法配额一律拒绝（负数或天文数字），不做静默纠正。 */
    public boolean valid() {
        return nonNegativeWithinLimit(maxBeds)
                && nonNegativeWithinLimit(maxUsers)
                && nonNegativeWithinLimit(maxStorageGb)
                && nonNegativeWithinLimit(maxAiCallsPerDay);
    }

    private static boolean nonNegativeWithinLimit(Integer value) {
        return value == null || (value > 0 && value <= MAX_VALUE);
    }
}
