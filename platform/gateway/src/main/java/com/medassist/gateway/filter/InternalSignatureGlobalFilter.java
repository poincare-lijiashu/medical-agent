package com.medassist.gateway.filter;

import com.medassist.common.security.AuthHeaders;
import com.medassist.common.security.InternalAuthProperties;
import com.medassist.common.security.InternalKeySet;
import com.medassist.common.security.InternalSignature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

/**
 * 网关 → 业务服务的请求签名（MF-07 阶段一）。
 *
 * <p>在 {@link AuthGlobalFilter} 完成身份头注入、路由过滤器完成 {@code StripPrefix} 之后执行，
 * 因此签名覆盖的是**下游实际看到**的方法/路径/查询串与身份头。
 *
 * <p>顺序放在 {@code Ordered.LOWEST_PRECEDENCE - 100}：晚于 StripPrefix（路由过滤器按声明顺序排序）、
 * 早于 NettyRoutingFilter（{@code LOWEST_PRECEDENCE}），避免签出一份"网关视角"而非"下游视角"的签名。
 *
 * <p>客户端自带的签名头已在入口被剥离（{@link AuthHeaders#CLIENT_SUPPLIED}），
 * 这里再 {@code set} 一次，确保伪造值不可能残留。
 */
public class InternalSignatureGlobalFilter implements GlobalFilter, Ordered {

    private static final Logger log = LoggerFactory.getLogger(InternalSignatureGlobalFilter.class);

    private final boolean enabled;
    private final InternalKeySet keySet;
    private final SecureRandom random = new SecureRandom();

    public InternalSignatureGlobalFilter(InternalAuthProperties props, InternalKeySet keySet) {
        this.enabled = !InternalAuthProperties.OFF.equals(props.modeOrDefault());
        this.keySet = keySet;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        if (!enabled) {
            return chain.filter(exchange);
        }
        String keyId = keySet.activeKeyId();
        String secret = keySet.secret(keyId);
        if (secret == null) {
            // 启动守卫已保证不会走到这里；万一发生，宁可让下游 403（fail-closed）也不放行未签名请求
            log.error("internal-sign.disabled-reason=no-active-key 请检查 medassist.internal-auth.keys-json");
            return chain.filter(exchange);
        }

        ServerHttpRequest request = exchange.getRequest();
        String method = request.getMethod().name();
        String path = request.getURI().getRawPath();
        String query = request.getURI().getRawQuery();
        long timestamp = System.currentTimeMillis() / 1000L;
        String nonce = nonce();

        String canonical = InternalSignature.canonical(method, path, query,
                identityHeaders(request), timestamp, nonce, InternalSignature.EMPTY_BODY_HASH);
        String signature = InternalSignature.sign(secret, canonical);

        ServerHttpRequest signed = request.mutate()
                .headers(headers -> {
                    headers.set(InternalSignature.HEADER_SIG, signature);
                    headers.set(InternalSignature.HEADER_TS, Long.toString(timestamp));
                    headers.set(InternalSignature.HEADER_NONCE, nonce);
                    headers.set(InternalSignature.HEADER_KEY_ID, keyId);
                    headers.set(InternalSignature.HEADER_SIG_VER, InternalSignature.SIG_VERSION);
                })
                .build();
        return chain.filter(exchange.mutate().request(signed).build());
    }

    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE - 100;
    }

    private String nonce() {
        byte[] bytes = new byte[16];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static Map<String, String> identityHeaders(ServerHttpRequest request) {
        Map<String, String> identity = new HashMap<>(8);
        for (String header : InternalSignature.IDENTITY_HEADERS) {
            identity.put(header, request.getHeaders().getFirst(header));
        }
        return identity;
    }
}
