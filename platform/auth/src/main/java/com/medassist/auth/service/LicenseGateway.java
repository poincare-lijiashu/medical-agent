package com.medassist.auth.service;

import java.time.Instant;

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

    record Result(State state, String licenseNo, String status, String editionCode,
                  Instant expiresAt, int modules, String error) {
    }
}
