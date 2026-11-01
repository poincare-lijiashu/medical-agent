package com.medassist.gateway.security;

import com.medassist.gateway.config.GatewayAuthProperties;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 撤销名单缓存语义测试（M4）。
 *
 * <p>核心断言：同步已启用但首轮尚未完成时，撤销状态未知 → 必须 fail-closed
 * （按"已撤销"处理），而不是放行。
 */
class TokenRevocationCacheTest {

    @Test
    void failClosedBeforeFirstSyncWhenSyncEnabled() {
        TokenRevocationCache cache = new TokenRevocationCache(props(true));

        // 未调用 afterPropertiesSet（首轮同步未完成）
        assertThat(cache.isRevoked("any-jti")).isTrue();
    }

    @Test
    void openWhenSyncDisabled() {
        TokenRevocationCache cache = new TokenRevocationCache(props(false));

        // 关闭同步时不存在"名单未知"的语义，不应误杀所有令牌
        assertThat(cache.isRevoked("any-jti")).isFalse();
    }

    private static GatewayAuthProperties props(boolean syncEnabled) {
        return new GatewayAuthProperties(true, "medassist-auth",
                "unit-test-secret-0123456789abcdefghijklmnop",
                List.of("/actuator/health/**"),
                List.of("/api/v2/**"),
                List.of("/api/v2/*/internal/**"),
                "http://127.0.0.1:8081",
                "unit-test-internal-token",
                new GatewayAuthProperties.RevocationSync(syncEnabled, Duration.ofSeconds(15), false),
                new GatewayAuthProperties.ClientIp(List.of()));
    }
}
