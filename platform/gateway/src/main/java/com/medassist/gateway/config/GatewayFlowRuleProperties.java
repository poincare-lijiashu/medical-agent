package com.medassist.gateway.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.util.List;

/**
 * 网关入口限流与熔断配置。
 *
 * <p>用**列表**而非 Map：Spring 的宽松绑定会把 Map 键里的 {@code /} 去掉
 * （{@code /api/v2/auth} 被绑定成 {@code apiv2auth}，2026-10-30 实测），
 * 导致规则资源名与请求路径永远不匹配、限流静默失效。
 *
 * @param enabled      总开关
 * @param flowRules    限流规则：路径前缀 → 每秒请求数；未命中的路径不限流
 * @param degradeRules 熔断规则：路径前缀 → 慢调用阈值；未命中的路径不熔断
 */
@ConfigurationProperties(prefix = "medassist.gateway.sentinel")
public record GatewayFlowRuleProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue List<FlowRuleSpec> flowRules,
        @DefaultValue List<DegradeRuleSpec> degradeRules
) {

    /**
     * 单条限流规则。
     *
     * @param path 路径前缀（如 {@code /api/v2/auth}）
     * @param qps  每秒允许请求数
     */
    public record FlowRuleSpec(String path, int qps) {
    }

    /**
     * 单条熔断规则（慢调用比例）。
     *
     * <p>为什么用「慢调用比例」而不是「异常比例」：504 的成因是下游变慢而不是抛异常，
     * 异常比例在慢下游场景下几乎不会触发。
     *
     * @param path             路径前缀
     * @param slowRtMs         慢调用阈值（毫秒），单次调用超过即记为慢调用
     * @param slowRatio        慢调用比例阈值（0~1），超过则开启熔断
     * @param minRequestAmount 统计窗口内的最小请求数，低于该值不熔断（避免低流量误判）
     * @param statIntervalMs   统计窗口（毫秒）
     * @param timeWindowSec    熔断持续时长（秒），到期后半开
     */
    public record DegradeRuleSpec(String path, long slowRtMs, double slowRatio,
                                  int minRequestAmount, int statIntervalMs,
                                  int timeWindowSec) {
    }
}
