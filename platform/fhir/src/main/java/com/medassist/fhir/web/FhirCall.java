package com.medassist.fhir.web;

import com.medassist.common.api.ErrorCode;
import com.medassist.common.exception.BusinessException;
import com.medassist.common.security.AuthHeaders;
import com.medassist.common.security.Permissions;
import com.medassist.fhir.config.FhirProperties;
import jakarta.servlet.http.HttpServletRequest;

import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 一次 FHIR 调用的上下文（身份、数据范围、输出格式、外部基址）。
 *
 * <p>集中在一个对象里，而不是让每个 Controller 方法各带 8 个 {@code @RequestHeader}：
 * 头名与校验规则只写一次，新增端点不会漏掉"院区收敛"或"真实操作人"这类硬要求。
 */
public record FhirCall(String actor, String permissions, boolean phiReadable,
                       Set<UUID> campusFilter, String clientIp, String traceId,
                       String baseUrl, FhirFormats.Format format) {

    /**
     * 校验读取权限与操作人身份。
     *
     * <p>{@code system} 不允许作为 PHI 读取的操作人：审计里出现"system 看了患者"
     * 等于追责链断裂（与 EMPI 明文读取的口径一致）。
     */
    public void requireRead() {
        // 权限点必须写字面量：权限矩阵静态锁（tests/test_console_permission_matrix.py）
        // 只解析字面量/本类常量，用别的类的常量会漏检。
        Permissions.require(permissions, "fhir:read");
        if (actor == null || actor.isBlank() || "system".equalsIgnoreCase(actor.trim())) {
            throw new BusinessException(ErrorCode.FORBIDDEN,
                    "FHIR 读取需要真实的操作人身份（X-Actor），不能使用 system 兜底");
        }
    }

    public static FhirCall from(HttpServletRequest request, String formatParam,
                                FhirProperties properties, FhirFormats formats) {
        String permissions = request.getHeader(AuthHeaders.PERMISSIONS);
        String actor = request.getHeader(AuthHeaders.ACTOR);
        String clientIp = request.getHeader(AuthHeaders.CLIENT_IP);
        String traceId = request.getHeader(AuthHeaders.TRACE_ID);
        return new FhirCall(
                actor == null ? null : actor.trim(),
                permissions,
                Permissions.has(permissions, "fhir:read_phi"),
                FhirScopes.campusFilter(request.getHeader(AuthHeaders.CAMPUS)),
                clientIp == null || clientIp.isBlank() ? request.getRemoteAddr() : clientIp,
                traceId,
                baseUrl(request, properties),
                formats.negotiate(formatParam, request.getHeader("Accept")));
    }

    /**
     * Bundle 链接里的外部基址。
     *
     * <p>配置了 {@code medassist.fhir.base-url} 就以配置为准：经网关/Ingress 时
     * 服务端看到的 Host 是内网地址，按请求推断会把 {@code fullUrl} 写成
     * {@code http://127.0.0.1:8090/...}，对端拿到后无法回查。
     */
    private static String baseUrl(HttpServletRequest request, FhirProperties properties) {
        String configured = properties.getBaseUrl();
        if (configured != null && !configured.isBlank()) {
            return trimTrailingSlash(configured.trim());
        }
        String prefix = request.getRequestURI().startsWith("/api/v2/fhir")
                ? "/api/v2/fhir" : "/fhir";
        return request.getScheme() + "://" + request.getServerName() + ":"
                + request.getServerPort() + request.getContextPath() + prefix;
    }

    private static String trimTrailingSlash(String value) {
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }

    /**
     * 审计用的检索参数摘要：只记参数名，不记值。
     *
     * <p>姓名、患者号等检索值本身就是 PHI，落进审计表等于复制一份。
     */
    public static String paramSummary(HttpServletRequest request) {
        return request.getParameterMap().keySet().stream()
                .filter(name -> !name.equals("_format"))
                .sorted()
                .collect(Collectors.joining(","));
    }
}
