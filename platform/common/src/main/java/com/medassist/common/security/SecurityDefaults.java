package com.medassist.common.security;

/**
 * 安全相关的默认值（仅用于本地开发兜底）。
 *
 * <p>{@link #DEV_JWT_SECRET} 会出现在 application.yml 的 {@code ${JWT_SECRET:...}} 默认位，
 * 目的是让本地 {@code java -jar} 无需额外设置环境变量即可联调。两个服务启动时会检测该值
 * 并打印告警；<b>生产环境必须通过环境变量或密钥管理下发真实密钥</b>。
 */
public final class SecurityDefaults {

    /** 本地开发默认 JWT 密钥（≥32 字节，满足 HS256 强度要求）。 */
    public static final String DEV_JWT_SECRET = "<dev-jwt-secret>";

    /** 令牌签发方标识，网关校验时校验 iss，防止跨系统令牌混用。 */
    public static final String DEFAULT_ISSUER = "medassist-auth";

    private SecurityDefaults() {
    }
}
