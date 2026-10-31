package com.medassist.common.security;

import org.springframework.core.env.Environment;

/**
 * 安全相关的默认值（仅用于本地开发兜底）。
 *
 * <p>{@link #DEV_JWT_SECRET} 会出现在 application.yml 的 {@code ${JWT_SECRET:...}} 默认位，
 * 目的是让本地 {@code java -jar} 无需额外设置环境变量即可联调。两个服务启动时会检测该值：
 * <ul>
 *   <li>非生产 profile：打印告警，允许启动；</li>
 *   <li>生产 profile（{@code prod}/{@code production}/{@code staging}）：<b>直接拒绝启动</b>——
 *       仓库内的公开默认密钥一旦进了预发布/生产，JWT 可被任意伪造，仅靠日志告警是无效防线。</li>
 * </ul>
 */
public final class SecurityDefaults {

    /** 本地开发默认 JWT 密钥（≥32 字节，满足 HS256 强度要求）。 */
    public static final String DEV_JWT_SECRET = "<dev-jwt-secret>";

    /** 令牌签发方标识，网关校验时校验 iss，防止跨系统令牌混用。 */
    public static final String DEFAULT_ISSUER = "medassist-auth";

    private SecurityDefaults() {
    }

    /** 是否处于会承接真实流量的环境（staging 也按生产口径处理，避免"预发布用弱密钥"的滑坡）。 */
    public static boolean isProduction(Environment env) {
        if (env == null) {
            return false;
        }
        for (String profile : env.getActiveProfiles()) {
            String p = profile.toLowerCase();
            if (p.equals("prod") || p.equals("production") || p.equals("staging")) {
                return true;
            }
        }
        return false;
    }
}
