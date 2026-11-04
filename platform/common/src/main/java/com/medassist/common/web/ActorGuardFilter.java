package com.medassist.common.web;

import com.medassist.common.api.ErrorCode;
import com.medassist.common.security.AuthHeaders;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Set;

/**
 * 写操作必须带操作者身份（{@code X-Actor}）。
 *
 * <p>问题（多智能体评审 E1）：大量写接口用 {@code defaultValue = "system"} 兜底，
 * 直连服务（绕过网关）时不带头也能成功，审计里出现"system 改了病历/报告/患者"，
 * 追责链断裂；另一些接口又是"缺头 400"，同一平台两套语义。
 *
 * <p>因此把规则下沉为统一过滤器：**所有非 GET 的业务请求都必须带非空 X-Actor**，
 * 缺失、空白或伪造为 {@code system} 一律 400（与 PacsController 现有语义一致）。
 * 内部服务通道（{@code /internal/**}）、健康检查（{@code /actuator/**}）与
 * 登录/刷新（无身份可用）豁免——它们各有自己的鉴权边界。
 *
 * <p>注册在 {@link InternalAuthFilter} 之后：先验签身份，再要求操作者身份。
 */
public class ActorGuardFilter extends OncePerRequestFilter {

    private static final Set<String> WRITE_METHODS = Set.of("POST", "PUT", "PATCH", "DELETE");

    /**
     * 无需 X-Actor 的路径前缀：内部通道、健康检查、登录/刷新与统一身份登录。
     *
     * <p>注意网关的 {@code StripPrefix=3} 会把 {@code /api/v2/auth/login} 剥成 {@code /login}
     * （服务内没有 {@code /auth} 前缀），因此这里必须同时列出带前缀与剥离后的两种形态。
     * SSO 的 authorize/callback 同样发生在登录前，安全由一次性 state + PKCE + nonce 保证。
     */
    private static final String[] EXEMPT_PREFIXES = {
            "/internal/", "/actuator/",
            "/auth/login", "/auth/refresh", "/auth/sso/",
            "/login", "/refresh", "/sso/"
    };

    /** 历史兜底值：出现即视为"没有真实操作者"。 */
    private static final String LEGACY_DEFAULT_ACTOR = "system";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String method = request.getMethod();
        if (method == null || !WRITE_METHODS.contains(method.toUpperCase())) {
            chain.doFilter(request, response);
            return;
        }
        String path = path(request);
        if (isExempt(path)) {
            chain.doFilter(request, response);
            return;
        }
        String actor = request.getHeader(AuthHeaders.ACTOR);
        if (actor == null || actor.isBlank()
                || LEGACY_DEFAULT_ACTOR.equalsIgnoreCase(actor.trim())) {
            reject(response);
            return;
        }
        chain.doFilter(request, response);
    }

    private static String path(HttpServletRequest request) {
        String uri = request.getRequestURI();
        String context = request.getContextPath();
        if (context != null && !context.isEmpty() && uri.startsWith(context)) {
            uri = uri.substring(context.length());
        }
        return uri.isEmpty() ? "/" : uri;
    }

    private static boolean isExempt(String path) {
        for (String prefix : EXEMPT_PREFIXES) {
            if (path.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    private static void reject(HttpServletResponse response) throws IOException {
        response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
        response.setContentType("application/json;charset=UTF-8");
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write("{\"code\":" + ErrorCode.VALIDATION_FAILED.code()
                + ",\"message\":\"缺少操作者身份（X-Actor），写操作已被拒绝\"}");
    }
}
