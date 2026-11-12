package com.medassist.auth.service;

import java.time.Instant;
import java.util.List;

/**
 * 授权检查端口（§284）。
 *
 * <p>开通一家医院是否必须有授权，取决于交付模式：厂商直营的云上 Cell 通常"先有合同再开通"，
 * 私有化离线交付则可能先交付后补授权。因此这里返回的是**事实 + 状态**，
 * 由开通参数决定"缺失是否阻塞"——而不是把策略硬编码在客户端。
 */
public interface LicenseGateway {

    Result check(String instanceCode);

    enum State {
        /** 本部署未配置授权服务地址：本地联调/离线交付。 */
        DISABLED,
        /** 找到授权记录（可能是 ACTIVE，也可能是 REVOKED/EXPIRED）。 */
        FOUND,
        /** 授权服务可达，但该实例没有任何授权记录。 */
        NOT_FOUND,
        /** 授权服务不可达/返回异常：不能据此判断"无授权"。 */
        UNREACHABLE
    }

    /**
     * 授权摘要（§288）。
     *
     * @param status      数据库状态：ACTIVE / REVOKED / SUPERSEDED（人工动作）
     * @param licensePhase 时间事实：ACTIVE / PENDING / GRACE / EXPIRED / REVOKED / NONE
     * @param modules     已授权模块编码（开通时用于核对版本能力）
     * @param maxBeds / maxUsers / maxAiCallsPerDay 合同上限；null 表示不限
     */
    record Result(State state, String licenseNo, String status, String licensePhase,
                  String editionCode, Instant expiresAt, List<String> modules,
                  Integer maxBeds, Integer maxUsers, Integer maxAiCallsPerDay, int graceDays,
                  String error) {
    }
}
