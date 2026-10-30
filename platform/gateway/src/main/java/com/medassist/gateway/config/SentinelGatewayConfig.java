package com.medassist.gateway.config;

import com.alibaba.csp.sentinel.slots.block.RuleConstant;
import com.alibaba.csp.sentinel.slots.block.flow.FlowRule;
import com.alibaba.csp.sentinel.slots.block.flow.FlowRuleManager;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 网关入口限流规则（Sentinel 核心 API，路径前缀维度）。
 *
 * <p>为什么不用 Sentinel 的 Spring Cloud Gateway Adapter：实测 1.8.9 适配器与
 * Spring Cloud Gateway 5.x / Spring 7 组合会在每个请求上抛
 * {@code ParamFlowException: $D}（规则本身无 paramItem），导致全量 500；
 * 关闭 SCA 的 WebFlux 过滤器后仍复现。因此改用 Sentinel **核心**资源 API：
 * 在自研 {@code RouteRateLimitFilter} 内 {@code SphU.entry(前缀资源)}，规则由本类装载。
 * 保留了 Sentinel 的滑动窗口与规则模型，同时不依赖不兼容的适配层（2026-10-30 实测固化）。
 *
 * <p>资源粒度：按路径前缀（如 {@code /api/v2/auth}）而非路由 ID——路由属性要到
 * 路由解析阶段才存在，前缀匹配可以在鉴权之前/之后任意位置稳定取到。
 */
@Configuration
@EnableConfigurationProperties(GatewayFlowRuleProperties.class)
public class SentinelGatewayConfig {

    private static final Logger log = LoggerFactory.getLogger(SentinelGatewayConfig.class);

    private final GatewayFlowRuleProperties props;

    public SentinelGatewayConfig(GatewayFlowRuleProperties props) {
        this.props = props;
    }

    @PostConstruct
    public void init() {
        if (!props.enabled() || props.flowRules() == null || props.flowRules().isEmpty()) {
            log.info("sentinel.gateway.disabled");
            return;
        }
        List<FlowRule> rules = new ArrayList<>();
        for (GatewayFlowRuleProperties.FlowRuleSpec spec : props.flowRules()) {
            if (spec.path() == null || spec.path().isBlank() || spec.qps() <= 0) {
                continue;
            }
            FlowRule rule = new FlowRule(spec.path());
            rule.setGrade(RuleConstant.FLOW_GRADE_QPS);
            rule.setCount(spec.qps());
            rules.add(rule);
        }
        FlowRuleManager.loadRules(rules);
        log.info("sentinel.gateway.rules.loaded resources={}", rules.size());
    }
}
