package com.medassist.common.web;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.MDC;

import java.io.IOException;

/**
 * 把网关下发的 {@code X-Trace-Id} 放进日志 MDC，实现 trace ↔ log 互跳（M12）。
 *
 * <p>背景：SkyWalking 只保证链路可查，但应用日志里没有 traceId 时，
 * 出问题后无法从一条错误日志定位到具体链路，也无法从链路跳到该次请求的全部日志。
 * 网关对每个请求都生成并下发 {@code X-Trace-Id}，这里将其写入 MDC 键 {@code tid}，
 * logback 用 {@code %X{tid:-}} 输出；请求结束必须清理，避免线程复用时串号。
 *
 * <p>只在 MVC（Servlet）服务注册；网关是 WebFlux，且其日志本身已带 traceId。
 */
public class TraceMdcFilter implements Filter {

    /** MDC 键，与 logback-spring.xml 的 %X{tid} 对应。 */
    public static final String MDC_KEY = "tid";
    public static final String HEADER = "X-Trace-Id";

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        String traceId = null;
        if (request instanceof HttpServletRequest http) {
            traceId = http.getHeader(HEADER);
        }
        if (traceId != null && !traceId.isBlank()) {
            MDC.put(MDC_KEY, traceId.trim());
        }
        try {
            chain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_KEY);
        }
    }
}
