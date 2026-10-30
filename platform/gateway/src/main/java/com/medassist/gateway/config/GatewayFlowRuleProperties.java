package com.medassist.gateway.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.util.List;

/**
 * 网关入口限流配置。
 *
 * <p>用**列表**而非 Map：Spring 的宽松绑定会把 Map 键里的 {@code /} 去掉
 * （{@code /api/v2/auth} 被绑定成 {@code apiv2auth}，2026-10-30 实测），
 * 导致规则资源名与请求路径永远不匹配、限流静默失效。
 *
 * @param enabled   总开关
 * @param flowRules 规则列表：路径前缀 → 每秒请求数；未命中的路径不限流
 */
@ConfigurationProperties(prefix = "medassist.gateway.sentinel")
public record GatewayFlowRuleProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue List<FlowRuleSpec> flowRules
) {

    /**
     * 单条限流规则。
     *
     * @param path 路径前缀（如 {@code /api/v2/auth}）
     * @param qps  每秒允许请求数
     */
    public record FlowRuleSpec(String path, int qps) {
    }
}
