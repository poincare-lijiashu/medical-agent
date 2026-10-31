package com.medassist.gateway.security;

import com.medassist.gateway.config.GatewayAuthProperties;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 真实 IP 解析测试（M3）。
 *
 * <p>核心断言：不可信的 X-Forwarded-For 永远不能影响结果——
 * 否则攻击者可自选"来源 IP"，同时绕过 IP 维度限速并污染审计。
 */
class ClientIpResolverTest {

    private static final InetSocketAddress PUBLIC_PEER = new InetSocketAddress("203.0.113.9", 51234);
    private static final InetSocketAddress INGRESS_PEER = new InetSocketAddress("10.0.0.5", 51234);

    @Test
    void withoutTrustedProxiesIgnoresForwardedFor() {
        ClientIpResolver resolver = resolver(List.of());

        // 客户端自带 XFF 想冒充 1.2.3.4
        assertThat(resolver.resolve(PUBLIC_PEER, "1.2.3.4"))
                .isEqualTo(PUBLIC_PEER.getAddress().getHostAddress());
    }

    @Test
    void trustedProxyUsesRightmostUntrustedHop() {
        ClientIpResolver resolver = resolver(List.of("10.0.0.0/8"));

        // 客户端伪造首段，Ingress（10.0.0.5）追加真实地址
        assertThat(resolver.resolve(INGRESS_PEER, "1.2.3.4, 198.51.100.7"))
                .isEqualTo("198.51.100.7");
    }

    @Test
    void untrustedPeerCannotSpoofEvenWithTrustedProxiesConfigured() {
        ClientIpResolver resolver = resolver(List.of("10.0.0.0/8"));

        // 对端不在可信网段 → 完全忽略 XFF
        assertThat(resolver.resolve(PUBLIC_PEER, "10.1.2.3"))
                .isEqualTo(PUBLIC_PEER.getAddress().getHostAddress());
    }

    @Test
    void multipleTrustedHopsAreSkipped() {
        ClientIpResolver resolver = resolver(List.of("10.0.0.0/8", "172.16.0.0/12"));

        assertThat(resolver.resolve(INGRESS_PEER, "198.51.100.7, 172.16.0.2, 10.0.0.8"))
                .isEqualTo("198.51.100.7");
    }

    @Test
    void invalidForwardedValueFallsBackToPeer() {
        ClientIpResolver resolver = resolver(List.of("10.0.0.0/8"));

        // 主机名/非法字符一律拒绝解析（防止把内部地址交给 DNS 探测）
        assertThat(resolver.resolve(INGRESS_PEER, "not-an-ip"))
                .isEqualTo(INGRESS_PEER.getAddress().getHostAddress());
    }

    @Test
    void invalidCidrFailsFast() {
        assertThatThrownBy(() -> resolver(List.of("10.0.0.0/99")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("trusted-proxies");
    }

    private static ClientIpResolver resolver(List<String> cidrs) {
        return new ClientIpResolver(props(cidrs));
    }

    private static GatewayAuthProperties props(List<String> cidrs) {
        return new GatewayAuthProperties(true, "medassist-auth",
                "unit-test-secret-0123456789abcdefghijklmnop",
                List.of("/actuator/health/**"),
                List.of("/api/v2/**"),
                List.of("/api/v2/*/internal/**"),
                "http://127.0.0.1:8081",
                new GatewayAuthProperties.RevocationSync(false, Duration.ofSeconds(15), false),
                new GatewayAuthProperties.ClientIp(cidrs));
    }
}
