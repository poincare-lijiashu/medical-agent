package com.medassist.common.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.medassist.common.api.ApiResponse;
import com.medassist.common.api.ErrorCode;
import com.medassist.common.security.AuthHeaders;
import com.medassist.common.security.InternalAuthProperties;
import com.medassist.common.security.InternalKeySet;
import com.medassist.common.security.InternalSignature;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 服务间请求来源校验（MF-07 阶段一，网关 → 业务服务）。
 *
 * <p>信任模型：业务服务此前只信任 {@code X-Permissions} 等身份头，内网任意主机直连业务端口
 * 自带该头即可读取 PHI。本过滤器要求网关对每个转发请求附加 HMAC 签名，
 * 签名覆盖方法、路径、查询串、全部身份头、时间戳与随机数；缺签名或签名不符一律 403
 * （{@code log} 模式只打点放行，用于灰度观测）。
 *
 * <p>行为矩阵：
 * <pre>
 * mode=off      → 直接放行（仅非生产环境允许；生产启动阶段即拒绝）
 * mode=log      → 校验并打点，失败也放行（灰度期用来暴露"绕过网关的直连流量"）
 * mode=enforce  → 校验失败返回 403，不区分具体原因（避免成为攻击者的 oracle）
 * </pre>
 *
 * <p>豁免：探针 / Prometheus 抓取；{@code /internal/**} 在
 * {@code internal-paths-signature-required=false} 时仍只由控制器校验内部令牌
 * （调用方签名作为 T5 单独上线，避免切换 enforce 时中断服务间调用）。
 *
 * <p>已知边界（写入 docs/DESIGN-20261101-MF07-INTERNAL-AUTH.md）：
 * nonce 去重为进程内实现，仅对单副本严格有效；多副本窗口内重放依赖 60 秒时间窗收敛，
 * 阶段二切换 Redis 后消除。
 */
public class InternalAuthFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(InternalAuthFilter.class);

    /** 校验结果写入请求属性，便于控制器/审计串联（不含敏感值）。 */
    public static final String ATTR_RESULT = "medassist.internal-auth.result";
    public static final String ATTR_KEY_ID = "medassist.internal-auth.key-id";
    public static final String ATTR_CALLER = "medassist.internal-auth.caller";

    private static final String METRIC_NAME = "medassist.internal.auth";
    private static final int MAX_NONCE_ENTRIES = 200_000;

    private final InternalAuthProperties props;
    private final InternalKeySet keySet;
    private final ObjectMapper mapper;
    private final MeterRegistry meterRegistry;
    private final AntPathMatcher pathMatcher = new AntPathMatcher();
    private final NonceCache nonces;

    public InternalAuthFilter(InternalAuthProperties props, InternalKeySet keySet,
                              ObjectMapper mapper, MeterRegistry meterRegistry) {
        this.props = props;
        this.keySet = keySet;
        this.mapper = mapper == null ? new ObjectMapper().findAndRegisterModules() : mapper;
        this.meterRegistry = meterRegistry;
        this.nonces = new NonceCache(props.nonceTtlSecondsOrDefault() * 1000L, MAX_NONCE_ENTRIES);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        if (InternalAuthProperties.OFF.equals(props.modeOrDefault())) {
            chain.doFilter(request, response);
            return;
        }

        String path = path(request);
        if (isExempt(path)) {
            chain.doFilter(request, response);
            return;
        }
        if (isInternalPath(path) && !props.internalPathsSignatureRequiredOrDefault()) {
            // 服务→服务通道：内部令牌仍由各控制器校验；调用方签名（T5）落地前不在此拦截
            request.setAttribute(ATTR_CALLER, "internal");
            chain.doFilter(request, response);
            return;
        }

        String result = verify(request);
        request.setAttribute(ATTR_RESULT, result);
        count(result);

        if ("ok".equals(result)) {
            chain.doFilter(request, response);
            return;
        }

        if (InternalAuthProperties.ENFORCE.equals(props.modeOrDefault())) {
            log.warn("internal-auth.reject path={} method={} result={} remote={}",
                    path, request.getMethod(), result, request.getRemoteAddr());
            reject(response, traceId(request));
            return;
        }
        log.info("internal-auth.log-only path={} method={} result={}", path, request.getMethod(), result);
        chain.doFilter(request, response);
    }

    // ---------------------------------------------------------------- 校验

    /** @return ok / missing / bad_version / unknown_key / skew / replay / bad_sig */
    private String verify(HttpServletRequest request) {
        String version = request.getHeader(InternalSignature.HEADER_SIG_VER);
        String keyId = request.getHeader(InternalSignature.HEADER_KEY_ID);
        String timestamp = request.getHeader(InternalSignature.HEADER_TS);
        String nonce = request.getHeader(InternalSignature.HEADER_NONCE);
        String signature = request.getHeader(InternalSignature.HEADER_SIG);
        if (isBlank(version) || isBlank(keyId) || isBlank(timestamp)
                || isBlank(nonce) || isBlank(signature)) {
            return "missing";
        }
        if (!InternalSignature.SIG_VERSION.equals(version)) {
            return "bad_version";
        }
        String secret = keySet.secret(keyId);
        if (secret == null) {
            return "unknown_key";
        }

        long ts;
        try {
            ts = Long.parseLong(timestamp);
        } catch (NumberFormatException e) {
            return "missing";
        }
        long now = System.currentTimeMillis() / 1000L;
        if (Math.abs(now - ts) > props.clockSkewSecondsOrDefault()) {
            return "skew";
        }
        if (!nonces.register(keyId + ":" + nonce)) {
            return "replay";
        }

        String canonical = InternalSignature.canonical(request.getMethod(), path(request),
                request.getQueryString(), identityHeaders(request), ts, nonce,
                InternalSignature.EMPTY_BODY_HASH);
        if (!InternalSignature.verify(secret, canonical, signature)) {
            return "bad_sig";
        }
        request.setAttribute(ATTR_KEY_ID, keyId);
        request.setAttribute(ATTR_CALLER, "gateway");
        return "ok";
    }

    private static Map<String, String> identityHeaders(HttpServletRequest request) {
        Map<String, String> identity = new HashMap<>(8);
        for (String header : InternalSignature.IDENTITY_HEADERS) {
            identity.put(header, request.getHeader(header));
        }
        return identity;
    }

    // ---------------------------------------------------------------- 辅助

    private String path(HttpServletRequest request) {
        String uri = request.getRequestURI();
        String context = request.getContextPath();
        if (context != null && !context.isEmpty() && uri.startsWith(context)) {
            uri = uri.substring(context.length());
        }
        return uri.isEmpty() ? "/" : uri;
    }

    private boolean isExempt(String path) {
        for (String pattern : props.exemptPathsOrDefault()) {
            if (pathMatcher.match(pattern, path)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isInternalPath(String path) {
        return "/internal".equals(path) || path.startsWith("/internal/");
    }

    private void count(String result) {
        if (meterRegistry == null) {
            return;
        }
        Counter.builder(METRIC_NAME).tag("result", result).register(meterRegistry).increment();
    }

    private void reject(HttpServletResponse response, String traceId) throws IOException {
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        ApiResponse<Void> body = ApiResponse.fail(ErrorCode.FORBIDDEN, "请求来源未通过校验", traceId);
        response.getWriter().write(mapper.writeValueAsString(body));
    }

    private static String traceId(HttpServletRequest request) {
        String trace = request.getHeader(AuthHeaders.TRACE_ID);
        return trace == null || trace.isBlank() ? null : trace;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    /** 进程内 nonce 去重（TTL 到期自动失效；容量超限时惰性清理）。 */
    private static final class NonceCache {

        private final Map<String, Long> seen = new ConcurrentHashMap<>();
        private final long ttlMillis;
        private final int maxEntries;

        private NonceCache(long ttlMillis, int maxEntries) {
            this.ttlMillis = ttlMillis;
            this.maxEntries = maxEntries;
        }

        /** @return true 表示首次出现；false 表示 TTL 内重复（重放） */
        boolean register(String key) {
            long now = System.currentTimeMillis();
            if (seen.size() >= maxEntries) {
                seen.entrySet().removeIf(entry -> entry.getValue() <= now);
                if (seen.size() >= maxEntries) {
                    // 极端流量下仍满：整体清空优于无限增长（60 秒窗口内影响可接受）
                    seen.clear();
                }
            }
            boolean[] fresh = {false};
            seen.compute(key, (k, expiry) -> {
                if (expiry == null || expiry <= now) {
                    fresh[0] = true;
                    return now + ttlMillis;
                }
                return expiry;
            });
            return fresh[0];
        }
    }
}
