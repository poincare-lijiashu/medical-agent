package com.medassist.gateway.filter;

import com.medassist.common.api.ErrorCode;
import com.medassist.common.security.AuthHeaders;
import com.medassist.gateway.config.GatewayAuthProperties;
import com.medassist.gateway.security.ClientIpResolver;
import com.medassist.gateway.security.TokenRevocationCache;
import com.medassist.gateway.web.GatewayResponses;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.PathContainer;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;
import reactor.core.publisher.Mono;

import java.net.InetSocketAddress;
import java.util.List;
import java.util.UUID;

/**
 * 网关统一鉴权过滤器（P0-4 引入，P1 补充权限头与撤销名单）。
 *
 * <p>处理顺序（每个请求都执行）：
 * <ol>
 *   <li><b>剥离</b>客户端提交的 {@code X-Actor / X-User-Id / X-Roles / X-Permissions /
 *       X-Token-Id / X-Org / X-Trace-Id}——先删除再谈注入；</li>
 *   <li>生成链路 ID 并写入 {@code X-Trace-Id}；</li>
 *   <li>命中 {@code denyPaths}（如 {@code /api/v2/* /internal/**}）直接 404；</li>
 *   <li>命中白名单（登录/刷新/健康检查）放行；非受保护前缀（v1 兼容层）放行；</li>
 *   <li>受保护路径：校验 Bearer JWT → 检查 jti 是否在撤销名单 → 按令牌声明写入身份头
 *       （账号/角色/权限/令牌 ID/科室）。</li>
 * </ol>
 *
 * <p>过滤器顺序 -100：早于路由与转发过滤器，保证下游看不到未清洗的请求头。
 */
@Component
public class AuthGlobalFilter implements GlobalFilter, Ordered {

    private static final Logger log = LoggerFactory.getLogger(AuthGlobalFilter.class);

    private static final String BEARER_PREFIX = "Bearer ";

    private final GatewayAuthProperties props;
    private final JwtDecoder decoder;
    private final TokenRevocationCache revocations;
    private final ClientIpResolver clientIpResolver;
    private final List<PathPattern> whitelist;
    private final List<PathPattern> protectedPaths;
    private final List<PathPattern> denyPaths;

    public AuthGlobalFilter(GatewayAuthProperties props, JwtDecoder jwtDecoder,
                            TokenRevocationCache revocations, ClientIpResolver clientIpResolver) {
        this.props = props;
        this.decoder = jwtDecoder;
        this.revocations = revocations;
        this.clientIpResolver = clientIpResolver;
        this.whitelist = parse(props.whitelist());
        this.protectedPaths = parse(props.protectedPaths());
        this.denyPaths = parse(props.denyPaths());
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String traceId = UUID.randomUUID().toString().replace("-", "");

        // 真实客户端 IP：由可信代理链计算，客户端自带的 X-Forwarded-For / X-Client-Ip 一律不可信（M3）
        InetSocketAddress remote = exchange.getRequest().getRemoteAddress();
        String clientIp = clientIpResolver.resolve(remote,
                exchange.getRequest().getHeaders().getFirst("X-Forwarded-For"));

        ServerHttpRequest sanitized = exchange.getRequest().mutate()
                .headers(h -> AuthHeaders.CLIENT_SUPPLIED.forEach(h::remove))
                .header(AuthHeaders.TRACE_ID, traceId)
                .header(AuthHeaders.CLIENT_IP, clientIp)
                .build();
        ServerWebExchange cleaned = exchange.mutate().request(sanitized).build();

        PathContainer path = sanitized.getPath().pathWithinApplication();
        // 内部端点无论鉴权开关如何都必须拒绝，防止排障时误把 /internal/** 暴露到公网
        if (matches(denyPaths, path)) {
            log.info("gateway.auth.deny path={} traceId={}", path.value(), traceId);
            return GatewayResponses.reject(cleaned, HttpStatus.NOT_FOUND, ErrorCode.NOT_FOUND,
                    ErrorCode.NOT_FOUND.defaultMessage(), traceId);
        }
        if (!props.enabled()) {
            return chain.filter(cleaned);
        }

        if (matches(whitelist, path) || !matches(protectedPaths, path)) {
            return chain.filter(cleaned);
        }

        String token = bearerToken(sanitized);
        if (token == null) {
            return unauthorized(cleaned, traceId, "未认证：请先登录");
        }

        Jwt jwt;
        try {
            jwt = decoder.decode(token);
        } catch (JwtException e) {
            // 不打印令牌内容；仅记录失败原因用于排障
            log.info("gateway.auth.invalid path={} traceId={} reason={}", path.value(), traceId, e.getMessage());
            return unauthorized(cleaned, traceId, "未认证：请先登录");
        }

        String tokenId = jwt.getId();
        if (revocations.isRevoked(tokenId)) {
            log.info("gateway.auth.revoked path={} traceId={} jti={}", path.value(), traceId, tokenId);
            return unauthorized(cleaned, traceId, "登录已失效，请重新登录");
        }

        String actor = jwt.getClaimAsString("username");
        String subject = jwt.getSubject();
        if (actor == null || actor.isBlank() || subject == null || subject.isBlank()) {
            log.info("gateway.auth.claim.missing path={} traceId={}", path.value(), traceId);
            return unauthorized(cleaned, traceId, "未认证：请先登录");
        }

        List<String> roles = jwt.getClaimAsStringList("roles");
        List<String> permissions = jwt.getClaimAsStringList("permissions");
        String org = jwt.getClaimAsString("org");
        ServerHttpRequest withIdentity = cleaned.getRequest().mutate()
                .header(AuthHeaders.ACTOR, actor)
                .header(AuthHeaders.USER_ID, subject)
                .header(AuthHeaders.ROLES, roles == null ? "" : String.join(",", roles))
                .header(AuthHeaders.PERMISSIONS, permissions == null ? "" : String.join(",", permissions))
                .header(AuthHeaders.TOKEN_ID, tokenId == null ? "" : tokenId)
                .headers(h -> {
                    if (org != null && !org.isBlank()) {
                        h.set(AuthHeaders.ORG, org);
                    }
                })
                .build();
        return chain.filter(cleaned.mutate().request(withIdentity).build());
    }

    @Override
    public int getOrder() {
        return -100;
    }

    // ---------------------------------------------------------------- 内部方法

    private Mono<Void> unauthorized(ServerWebExchange exchange, String traceId, String message) {
        return GatewayResponses.reject(exchange, HttpStatus.UNAUTHORIZED, ErrorCode.UNAUTHORIZED,
                message, traceId);
    }

    private static String bearerToken(ServerHttpRequest request) {
        String header = request.getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
        if (header == null || !header.startsWith(BEARER_PREFIX)) {
            return null;
        }
        String token = header.substring(BEARER_PREFIX.length()).trim();
        return token.isEmpty() ? null : token;
    }

    private static boolean matches(List<PathPattern> patterns, PathContainer path) {
        for (PathPattern pattern : patterns) {
            if (pattern.matches(path)) {
                return true;
            }
        }
        return false;
    }

    private static List<PathPattern> parse(List<String> patterns) {
        if (patterns == null || patterns.isEmpty()) {
            return List.of();
        }
        PathPatternParser parser = new PathPatternParser();
        return patterns.stream()
                .filter(p -> p != null && !p.isBlank())
                .map(parser::parse)
                .toList();
    }
}
