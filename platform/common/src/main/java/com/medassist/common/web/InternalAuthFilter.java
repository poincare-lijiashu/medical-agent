package com.medassist.common.web;

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
import java.time.Instant;
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
    private static final String REJECT_MESSAGE = "请求来源未通过校验";
    private static final int MAX_TRACE_LENGTH = 64;
    private static final int MAX_NONCE_ENTRIES = 200_000;

    private final InternalAuthProperties props;
    private final InternalKeySet keySet;
    private final MeterRegistry meterRegistry;
    private final AntPathMatcher pathMatcher = new AntPathMatcher();
    private final NonceCache nonces;

    public InternalAuthFilter(InternalAuthProperties props, InternalKeySet keySet,
                              MeterRegistry meterRegistry) {
        this(props, keySet, meterRegistry, MAX_NONCE_ENTRIES);
    }

    /** 供测试注入容量的构造器：容量策略（过期清理 / 饱和拒绝）需要在小容量下可验证。 */
    InternalAuthFilter(InternalAuthProperties props, InternalKeySet keySet,
                       MeterRegistry meterRegistry, int maxNonceEntries) {
        this.props = props;
        this.keySet = keySet;
        this.meterRegistry = meterRegistry;
        this.nonces = new NonceCache(props.nonceTtlSecondsOrDefault() * 1000L, maxNonceEntries);
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

        String result;
        if (isInternalPath(path)) {
            if (!props.internalPathsSignatureRequiredOrDefault()) {
                // 服务→服务通道：内部令牌仍由各控制器校验；调用方签名开关未打开时不在此拦截
                request.setAttribute(ATTR_CALLER, "internal");
                chain.doFilter(request, response);
                return;
            }
            result = verifyCaller(request);
        } else {
            result = verifyGateway(request);
        }
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
    private String verifyGateway(HttpServletRequest request) {
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

        String canonical = InternalSignature.canonical(request.getMethod(), path(request),
                request.getQueryString(), identityHeaders(request), ts, nonce,
                InternalSignature.EMPTY_BODY_HASH);
        if (!InternalSignature.verify(secret, canonical, signature)) {
            return "bad_sig";
        }
        // 验签通过后才占用 nonce：反过来的话，任何未认证流量都能先灌满 nonce 表（第五轮 R5-M1）
        String nonceResult = registerNonce(keyId + ":" + nonce);
        if (nonceResult != null) {
            return nonceResult;
        }
        request.setAttribute(ATTR_KEY_ID, keyId);
        request.setAttribute(ATTR_CALLER, "gateway");
        return "ok";
    }

    /**
     * 服务→服务通道校验（v1i）：按 {@code X-Internal-Caller} 选密钥，caller 参与规范串。
     *
     * @return ok / missing / bad_version / unknown_caller / skew / replay / bad_sig
     */
    private String verifyCaller(HttpServletRequest request) {
        String version = request.getHeader(InternalSignature.HEADER_SIG_VER);
        String callerHeader = request.getHeader(InternalSignature.HEADER_CALLER);
        String timestamp = request.getHeader(InternalSignature.HEADER_TS);
        String nonce = request.getHeader(InternalSignature.HEADER_NONCE);
        String signature = request.getHeader(InternalSignature.HEADER_SIG);
        if (isBlank(version) || isBlank(callerHeader) || isBlank(timestamp)
                || isBlank(nonce) || isBlank(signature)) {
            return "missing";
        }
        if (!InternalSignature.SIG_VERSION_CALLER.equals(version)) {
            return "bad_version";
        }
        String caller = callerHeader.trim().toLowerCase();
        if (!caller.matches("[a-z0-9-]{1,32}")) {
            return "unknown_caller";
        }
        String secret = keySet.callerSecret(caller);
        if (secret == null) {
            return "unknown_caller";
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

        String canonical = InternalSignature.canonicalCaller(request.getMethod(), path(request),
                request.getQueryString(), caller, ts, nonce, InternalSignature.EMPTY_BODY_HASH);
        if (!InternalSignature.verify(secret, canonical, signature)) {
            return "bad_sig";
        }
        // 同网关通道：验签通过后才占 nonce；前缀带 caller，避免不同调用方互相"烧掉"随机数
        String nonceResult = registerNonce("c:" + caller + ":" + nonce);
        if (nonceResult != null) {
            return nonceResult;
        }
        request.setAttribute(ATTR_KEY_ID, caller);
        request.setAttribute(ATTR_CALLER, caller);
        return "ok";
    }

    /** @return null 表示首次出现（放行）；否则返回拒绝原因 replay / saturated */
    private String registerNonce(String key) {
        return switch (nonces.register(key)) {
            case FRESH -> null;
            case REPLAY -> "replay";
            case SATURATED -> "saturated";
        };
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
        // 手写响应体：不依赖 Jackson 的 JavaTime 模块。
        // Boot 4 的 Web 层已切到 Jackson 3，若这里用 Jackson 2 序列化 Instant 会抛
        // InvalidDefinitionException（实测 500），拒绝响应必须是"绝不失败"的路径。
        StringBuilder body = new StringBuilder(160)
                .append("{\"code\":").append(ErrorCode.FORBIDDEN.code())
                .append(",\"message\":\"").append(REJECT_MESSAGE).append('"');
        if (traceId != null) {
            body.append(",\"traceId\":\"").append(traceId).append('"');
        }
        body.append(",\"timestamp\":\"").append(Instant.now()).append("\"}");
        response.getWriter().write(body.toString());
    }

    /** traceId 只允许安全字符并限长，避免把客户端头原样拼进 JSON。 */
    private static String traceId(HttpServletRequest request) {
        String trace = request.getHeader(AuthHeaders.TRACE_ID);
        if (trace == null || trace.isBlank()) {
            return null;
        }
        String value = trace.trim();
        if (value.length() > MAX_TRACE_LENGTH) {
            value = value.substring(0, MAX_TRACE_LENGTH);
        }
        return value.matches("[A-Za-z0-9_.-]+") ? value : null;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    /**
     * 进程内 nonce 去重（TTL 到期自动失效）。
     *
     * <p>容量策略：优先清理已过期条目；仍然满额时**拒绝新 nonce**（fail-closed，返回 SATURATED
     * 由调用方按 403 处理），绝不整体清空——清空等于主动把已用过的 nonce 全部放回可用状态，
     * 在 TTL 窗口内重新打开重放（第五轮 R5-M1）。
     */
    private static final class NonceCache {

        enum Outcome { FRESH, REPLAY, SATURATED }

        private static final int PURGE_BUDGET = 2000;

        private final Map<String, Long> seen = new ConcurrentHashMap<>();
        private final java.util.Queue<String> insertionOrder = new java.util.concurrent.ConcurrentLinkedQueue<>();
        private final long ttlMillis;
        private final int maxEntries;

        private NonceCache(long ttlMillis, int maxEntries) {
            this.ttlMillis = ttlMillis;
            this.maxEntries = maxEntries;
        }

        Outcome register(String key) {
            long now = System.currentTimeMillis();
            if (seen.size() >= maxEntries && !purgeExpired(now)) {
                return Outcome.SATURATED;
            }
            boolean[] fresh = {false};
            seen.compute(key, (k, expiry) -> {
                if (expiry == null || expiry <= now) {
                    fresh[0] = true;
                    return now + ttlMillis;
                }
                return expiry;
            });
            if (fresh[0]) {
                insertionOrder.add(key);
            }
            return fresh[0] ? Outcome.FRESH : Outcome.REPLAY;
        }

        /** 按插入顺序从头部清理过期条目，单次最多 PURGE_BUDGET 条，避免长尾延迟。 */
        private boolean purgeExpired(long now) {
            int budget = PURGE_BUDGET;
            while (budget-- > 0) {
                String head = insertionOrder.poll();
                if (head == null) {
                    break;
                }
                Long expiry = seen.get(head);
                if (expiry == null) {
                    continue;
                }
                if (expiry <= now) {
                    seen.remove(head, expiry);
                } else {
                    // 头部尚未过期：说明整体都还新鲜，停止（放回队尾以保持顺序近似）
                    insertionOrder.add(head);
                    break;
                }
            }
            return seen.size() < maxEntries;
        }
    }
}
