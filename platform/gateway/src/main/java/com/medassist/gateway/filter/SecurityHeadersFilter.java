package com.medassist.gateway.filter;

import com.medassist.gateway.config.SecurityHeadersProperties;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * 统一安全响应头（§267，等保三级）。
 *
 * <p>顺序刻意早于 {@link AuthGlobalFilter}（-110 &lt; -100）：401/403 由鉴权过滤器直接写响应，
 * 如果安全头在它之后才加，未认证响应就没有防护头，扫描器会判为缺失。
 *
 * <p>HSTS 只在确认是 HTTPS 时输出：本地 HTTP 联调如果也发 HSTS，浏览器会把 localhost
 * 记成强制 HTTPS，影响后续开发；生产由 Ingress 终止 TLS 时通过 X-Forwarded-Proto 识别。
 */
@Component
public class SecurityHeadersFilter implements GlobalFilter, Ordered {

    static final String PERMISSIONS_POLICY = "Permissions-Policy";
    static final String STRICT_TRANSPORT_SECURITY = "Strict-Transport-Security";

    private final SecurityHeadersProperties props;

    public SecurityHeadersFilter(SecurityHeadersProperties props) {
        this.props = props;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        if (!props.enabled()) {
            return chain.filter(exchange);
        }
        HttpHeaders headers = exchange.getResponse().getHeaders();
        headers.set("X-Content-Type-Options", "nosniff");
        headers.set("X-Frame-Options", "DENY");
        headers.set("Referrer-Policy", props.referrerPolicy());
        headers.set(PERMISSIONS_POLICY, props.permissionsPolicy());
        headers.set("Content-Security-Policy", props.contentSecurityPolicy());

        String path = exchange.getRequest().getURI().getPath();
        if (props.noStoreForAuthPaths() && path.startsWith("/api/v2/auth/")) {
            headers.set(HttpHeaders.CACHE_CONTROL, "no-store");
            headers.set(HttpHeaders.PRAGMA, "no-cache");
        }
        if (props.hstsEnabled() && isSecure(exchange)) {
            String value = "max-age=" + props.hstsMaxAgeSeconds()
                    + (props.hstsIncludeSubDomains() ? "; includeSubDomains" : "");
            headers.set(STRICT_TRANSPORT_SECURITY, value);
        }
        return chain.filter(exchange);
    }

    private boolean isSecure(ServerWebExchange exchange) {
        if (exchange.getRequest().getSslInfo() != null) {
            return true;
        }
        if (props.trustForwardedProto()) {
            String forwarded = exchange.getRequest().getHeaders().getFirst("X-Forwarded-Proto");
            if (forwarded != null && !forwarded.isBlank()) {
                return "https".equalsIgnoreCase(forwarded.trim());
            }
        }
        return "https".equalsIgnoreCase(exchange.getRequest().getURI().getScheme());
    }

    @Override
    public int getOrder() {
        return -110;
    }
}
