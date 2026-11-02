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
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferFactory;
import org.springframework.core.io.buffer.DataBufferLimitException;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpRequestDecorator;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

/**
 * 网关 → 业务服务的请求签名（MF-07 阶段一 v1 / v2 / v1b / v2b）。
 *
 * <p>在 {@link AuthGlobalFilter} 完成身份头注入、路由过滤器完成 {@code StripPrefix} 之后执行，
 * 因此签名覆盖的是**下游实际看到**的方法/路径/查询串与身份头。
 *
 * <p>顺序放在 {@code Ordered.LOWEST_PRECEDENCE - 100}：晚于 StripPrefix（路由过滤器按声明顺序排序），
 * 早于 NettyRoutingFilter（{@code LOWEST_PRECEDENCE}），避免签出一份"网关视角"而非"下游视角"的签名。
 *
 * <p>算法：默认 v1（HMAC，密钥集），{@code gateway-signing=ed25519} 时用 v2（Ed25519 私钥）。
 * 私钥只存在于网关，业务服务只持公钥——任一业务服务沦陷都无法伪造网关身份（第五轮 R5-M3）。
 *
 * <p>P0-8：{@code body-hash-required=true} 时改用带体的 v1b/v2b——先从 WebFlux 的
 * {@code Flux<DataBuffer>} 缓存请求体并计算 SHA-256，再把同一份字节重新包装成新的请求体
 * 传给下游。若超上限（{@code body-hash-max-bytes}）直接 413，**不退化成"跳过体校验"**。
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
    /** 是否把请求体摘要纳入签名（P0-8 网关通道；灰度开关与业务服务同源）。 */
    private final boolean bodyBinding;
    /** 缓存请求体上限；超出即 413。 */
    private final int maxBodyBytes;
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
        this.bodyBinding = props.bodyHashRequiredOrDefault();
        this.maxBodyBytes = props.bodyHashMaxBytesOrDefault();
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
        if (bodyBinding && InternalSignature.isBodyHashable(contentType(exchange.getRequest()))) {
            return signWithBody(exchange, chain, ed25519, keyId, secret);
        }
        ServerHttpRequest signed = sign(exchange.getRequest(), ed25519, keyId, secret,
                InternalSignature.EMPTY_BODY_HASH, bodyBinding);
        return chain.filter(exchange.mutate().request(signed).build());
    }

    /** 上传类（multipart/二进制）不摘要：避免把大文件聚合进网关内存。 */
    private static String contentType(ServerHttpRequest request) {
        org.springframework.http.MediaType type = request.getHeaders().getContentType();
        return type == null ? null : type.toString();
    }

    /**
     * 带体签名：读取请求体 → 计算摘要 → 重签 → 用缓存的字节重建请求体。
     *
     * <p>{@code DataBufferUtils.join} 会把整个体聚合到内存，因此必须给上限；
     * 超限抛 {@link DataBufferLimitException}，这里转成 413（不进入路由）。
     */
    private Mono<Void> signWithBody(ServerWebExchange exchange, GatewayFilterChain chain,
                                    boolean ed25519, String keyId, String secret) {
        ServerHttpRequest request = exchange.getRequest();
        DataBufferFactory bufferFactory = exchange.getResponse().bufferFactory();
        return DataBufferUtils.join(request.getBody(), maxBodyBytes)
                .map(buffer -> {
                    try {
                        byte[] bytes = new byte[buffer.readableByteCount()];
                        buffer.read(bytes);
                        return bytes;
                    } finally {
                        DataBufferUtils.release(buffer);
                    }
                })
                .defaultIfEmpty(new byte[0])
                .flatMap(bytes -> {
                    ServerHttpRequest signed = sign(request, ed25519, keyId, secret,
                            InternalSignature.bodyHash(bytes), true);
                    // 空体（GET/无体 POST）无需重建，避免给下游多包一层 DataBuffer
                    ServerHttpRequest forwarded = bytes.length == 0 ? signed
                            : withBody(signed, bufferFactory, bytes);
                    return chain.filter(exchange.mutate().request(forwarded).build());
                })
                .onErrorResume(DataBufferLimitException.class, e -> {
                    log.warn("internal-sign.body-too-large max={} path={}", maxBodyBytes,
                            request.getURI().getRawPath());
                    exchange.getResponse().setStatusCode(HttpStatus.PAYLOAD_TOO_LARGE);
                    return exchange.getResponse().setComplete();
                });
    }

    /** 生成签名头（覆盖客户端伪造值）；请求体摘要由调用方决定（无体用 {@code "-"}）。 */
    private ServerHttpRequest sign(ServerHttpRequest request, boolean ed25519, String keyId,
                                   String secret, String bodyHash, boolean bodyBound) {
        String method = request.getMethod().name();
        String path = request.getURI().getRawPath();
        String query = request.getURI().getRawQuery();
        long timestamp = System.currentTimeMillis() / 1000L;
        String nonce = nonce();
        String version = version(ed25519, bodyBound);

        Map<String, String> identity = identityHeaders(request);
        String canonical = InternalSignature.canonicalGateway(version, method, path, query,
                identity, timestamp, nonce, bodyHash);
        String signature = ed25519
                ? InternalSignature.signEd25519(ed25519PrivateKey, canonical)
                : InternalSignature.sign(secret, canonical);
        return request.mutate()
                .headers(headers -> {
                    headers.set(InternalSignature.HEADER_SIG, signature);
                    headers.set(InternalSignature.HEADER_TS, Long.toString(timestamp));
                    headers.set(InternalSignature.HEADER_NONCE, nonce);
                    headers.set(InternalSignature.HEADER_KEY_ID, keyId);
                    headers.set(InternalSignature.HEADER_SIG_VER, version);
                })
                .build();
    }

    /** 版本行必须与"是否绑定请求体"一致，否则下游会按另一种规范串重算而验签失败。 */
    private static String version(boolean ed25519, boolean bodyBound) {
        if (ed25519) {
            return bodyBound ? InternalSignature.SIG_VERSION_GATEWAY_ED25519_BODY
                    : InternalSignature.SIG_VERSION_GATEWAY_ED25519;
        }
        return bodyBound ? InternalSignature.SIG_VERSION_GATEWAY_BODY
                : InternalSignature.SIG_VERSION;
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

    /** 用缓存字节重建请求体，保证下游读到的内容与签名覆盖的字节完全一致。 */
    private static ServerHttpRequest withBody(ServerHttpRequest request,
                                              DataBufferFactory factory, byte[] bytes) {
        return new ServerHttpRequestDecorator(request) {
            @Override
            public Flux<DataBuffer> getBody() {
                return Flux.just(factory.wrap(bytes));
            }
        };
    }

    private static Map<String, String> identityHeaders(ServerHttpRequest request) {
        Map<String, String> identity = new HashMap<>(8);
        for (String header : InternalSignature.IDENTITY_HEADERS) {
            identity.put(header, request.getHeaders().getFirst(header));
        }
        return identity;
    }
}
