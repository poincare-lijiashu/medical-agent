package com.medassist.gateway.filter;

import com.alibaba.csp.sentinel.Entry;
import com.alibaba.csp.sentinel.EntryType;
import com.alibaba.csp.sentinel.ResourceTypeConstants;
import com.alibaba.csp.sentinel.SphU;
import com.alibaba.csp.sentinel.slots.block.BlockException;
import com.medassist.common.api.ErrorCode;
import com.medassist.common.security.AuthHeaders;
import com.medassist.gateway.config.GatewayFlowRuleProperties;
import com.medassist.gateway.web.GatewayResponses;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.Comparator;
import java.util.List;

/**
 * 入口限流过滤器（Sentinel 核心 API，路径前缀维度）。
 *
 * <p>职责边界：登录失败的账号维度限速在 auth 服务内（按 username/IP 落库统计）；
 * 本过滤器是**容量保护**——按接口族削峰，避免单一调用方把下游打满。
 *
 * <p>顺序 -50：在鉴权（-100）之后、路由转发之前。未认证流量先被 401 拒绝，
 * 不计入业务容量；白名单（登录/刷新）仍会被限流，这是刻意的（登录是最易被刷的入口）。
 */
@Component
public class RouteRateLimitFilter implements GlobalFilter, Ordered {

    private static final Logger log = LoggerFactory.getLogger(RouteRateLimitFilter.class);

    private final List<GatewayFlowRuleProperties.FlowRuleSpec> rules;

    public RouteRateLimitFilter(GatewayFlowRuleProperties props) {
        // 长前缀优先：/api/v2/auth 先于 /api/v2 命中
        this.rules = props.flowRules() == null ? List.of()
                : props.flowRules().stream()
                .filter(r -> r.path() != null && !r.path().isBlank())
                .sorted(Comparator.comparingInt((GatewayFlowRuleProperties.FlowRuleSpec r) -> r.path().length()).reversed())
                .toList();
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String path = exchange.getRequest().getPath().pathWithinApplication().value();
        String resource = matchResource(path);
        if (resource == null) {
            return chain.filter(exchange);
        }

        Entry entry;
        try {
            entry = SphU.entry(resource, ResourceTypeConstants.COMMON_API_GATEWAY, EntryType.IN);
        } catch (BlockException e) {
            log.warn("gateway.rate_limited resource={} path={}", resource, path);
            String traceId = exchange.getRequest().getHeaders().getFirst(AuthHeaders.TRACE_ID);
            return GatewayResponses.reject(exchange, HttpStatus.TOO_MANY_REQUESTS,
                    ErrorCode.TOO_MANY_REQUESTS, "请求过于频繁，请稍后重试", traceId);
        }
        // 必须在请求完成后退出 entry，否则滑动窗口计数泄漏（Sentinel 要求成对调用）
        return chain.filter(exchange).doFinally(signal -> entry.exit());
    }

    @Override
    public int getOrder() {
        return -50;
    }

    private String matchResource(String path) {
        for (GatewayFlowRuleProperties.FlowRuleSpec rule : rules) {
            if (path.startsWith(rule.path())) {
                return rule.path();
            }
        }
        return null;
    }
}
