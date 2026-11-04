package com.medassist.auth.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.List;

/**
 * 统一身份 SSO 配置（G42，OIDC 授权码 + PKCE）。
 *
 * <p>所有取值只从部署配置/环境变量注入；未启用时相关接口返回 40110，
 * 不影响本地账号密码登录。client-secret 不落库、不写日志。
 *
 * @param enabled         是否启用 SSO 登录
 * @param provider        提供方标识（写入 sso_identity.provider，默认 OIDC）
 * @param issuer          IdP 的 issuer（用于发现文档与 id_token iss 校验）
 * @param clientId        本系统在 IdP 注册的 client_id
 * @param clientSecret    本系统在 IdP 注册的 client_secret
 * @param redirectUri     授权码回调地址（必须与 IdP 注册值一致）
 * @param scopes          申请的 scope（默认 openid profile email）
 * @param timeout         访问 IdP 的连接/读取超时
 * @param usernameClaim   映射本地账号的用户名声明
 * @param emailClaim      邮箱声明
 * @param nameClaim       显示名声明
 * @param autoProvision   本地无同账号时是否自动开户
 * @param defaultRoles    自动开户时授予的角色
 * @param defaultOrgCode  自动开户时的科室编码（为空则由管理员后续绑定）
 */
@ConfigurationProperties("medassist.auth.sso")
public record SsoProperties(
        boolean enabled,
        String provider,
        String issuer,
        String clientId,
        String clientSecret,
        String redirectUri,
        List<String> scopes,
        Duration timeout,
        String usernameClaim,
        String emailClaim,
        String nameClaim,
        boolean autoProvision,
        List<String> defaultRoles,
        String defaultOrgCode) {

    public SsoProperties {
        provider = blankTo(provider, "OIDC");
        scopes = scopes == null || scopes.isEmpty() ? List.of("openid", "profile", "email") : List.copyOf(scopes);
        timeout = timeout == null ? Duration.ofSeconds(3) : timeout;
        usernameClaim = blankTo(usernameClaim, "preferred_username");
        emailClaim = blankTo(emailClaim, "email");
        nameClaim = blankTo(nameClaim, "name");
        defaultRoles = defaultRoles == null ? List.of() : List.copyOf(defaultRoles);
    }

    /** 配置是否完整；不完整时按"未启用"处理，避免半配置状态发起不可用登录。 */
    public boolean configured() {
        return enabled && !isBlank(issuer) && !isBlank(clientId)
                && !isBlank(clientSecret) && !isBlank(redirectUri);
    }

    private static String blankTo(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
