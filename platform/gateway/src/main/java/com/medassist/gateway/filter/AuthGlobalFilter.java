package com.medassist.gateway.filter;

import com.medassist.common.api.ErrorCode;
import com.medassist.common.security.AuthHeaders;
import com.medassist.common.security.DataScope;
import com.medassist.common.security.DataScopeResolver;
import com.medassist.gateway.config.GatewayAuthProperties;
import com.medassist.gateway.config.GatewayTenantProperties;
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
 *       （账号/角色/权限/令牌 ID/科室）；</li>
 *   <li>租户一致性（§281）：入口 Host 映射到租户后，令牌 {@code tenant} 必须与之一致、
 *       {@code instance} 必须是本 Cell，否则 403 并注入校验通过的 {@code X-Tenant}。</li>
 * </ol>
 *
 * <p>过滤器顺序 -100：早于路由与转发过滤器，保证下游看不到未清洗的请求头。
 */
@Component
public class AuthGlobalFilter implements GlobalFilter, Ordered {

    private static final Logger log = LoggerFactory.getLogger(AuthGlobalFilter.class);

    private static final String BEARER_PREFIX = "Bearer ";

    private final GatewayAuthProperties props;
    private final GatewayTenantProperties tenantProps;
    private final JwtDecoder decoder;
    private final TokenRevocationCache revocations;
    private final ClientIpResolver clientIpResolver;
    private final List<PathPattern> whitelist;
    private final List<PathPattern> protectedPaths;
    private final List<PathPattern> denyPaths;

