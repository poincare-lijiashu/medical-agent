package com.medassist.gateway.filter;

import com.alibaba.csp.sentinel.slots.block.RuleConstant;
import com.alibaba.csp.sentinel.slots.block.flow.FlowRule;
import com.alibaba.csp.sentinel.slots.block.flow.FlowRuleManager;
import com.medassist.gateway.config.GatewayFlowRuleProperties;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 入口限流过滤器测试：验证拦截、统一 429 壳、非规则路径放行。
 *
 * <p>规则为 0 QPS → 全部拦截；无需并发即可稳定断言。
 */
class RouteRateLimitFilterTest {

    @AfterEach
    void clearRules() {
        FlowRuleManager.loadRules(new ArrayList<>());
    }

    private static GatewayFlowRuleProperties props() {
        return new GatewayFlowRuleProperties(true, List.of(
                new GatewayFlowRuleProperties.FlowRuleSpec("/api/v2/auth", 0),
                new GatewayFlowRuleProperties.FlowRuleSpec("/api/v2/empi", 1000)));
    }

    private static void loadRules() {
        FlowRule blocked = new FlowRule("/api/v2/auth");
        blocked.setGrade(RuleConstant.FLOW_GRADE_QPS);
        blocked.setCount(0);
        FlowRule allowed = new FlowRule("/api/v2/empi");
        allowed.setGrade(RuleConstant.FLOW_GRADE_QPS);
        allowed.setCount(1000);
        FlowRuleManager.loadRules(List.of(blocked, allowed));
    }

    @Test
    void blocksWhenQpsExceededWithUnifiedEnvelope() {
        loadRules();
        RouteRateLimitFilter filter = new RouteRateLimitFilter(props(), new SimpleMeterRegistry());
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.post("/api/v2/auth/login").build());
        CapturingChain chain = new CapturingChain();

        filter.filter(exchange, chain).block();

        assertThat(chain.called).isFalse();
        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(body(exchange)).contains("\"code\":42900");
    }

    @Test
    void passesWhenUnderLimit() {
        loadRules();
        RouteRateLimitFilter filter = new RouteRateLimitFilter(props(), new SimpleMeterRegistry());
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/v2/empi/patients").build());
        CapturingChain chain = new CapturingChain();

        filter.filter(exchange, chain).block();

        assertThat(chain.called).isTrue();
    }

    @Test
    void passesPathsWithoutRule() {
        loadRules();
        RouteRateLimitFilter filter = new RouteRateLimitFilter(props(), new SimpleMeterRegistry());
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/actuator/health").build());
        CapturingChain chain = new CapturingChain();

        filter.filter(exchange, chain).block();

        assertThat(chain.called).isTrue();
    }

    private static String body(MockServerWebExchange exchange) {
        DataBuffer buffer = DataBufferUtils.join(exchange.getResponse().getBody()).block();
        assertThat(buffer).isNotNull();
        byte[] bytes = new byte[buffer.readableByteCount()];
        buffer.read(bytes);
        DataBufferUtils.release(buffer);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static final class CapturingChain implements GatewayFilterChain {

        private boolean called;
        private ServerWebExchange exchange;

        @Override
        public Mono<Void> filter(ServerWebExchange exchange) {
            this.called = true;
            this.exchange = exchange;
            return Mono.empty();
        }
    }
}
