package com.medassist.emr.web;

import com.medassist.common.api.ErrorCode;
import com.medassist.common.exception.BusinessException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * 内网接口共享令牌校验。
 *
 * <p>内网接口不止一个（首页手术操作、病历归档体检），令牌比较必须只有一份实现：
 * 分散写三份，迟早有一份漏掉常量时间比较，变成可被计时攻击的口子。
 */
final class InternalToken {

    private InternalToken() {
    }

    /**
     * 校验 {@code X-Internal-Token}。
     *
     * <p>未配置令牌时一律拒绝（fail-closed），且错误文案统一泛化——
     * 不区分"未启用/令牌错误/令牌缺失"，避免成为探测接口是否存在的信号。
     */
    static void require(String configured, String provided) {
        if (configured == null || configured.isBlank()) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "内部接口不可用");
        }
        if (provided == null || !MessageDigest.isEqual(
                configured.getBytes(StandardCharsets.UTF_8), provided.getBytes(StandardCharsets.UTF_8))) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "内部接口不可用");
        }
    }
}
