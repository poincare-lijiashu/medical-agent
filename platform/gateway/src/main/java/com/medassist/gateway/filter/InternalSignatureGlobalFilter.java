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
 * 网关 → 业务服务的请求签名（MF-07 阶段一 v1 / 阶段二 v2）。
 *
 * <p>在 {@link AuthGlobalFilter} 完成身份头注入、路由过滤器完成 {@code StripPrefix} 之后执行，
 * 因此签名覆盖的是**下游实际看到**的方法/路径/查询串与身份头。
 *
 * <p>顺序放在 {@code Ordered.LOWEST_PRECEDENCE - 100}：晚于 StripPrefix（路由过滤器按声明顺序排序）、
 * 早于 NettyRoutingFilter（{@code LOWEST_PRECEDENCE}），避免签出一份"网关视角"而非"下游视角"的签名。
 *
 * <p>算法：默认 v1（HMAC，密钥集），{@code gateway-signing=ed25519} 时用 v2（Ed25519 私钥）。
 * 私钥只存在于网关，业务服务只持公钥——任一业务服务沦陷都无法伪造网关身份（第五轮 R5-M3）。
 *
 * <p>客户端自带的签名头已在入口被剥离（{@link AuthHeaders#CLIENT_SUPPLIED}），
 * 这里再 {@code set} 一次，确保伪造值不可能残留。
 */
public class InternalSignatureGlobalFilter implements GlobalFilter, Ordered {

    private static final Logger log = LoggerFactory.getLogger(InternalSignatureGlobalFilter.class);

    private final boolean enabled;
    private final InternalKeySet keySet;
    /** v2（Ed25519）私钥；非空即表示走非对称签名（业务服务只持公钥，无法伪造网关身份）。 */
    private final java.security.PrivateKey ed25519PrivateKey;
    private final String gatewayKeyId;
    private final SecureRandom random = new SecureRandom();

    public InternalSignatureGlobalFilter(InternalAuthProperties props, InternalKeySet keySet) {
        this(props, keySet, null);
    }

    public InternalSignatureGlobalFilter(InternalAuthProperties props, InternalKeySet keySet,
                                         java.security.PrivateKey ed25519PrivateKey) {
        this.enabled = !InternalAuthProperties.OFF.equals(props.modeOrDefault());
        this.keySet = keySet;
        this.ed25519PrivateKey = ed25519PrivateKey;
        this.gatewayKeyId = props.gatewayKeyIdOrDefault();
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        if (!enabled) {
            return chain.filter(exchange);
        }
        boolean ed25519 = ed25519PrivateKey != null;
        String keyId = ed25519 ? gatewayKeyId : keySet.activeKeyId();
        String secret = ed25519 ? null : keySet.secret(keyId);
        if (!ed25519 && secret == null) {
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

        Map<String, String> identity = identityHeaders(request);
        String canonical = ed25519
                ? InternalSignature.canonicalGatewayV2(method, path, query, identity, timestamp,
                        nonce, InternalSignature.EMPTY_BODY_HASH)
                : InternalSignature.canonical(method, path, query, identity, timestamp, nonce,
                        InternalSignature.EMPTY_BODY_HASH);
        String signature = ed25519
                ? InternalSignature.signEd25519(ed25519PrivateKey, canonical)
                : InternalSignature.sign(secret, canonical);
        String version = ed25519 ? InternalSignature.SIG_VERSION_GATEWAY_ED25519
                : InternalSignature.SIG_VERSION;

        ServerHttpRequest signed = request.mutate()
                .headers(headers -> {
                    headers.set(InternalSignature.HEADER_SIG, signature);
                    headers.set(InternalSignature.HEADER_TS, Long.toString(timestamp));
                    headers.set(InternalSignature.HEADER_NONCE, nonce);
                    headers.set(InternalSignature.HEADER_KEY_ID, keyId);
                    headers.set(InternalSignature.HEADER_SIG_VER, version);
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
