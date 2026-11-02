package com.medassist.common.web;

import com.medassist.common.api.ErrorCode;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * JSON 请求体体积上限（P1-6）。
 *
 * <p>背景：各服务此前没有任何统一的请求体上限——医保目录批量导入只校验"列表非空"，
 * 一个超大 JSON（几十万条）就能把堆内存打满或长时间占用数据库连接（DoS）。
 *
 * <p>策略：
 * <ul>
 *   <li>只拦 {@code application/json}：multipart 上传（知识库文档）有独立的大小校验，不能误伤；</li>
 *   <li>按 {@code Content-Length} 判定，超限立即返回 413，**不读取** 请求体；</li>
 *   <li>默认 2MB（业务 JSON 远小于此；真正的批量导入应走文件上传通道）。</li>
 * </ul>
 *
 * <p>已知边界：chunked 传输没有 {@code Content-Length}，本过滤器不拦；该场景由 Ingress/网关的
 * 请求体限制与反向代理兜底（集群内调用都由 RestClient 带长度，不会走 chunked）。
 */
public class JsonBodyLimitFilter extends OncePerRequestFilter {

    private final long maxBytes;

    public JsonBodyLimitFilter(long maxBytes) {
        this.maxBytes = maxBytes > 0 ? maxBytes : 2L * 1024 * 1024;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        long declared = request.getContentLengthLong();
        if (declared > maxBytes && isJson(request.getContentType())) {
            response.setStatus(HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE);
            response.setCharacterEncoding(StandardCharsets.UTF_8.name());
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.getWriter().write("{\"code\":" + ErrorCode.VALIDATION_FAILED.code()
                    + ",\"message\":\"请求体过大（上限 " + (maxBytes / 1024) + " KB），请改用文件导入通道\"}");
            return;
        }
        chain.doFilter(request, response);
    }

    private static boolean isJson(String contentType) {
        return contentType != null
                && contentType.toLowerCase(java.util.Locale.ROOT).contains("application/json");
    }
}
