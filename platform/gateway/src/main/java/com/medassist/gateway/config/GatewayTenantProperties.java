package com.medassist.gateway.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.util.List;
import java.util.Locale;

/**
 * 租户路由配置（§281 / ADR-001）。
 *
 * <p>职责边界：网关只做"入口 → 租户"的解析与一致性校验，不做租户内的业务授权。
 *
 * <p><b>enabled=false（本地联调默认）</b>：不要求 Host 映射、不要求令牌带 tenant 声明，
 * 一律按 {@code defaultTenant} 注入 {@code X-Tenant}——单院私有化部署就是这个形态。
 * <b>enabled=true（多院控制面）</b>：Host 必须映射到租户、令牌必须带 tenant 声明且与
 * Host 一致、instance 声明必须等于本 Cell 实例码，否则 403；这一层是"拿 A 院令牌打 B 院"
 * 的唯一拦截点，关掉它等于放弃隔离契约。
 *
 * @param enabled      是否启用多租户入口校验（生产多院部署必须为 true）
 * @param defaultTenant 未映射时的默认租户（enabled=false 时用于注入 X-Tenant）
 * @param instanceCode 本 Cell 实例码；配置后令牌 instance 声明必须与之一致
 * @param hosts        Host → 租户映射，元素形如 {@code hospital-a.example=tenant-001}；
 *                     支持精确与通配（{@code *.example.com=tenant-001}）。
 *                     用列表而非 Map：环境变量 {@code TENANT_HOSTS} 只能注入逗号串，
 *                     Map 绑定会直接失败
 */
@ConfigurationProperties(prefix = "medassist.gateway.tenant")
public record GatewayTenantProperties(
        @DefaultValue("false") boolean enabled,
        @DefaultValue("tenant-001") String defaultTenant,
        String instanceCode,
        @DefaultValue List<String> hosts
) {

    /** 映射分隔符（host=tenant）。 */
    private static final String SEPARATOR = "=";

    public String effectiveDefaultTenant() {
        return defaultTenant == null || defaultTenant.isBlank() ? "tenant-001" : defaultTenant.trim();
    }

    public String effectiveInstanceCode() {
        return instanceCode == null || instanceCode.isBlank() ? null : instanceCode.trim();
    }

    /**
     * 解析入口 Host 对应的租户。
     *
     * <p>匹配顺序：先精确，再最长后缀通配（{@code *.b.example} 优先于 {@code *.example}），
     * 保证子域不会落到比它更宽的规则上。
     *
     * @return 租户编码；未配置映射或未命中返回 {@code null}（由调用方决定放行还是拒绝）
     */
    public String resolveHost(String host) {
        if (host == null || host.isBlank() || hosts == null || hosts.isEmpty()) {
            return null;
        }
        String normalized = normalize(host);
        String exact = lookup(normalized);
        if (exact != null) {
            return exact;
        }
        int idx = normalized.indexOf('.');
        while (idx > 0) {
            String tenant = lookup("*" + normalized.substring(idx));
            if (tenant != null) {
                return tenant;
            }
            idx = normalized.indexOf('.', idx + 1);
        }
        return null;
    }

    /** 去掉端口、转小写：容器/反代经常把 {@code Host:port} 原样带进来。 */
    private static String normalize(String host) {
        String normalized = host.trim().toLowerCase(Locale.ROOT);
        int colon = normalized.indexOf(':');
        return colon > 0 ? normalized.substring(0, colon) : normalized;
    }

    private String lookup(String key) {
        for (String entry : hosts) {
            if (entry == null) {
                continue;
            }
            int sep = entry.indexOf(SEPARATOR);
            if (sep <= 0 || sep == entry.length() - 1) {
                continue;
            }
            String pattern = normalize(entry.substring(0, sep));
            if (pattern.equals(key)) {
                String tenant = entry.substring(sep + 1).trim();
                return tenant.isEmpty() ? null : tenant;
            }
        }
        return null;
    }
}
