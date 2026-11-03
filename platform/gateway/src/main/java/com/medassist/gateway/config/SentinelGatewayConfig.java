package com.medassist.gateway.config;

import com.alibaba.csp.sentinel.slots.block.RuleConstant;
import com.alibaba.csp.sentinel.slots.block.degrade.DegradeRule;
import com.alibaba.csp.sentinel.slots.block.degrade.DegradeRuleManager;
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
        loadFlowRules();
        loadDegradeRules();
    }

    private void loadFlowRules() {
        if (!props.enabled() || props.flowRules() == null || props.flowRules().isEmpty()) {
            // 配置为空意味着网关没有任何入口容量保护——必须显式告警（S20），
            // 否则"以为开了限流"会是生产事故的完美伏笔
            log.warn("sentinel.gateway.disabled（未配置任何入口限流规则，网关将不做削峰）");
            return;
        }
        List<FlowRule> rules = new ArrayList<>();
        int skipped = 0;
        for (GatewayFlowRuleProperties.FlowRuleSpec spec : props.flowRules()) {
            if (spec.path() == null || spec.path().isBlank() || spec.qps() <= 0) {
                log.warn("sentinel.gateway.rule.skipped path={} qps={}（path 为空或 qps<=0）",
                        spec.path(), spec.qps());
                skipped++;
                continue;
            }
            FlowRule rule = new FlowRule(spec.path());
            rule.setGrade(RuleConstant.FLOW_GRADE_QPS);
            rule.setCount(spec.qps());
            rules.add(rule);
        }
        if (rules.isEmpty()) {
            log.error("sentinel.gateway.rules.empty（全部 {} 条规则被跳过，入口无限流保护）", skipped);
            return;
        }
        FlowRuleManager.loadRules(rules);
        log.info("sentinel.gateway.rules.loaded resources={} skipped={}", rules.size(), skipped);
    }

    /**
     * 装载熔断规则（慢调用比例）。
     *
     * <p>没有熔断时，一个慢下游会把连接池与线程占满，整条路由族一起 504——
     * 用户在临床侧看到的是「整个系统卡死」，而不是「某个接口慢」。
     * 这里按路径前缀给最重的接口族兜底：慢调用占比超阈值即快速失败，给下游恢复窗口。
     */
    private void loadDegradeRules() {
        if (!props.enabled() || props.degradeRules() == null || props.degradeRules().isEmpty()) {
            log.warn("sentinel.gateway.degrade.disabled（未配置熔断规则，慢下游会拖垮整条路由族）");
            return;
        }
        List<DegradeRule> rules = new ArrayList<>();
        int skipped = 0;
        for (GatewayFlowRuleProperties.DegradeRuleSpec spec : props.degradeRules()) {
            if (spec.path() == null || spec.path().isBlank() || spec.slowRtMs() <= 0
                    || spec.slowRatio() <= 0 || spec.slowRatio() > 1) {
                log.warn("sentinel.gateway.degrade.skipped path={} rt={} ratio={}（参数不合法）",
                        spec.path(), spec.slowRtMs(), spec.slowRatio());
                skipped++;
                continue;
            }
            DegradeRule rule = new DegradeRule(spec.path());
            rule.setGrade(RuleConstant.DEGRADE_GRADE_RT);
            rule.setCount(spec.slowRtMs());
            rule.setSlowRatioThreshold(spec.slowRatio());
            rule.setMinRequestAmount(Math.max(1, spec.minRequestAmount()));
            rule.setStatIntervalMs(Math.max(1000, spec.statIntervalMs()));
            rule.setTimeWindow(Math.max(1, spec.timeWindowSec()));
            rules.add(rule);
        }
        if (rules.isEmpty()) {
            log.error("sentinel.gateway.degrade.empty（全部 {} 条熔断规则被跳过）", skipped);
            return;
        }
        DegradeRuleManager.loadRules(rules);
        log.info("sentinel.gateway.degrade.loaded rules={} skipped={}", rules.size(), skipped);
    }
}
