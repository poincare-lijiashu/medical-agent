package com.medassist.gateway.filter;

import com.medassist.common.api.ApiResponse;
import com.medassist.common.api.ErrorCode;
import com.medassist.common.security.AuthHeaders;
import com.medassist.gateway.config.GatewayAuthProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.PathContainer;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;
import reactor.core.publisher.Mono;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

/**
 * 网关统一鉴权过滤器（P0-4 核心）。
 *
 * <p>处理顺序（每个请求都执行）：
 * <ol>
 *   <li><b>剥离</b>客户端提交的 {@code X-Actor / X-User-Id / X-Roles / X-Org / X-Trace-Id}——
 *       这是 P0-3 遗留的伪造漏洞，先删除再谈注入；</li>
 *   <li>生成链路 ID 并写入 {@code X-Trace-Id}（客户端无法指定，避免日志污染）；</li>
 *   <li>命中 {@code denyPaths}（如 {@code /api/v2/* /internal/**}）直接 404，内部端点不经网关外泄；</li>
 *   <li>命中白名单（登录、健康检查）放行；非受保护前缀（v1 兼容层）放行，由下游自行鉴权；</li>
 *   <li>受保护路径：校验 {@code Authorization: Bearer}，失败返回统一壳 401；成功则按令牌声明
 *       重新写入身份头供下游使用。</li>
 * </ol>
 *
 * <p>过滤器顺序 -100：早于路由与转发过滤器，保证下游永远看不到未清洗的请求头。
 */
@Component
public class AuthGlobalFilter implements GlobalFilter, Ordered {

    private static final Logger log = LoggerFactory.getLogger(AuthGlobalFilter.class);

    private static final String BEARER_PREFIX = "Bearer ";
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final GatewayAuthProperties props;
    private final JwtDecoder decoder;
    private final List<PathPattern> whitelist;
    private final List<PathPattern> protectedPaths;
    private final List<PathPattern> denyPaths;

    public AuthGlobalFilter(GatewayAuthProperties props, JwtDecoder jwtDecoder) {
        this.props = props;
        this.decoder = jwtDecoder;
        this.whitelist = parse(props.whitelist());
        this.protectedPaths = parse(props.protectedPaths());
        this.denyPaths = parse(props.denyPaths());
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String traceId = UUID.randomUUID().toString().replace("-", "");

        ServerHttpRequest sanitized = exchange.getRequest().mutate()
                .headers(h -> AuthHeaders.CLIENT_SUPPLIED.forEach(h::remove))
                .header(AuthHeaders.TRACE_ID, traceId)
                .build();
        ServerWebExchange cleaned = exchange.mutate().request(sanitized).build();

        PathContainer path = sanitized.getPath().pathWithinApplication();
        // 内部端点无论鉴权开关如何都必须拒绝，防止排障时误把 /internal/** 暴露到公网
        if (matches(denyPaths, path)) {
            log.info("gateway.auth.deny path={} traceId={}", path.value(), traceId);
            return reject(cleaned, HttpStatus.NOT_FOUND, ErrorCode.NOT_FOUND, traceId);
        }
        if (!props.enabled()) {
            return chain.filter(cleaned);
        }

        if (matches(whitelist, path) || !matches(protectedPaths, path)) {
            return chain.filter(cleaned);
        }

        String token = bearerToken(sanitized);
        if (token == null) {
            return reject(cleaned, HttpStatus.UNAUTHORIZED, ErrorCode.UNAUTHORIZED, traceId);
        }

        Jwt jwt;
        try {
            jwt = decoder.decode(token);
        } catch (JwtException e) {
            // 不打印令牌内容；仅记录失败原因用于排障
            log.info("gateway.auth.invalid path={} traceId={} reason={}", path.value(), traceId, e.getMessage());
            return reject(cleaned, HttpStatus.UNAUTHORIZED, ErrorCode.UNAUTHORIZED, traceId);
        }

        String actor = jwt.getClaimAsString("username");
        String subject = jwt.getSubject();
        if (actor == null || actor.isBlank() || subject == null || subject.isBlank()) {
            // 结构不符合平台契约（可能是其他系统的令牌）
            log.info("gateway.auth.claim.missing path={} traceId={}", path.value(), traceId);
            return reject(cleaned, HttpStatus.UNAUTHORIZED, ErrorCode.UNAUTHORIZED, traceId);
        }

        List<String> roles = jwt.getClaimAsStringList("roles");
        String org = jwt.getClaimAsString("org");
        ServerHttpRequest withIdentity = cleaned.getRequest().mutate()
                .header(AuthHeaders.ACTOR, actor)
                .header(AuthHeaders.USER_ID, subject)
                .header(AuthHeaders.ROLES, roles == null ? "" : String.join(",", roles))
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

    /** 统一错误壳（与下游服务同一契约，前端/集成方无需分支解析）。 */
    private Mono<Void> reject(ServerWebExchange exchange, HttpStatus status, ErrorCode code, String traceId) {
        ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(status);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        if (status == HttpStatus.UNAUTHORIZED) {
            response.getHeaders().set(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
        }
        byte[] body = serialize(ApiResponse.fail(code, code.defaultMessage(), traceId));
        response.getHeaders().setContentLength(body.length);
        return response.writeWith(Mono.just(response.bufferFactory().wrap(body)));
    }

    private static byte[] serialize(ApiResponse<?> payload) {
        try {
            return JSON.writeValueAsString(payload).getBytes(StandardCharsets.UTF_8);
        } catch (RuntimeException e) {
            // 序列化不应失败；兜底返回最小合法结构，保证客户端拿到 401 而不是空响应
            log.error("gateway.auth.response.serialize.failed", e);
            return ("{\"code\":" + payload.code() + ",\"message\":\"" + payload.message() + "\"}")
                    .getBytes(StandardCharsets.UTF_8);
        }
    }
}
