package com.medassist.gateway.web;

import com.medassist.common.api.ApiResponse;
import com.medassist.common.api.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;

/**
 * 网关统一错误响应（鉴权失败、限流、内部路径拒绝共用）。
 *
 * <p>与下游服务同一 {@link ApiResponse} 契约，前端/集成方无需分支解析；
 * 响应体不包含内部异常细节。
 */
public final class GatewayResponses {

    private static final Logger log = LoggerFactory.getLogger(GatewayResponses.class);
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private GatewayResponses() {
    }

    public static Mono<Void> reject(ServerWebExchange exchange, HttpStatus status, ErrorCode code,
                                    String message, String traceId) {
        ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(status);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        if (status == HttpStatus.UNAUTHORIZED) {
            response.getHeaders().set(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
        }
        if (status == HttpStatus.TOO_MANY_REQUESTS) {
            response.getHeaders().set(HttpHeaders.RETRY_AFTER, "1");
        }
        byte[] body = serialize(ApiResponse.fail(code, message, traceId));
        response.getHeaders().setContentLength(body.length);
        return response.writeWith(Mono.just(response.bufferFactory().wrap(body)));
    }

    private static byte[] serialize(ApiResponse<?> payload) {
        try {
            return JSON.writeValueAsString(payload).getBytes(StandardCharsets.UTF_8);
        } catch (RuntimeException e) {
            // 序列化不应失败；兜底返回最小合法结构，保证客户端拿到状态码而不是空响应
            log.error("gateway.response.serialize.failed", e);
            return ("{\"code\":" + payload.code() + ",\"message\":\"" + payload.message() + "\"}")
                    .getBytes(StandardCharsets.UTF_8);
        }
    }
}
