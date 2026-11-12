package com.medassist.auth.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 租户与实例配置（ADR-001）。
 *
 * @param defaultCode  默认租户编码：入口 Host 未映射到任何租户时（本地联调、单院私有化）
 *                     统一落到该租户；单实例部署应指向本机租户台账里的那一行
 * @param instanceCode 本部署实例码，写入令牌 {@code instance} 声明。与授权书
 *                     {@code LICENSE_INSTANCE_CODE} 取同一值；为空表示不做实例绑定校验
 *                     （仅限本地联调，生产必须配置）
 * @param enforceActive 登录时是否强制校验租户状态为 ACTIVE。默认 true：
 *                     停用医院必须立即禁止登录，而不是"靠人记得手动停账号"
 */
@ConfigurationProperties(prefix = "medassist.tenant")
public record TenantProperties(
        @DefaultValue("tenant-001") String defaultCode,
        String instanceCode,
        @DefaultValue("true") boolean enforceActive
) {

    /** 令牌/审计里使用的租户编码：配置为空时回落到 tenant-001，绝不下发空声明。 */
    public String effectiveDefaultCode() {
        return defaultCode == null || defaultCode.isBlank() ? "tenant-001" : defaultCode.trim();
    }

    /** 实例码（可能为空：单机联调不校验实例绑定）。 */
    public String effectiveInstanceCode() {
        return instanceCode == null || instanceCode.isBlank() ? null : instanceCode.trim();
    }
}
