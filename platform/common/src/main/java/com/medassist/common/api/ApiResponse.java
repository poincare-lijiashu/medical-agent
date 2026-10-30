package com.medassist.common.api;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;

/**
 * 全平台统一响应契约。
 *
 * <p>设计约束（对齐计划书 D6 契约先行与 D12 验收矩阵）：
 * <ul>
 *   <li>成功与失败同一外壳，前端/外部集成方无需分支解析；</li>
 *   <li>{@code traceId} 与网关的链路追踪 ID 对齐，用于 SkyWalking / 日志检索；</li>
 *   <li>不暴露内部异常堆栈，只暴露 {@link ErrorCode} 稳定码 + 面向医护的中文文案。</li>
 * </ul>
 *
 * @param code    业务码，0 表示成功
 * @param message 面向使用者的说明（医护可见，禁止出现英文技术术语）
 * @param data    业务数据
 * @param traceId 链路追踪 ID
 * @param timestamp 服务端生成时间（UTC）
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiResponse<T>(
        int code,
        String message,
        T data,
        String traceId,
        Instant timestamp
) {

    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>(ErrorCode.OK.code(), ErrorCode.OK.defaultMessage(), data, null, Instant.now());
    }

    public static <T> ApiResponse<T> ok(T data, String traceId) {
        return new ApiResponse<>(ErrorCode.OK.code(), ErrorCode.OK.defaultMessage(), data, traceId, Instant.now());
    }

    public static <T> ApiResponse<T> fail(ErrorCode errorCode) {
        return new ApiResponse<>(errorCode.code(), errorCode.defaultMessage(), null, null, Instant.now());
    }

    public static <T> ApiResponse<T> fail(ErrorCode errorCode, String message) {
        return new ApiResponse<>(errorCode.code(), message, null, null, Instant.now());
    }
}
