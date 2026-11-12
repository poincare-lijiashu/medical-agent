package com.medassist.auth.domain;

import java.time.Instant;

/**
 * 租户（医院）台账（第 281 批 / ADR-001）。
 *
 * <p>语义边界（实现时不要放宽）：
 * <ul>
 *   <li>租户 = 一家医院 = 一个独立 Cell；院区（{@code campus}）是租户<em>内部</em>的多院区层级，
 *       两者不是替代关系；</li>
 *   <li>本聚合只描述控制面元数据（实例码、入口、数据库主机、配额），
 *       <b>不含任何患者数据</b>，也不参与临床读路径；</li>
 *   <li>{@code code} 与 {@code instanceCode} 创建后不可修改：前者是审计与令牌声明的主键，
 *       后者与授权书绑定（改它等于把一家医院的授权挪给另一家）。</li>
 * </ul>
 */
public record Tenant(
        String code,
        String instanceCode,
        String name,
        String editionCode,
        String status,
        String cellEndpoint,
        String dbHost,
        String region,
        TenantQuotas quotas,
        String contactName,
        String contactPhone,
        String notes,
        long version,
        String createdBy,
        Instant createdAt,
        Instant updatedAt
) {

    /** 待开通：目录已建，Cell 尚未交付（此时不允许登录）。 */
    public static final String PENDING = "PENDING";
    /** 在用。 */
    public static final String ACTIVE = "ACTIVE";
    /** 停用：禁止登录、令牌由网关拦截，数据完整保留，可恢复。 */
    public static final String SUSPENDED = "SUSPENDED";
    /** 注销：终态，数据按合同保留期冻结，不允许再开通。 */
    public static final String CLOSED = "CLOSED";

    public boolean active() {
        return ACTIVE.equals(status);
    }

    public boolean closed() {
        return CLOSED.equals(status);
    }

    /** 状态机：定义"允许的流转"，其余一律拒绝（不做隐式纠正）。 */
    public boolean canTransitionTo(String target) {
        if (target == null || target.equals(status)) {
            return false;
        }
        return switch (status) {
            case PENDING -> ACTIVE.equals(target) || CLOSED.equals(target);
            case ACTIVE -> SUSPENDED.equals(target) || CLOSED.equals(target);
            case SUSPENDED -> ACTIVE.equals(target) || CLOSED.equals(target);
            default -> false;
        };
    }
}
