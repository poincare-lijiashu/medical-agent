package com.medassist.gateway.web;

import com.medassist.common.api.ErrorCode;
import com.medassist.common.security.AuthHeaders;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.webflux.error.ErrorWebExceptionHandler;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * 网关兜底错误处理：保证 5xx（含 504）与下游 4xx 都走统一 {@code ApiResponse} 壳。
 *
 * <p>没有它的时候，Spring Cloud Gateway 的 {@code response-timeout} 到期会抛出
 * {@code ResponseStatusException(504)}，响应体是 Spring 默认的 error JSON，
 * 既没有业务码也没有 traceId——前端只能显示「请求失败（HTTP 504）」这类技术串，
 * 也无法把用户反馈与后端日志对上。
 *
 * <p>约束：不把异常堆栈写进响应体；{@code X-Trace-Id} 同时回写到响应头，便于前端展示与工单追踪。
 */
@Component
@Order(-2)
public class GatewayErrorWebExceptionHandler implements ErrorWebExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GatewayErrorWebExceptionHandler.class);

    @Override
    public Mono<Void> handle(ServerWebExchange exchange, Throwable ex) {
        if (exchange.getResponse().isCommitted()) {
            // 响应已开始写出（例如流式转发中途断开），只能把错误交回上游处理
            return Mono.error(ex);
        }

        String traceId = exchange.getRequest().getHeaders().getFirst(AuthHeaders.TRACE_ID);
        if (traceId == null || traceId.isBlank()) {
            traceId = UUID.randomUUID().toString().replace("-", "");
        }

        HttpStatus status = resolveStatus(ex);
        ErrorCode code = resolveCode(status);

        if (status.is5xxServerError()) {
            log.error("gateway.error status={} code={} traceId={} path={}",
                    status.value(), code.code(), traceId,
                    exchange.getRequest().getPath().value(), ex);
        } else {
            log.warn("gateway.error status={} traceId={} path={}",
                    status.value(), traceId, exchange.getRequest().getPath().value());
        }

        exchange.getResponse().getHeaders().set(AuthHeaders.TRACE_ID, traceId);
        return GatewayResponses.reject(exchange, status, code, code.defaultMessage(), traceId);
    }

    private static HttpStatus resolveStatus(Throwable ex) {
        if (ex instanceof ResponseStatusException rse) {
            HttpStatus resolved = HttpStatus.resolve(rse.getStatusCode().value());
            if (resolved != null) {
                return resolved;
            }
        }
        return HttpStatus.INTERNAL_SERVER_ERROR;
    }

    private static ErrorCode resolveCode(HttpStatus status) {
        return switch (status) {
            case GATEWAY_TIMEOUT -> ErrorCode.GATEWAY_TIMEOUT;
            case SERVICE_UNAVAILABLE, BAD_GATEWAY -> ErrorCode.UPSTREAM_UNAVAILABLE;
            case UNAUTHORIZED -> ErrorCode.UNAUTHORIZED;
            case FORBIDDEN -> ErrorCode.FORBIDDEN;
            case NOT_FOUND -> ErrorCode.NOT_FOUND;
            case CONFLICT -> ErrorCode.CONFLICT;
            case UNPROCESSABLE_ENTITY -> ErrorCode.VALIDATION_FAILED;
            case TOO_MANY_REQUESTS -> ErrorCode.TOO_MANY_REQUESTS;
            case BAD_REQUEST -> ErrorCode.BAD_REQUEST;
            default -> ErrorCode.INTERNAL_ERROR;
        };
    }
}