    public AuthGlobalFilter(GatewayAuthProperties props, GatewayTenantProperties tenantProps,
                            JwtDecoder jwtDecoder, TokenRevocationCache revocations,
                            ClientIpResolver clientIpResolver) {
        this.props = props;
        this.tenantProps = tenantProps;
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

        // 租户解析（ADR-001）：入口 Host → 租户。先于鉴权开关，因为"入口没登记"比"没登录"更根本。
        String hostTenant = tenantProps.resolveHost(cleaned.getRequest().getURI().getHost());
        if (tenantProps.enabled() && hostTenant == null) {
            log.info("gateway.tenant.unknown_host path={} traceId={} host={}",
                    path.value(), traceId, cleaned.getRequest().getURI().getHost());
            return GatewayResponses.reject(cleaned, HttpStatus.FORBIDDEN, ErrorCode.TENANT_MISMATCH,
                    "当前访问入口未登记到任何院区，请联系平台运营", traceId);
        }
        // 未启用多租户或入口未映射时，落到默认租户（单院私有化 / 本地联调）
        String entryTenant = hostTenant != null ? hostTenant : tenantProps.effectiveDefaultTenant();

        if (!props.enabled()) {
            return chain.filter(withTenant(cleaned, entryTenant));
        }

        if (matches(whitelist, path) || !matches(protectedPaths, path)) {
            return chain.filter(withTenant(cleaned, entryTenant));
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
        if (tokenId == null || tokenId.isBlank()) {
            // 没有 jti 的令牌无法进入撤销名单：登出/改密后它仍然可用，等于撤销机制失效。
            // 平台自签令牌一律带 jti，缺 jti 说明是外部伪造或旧格式，直接拒绝（P2-4）。
            log.info("gateway.auth.jti.missing path={} traceId={}", path.value(), traceId);
            return unauthorized(cleaned, traceId, "未认证：令牌缺少唯一标识，请重新登录");
        }
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
        // 多科室执业：depts 是临床科室 UUID 列表，拼成逗号分隔的 X-Org（下游按集合解析）。
        // 没有 depts 声明的旧令牌回退到 org 声明，保持"单科室"的原行为。
        List<String> depts = jwt.getClaimAsStringList("depts");
        String org = (depts != null && !depts.isEmpty())
                ? String.join(",", depts)
                : jwt.getClaimAsString("org");
        // 多院区（G45）：campuses 是院区 UUID 列表，拼成逗号分隔的 X-Campus。
        //
        // G51：下游已改为 fail-closed（缺头 = 只看主院区），因此这里必须补齐默认值，
        // 否则"没有 campuses 声明"的令牌会导致管理员看不到任何非主院区数据：
        //   有 campuses 声明            → 原样下发；
        //   无声明且角色含 ADMIN/QC     → 下发 "*"（显式全院区）；
        //   其余（旧令牌/普通账号）      → 下发主院区。
        List<String> campuses = jwt.getClaimAsStringList("campuses");
        String campus;
        if (campuses != null && !campuses.isEmpty()) {
            campus = String.join(",", campuses);
        } else if (hasCampusAllRole(roles)) {
            campus = DataScopeResolver.CAMPUS_ALL;
        } else {
            campus = DataScope.DEFAULT_CAMPUS_ID.toString();
        }

        // 租户与实例校验（ADR-001）：令牌必须属于当前 Cell，且与入口 Host 指向的租户一致。
        // 这是"拿 A 院令牌打 B 院入口"的唯一拦截点——下游按 Cell 物理隔离，已经没有第二道防线。
        String tokenTenant = jwt.getClaimAsString("tenant");
        String tokenInstance = jwt.getClaimAsString("instance");
        if (tenantProps.enabled()) {
            if (tokenTenant == null || tokenTenant.isBlank()) {
                log.info("gateway.tenant.claim.missing path={} traceId={}", path.value(), traceId);
                return unauthorized(cleaned, traceId, "登录凭证缺少院区信息，请重新登录");
            }
            if (!tokenTenant.trim().equalsIgnoreCase(entryTenant)) {
                log.warn("gateway.tenant.mismatch path={} traceId={} entry={} token={}",
                        path.value(), traceId, entryTenant, tokenTenant);
                return GatewayResponses.reject(cleaned, HttpStatus.FORBIDDEN, ErrorCode.TENANT_MISMATCH,
                        ErrorCode.TENANT_MISMATCH.defaultMessage(), traceId);
            }
            String expectedInstance = tenantProps.effectiveInstanceCode();
            if (expectedInstance != null
                    && (tokenInstance == null || !expectedInstance.equalsIgnoreCase(tokenInstance.trim()))) {
                log.warn("gateway.tenant.instance.mismatch path={} traceId={} expected={} actual={}",
                        path.value(), traceId, expectedInstance, tokenInstance);
                return GatewayResponses.reject(cleaned, HttpStatus.FORBIDDEN, ErrorCode.TENANT_INSTANCE_MISMATCH,
                        ErrorCode.TENANT_INSTANCE_MISMATCH.defaultMessage(), traceId);
            }
        }
        // 下游看到的租户：以令牌声明为准（身份归属），令牌缺失时才回退入口租户。
        // 启用多租户时两者必然一致（上面已校验），因此不会出现"下游看到的租户与入口不同"。
        String tenant = (tokenTenant != null && !tokenTenant.isBlank()) ? tokenTenant.trim() : entryTenant;

        ServerHttpRequest withIdentity = cleaned.getRequest().mutate()
                .header(AuthHeaders.ACTOR, actor)
                .header(AuthHeaders.USER_ID, subject)
                .header(AuthHeaders.ROLES, roles == null ? "" : String.join(",", roles))
                .header(AuthHeaders.PERMISSIONS, permissions == null ? "" : String.join(",", permissions))
                .header(AuthHeaders.TOKEN_ID, tokenId == null ? "" : tokenId)
                .header(AuthHeaders.TENANT, tenant)
                .headers(h -> {
                    if (org != null && !org.isBlank()) {
                        h.set(AuthHeaders.ORG, org);
                    }
                    if (campus != null && !campus.isBlank()) {
                        h.set(AuthHeaders.CAMPUS, campus);
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

    /**
     * 是否属于"必须跨全部院区"的角色（G51）。
     *
     * <p>只在令牌<em>没有</em> campuses 声明时兜底：管理员/质控需要看到全部院区（含
     * 上线后新建的院区），其余账号一律收敛到主院区，避免 fail-closed 之后集体 403 或空列表。
     */
    private static boolean hasCampusAllRole(List<String> roles) {
        if (roles == null || roles.isEmpty()) {
            return false;
        }
        return roles.stream()
                .filter(r -> r != null && !r.isBlank())
                .map(r -> r.trim().toUpperCase())
                .anyMatch(r -> r.equals("ADMIN") || r.equals("QC"));
    }

    private Mono<Void> unauthorized(ServerWebExchange exchange, String traceId, String message) {
        return GatewayResponses.reject(exchange, HttpStatus.UNAUTHORIZED, ErrorCode.UNAUTHORIZED,
                message, traceId);
    }

    /** 未鉴权路径（登录/刷新/健康检查）也要带上租户：auth 按它决定"在哪家医院查账号"。 */
    private static ServerWebExchange withTenant(ServerWebExchange exchange, String tenant) {
        ServerHttpRequest request = exchange.getRequest().mutate()
                .header(AuthHeaders.TENANT, tenant)
                .build();
        return exchange.mutate().request(request).build();
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
