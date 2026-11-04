package com.medassist.clinical.web;

import com.medassist.common.api.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 错误码 → HTTP 状态契约测试。
 *
 * <p>为什么需要：{@code statusOf} 是一个 switch，新增 {@link ErrorCode} 时忘加分支会落进
 * {@code default -> 400}，服务层单测全绿、线上却把"流程未完成/状态非法"报成"参数错误"。
 * 本测试用枚举全量遍历把"漏配"变成编译后的可见失败，并对高危码给出精确断言。
 */
class ClinicalExceptionMappingTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    @DisplayName("每个错误码都必须有确定的 HTTP 状态（不允许抛异常/返回 null）")
    void everyErrorCodeHasStatus() {
        for (ErrorCode code : ErrorCode.values()) {
            HttpStatus status = handler.statusOf(code);
            assertThat(status).as("错误码 %s 未映射 HTTP 状态", code).isNotNull();
        }
    }

    @Test
    @DisplayName("高危错误码必须映射到语义正确的状态（防漏配回归）")
    void criticalCodesMapCorrectly() {
        assertThat(handler.statusOf(ErrorCode.ORDER_TRANSFER_INCOMPLETE)).isEqualTo(HttpStatus.CONFLICT);
        assertThat(handler.statusOf(ErrorCode.ORDER_STATUS_INVALID)).isEqualTo(HttpStatus.CONFLICT);
        assertThat(handler.statusOf(ErrorCode.VISIT_STATUS_INVALID)).isEqualTo(HttpStatus.CONFLICT);
        assertThat(handler.statusOf(ErrorCode.REGISTRATION_DUPLICATED)).isEqualTo(HttpStatus.CONFLICT);
        assertThat(handler.statusOf(ErrorCode.VALIDATION_FAILED)).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(handler.statusOf(ErrorCode.NOT_FOUND)).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(handler.statusOf(ErrorCode.ORDER_ITEM_NOT_FOUND)).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(handler.statusOf(ErrorCode.FORBIDDEN)).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(handler.statusOf(ErrorCode.UNAUTHORIZED)).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(handler.statusOf(ErrorCode.TOO_MANY_REQUESTS)).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(handler.statusOf(ErrorCode.UPSTREAM_UNAVAILABLE)).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(handler.statusOf(ErrorCode.GATEWAY_TIMEOUT)).isEqualTo(HttpStatus.GATEWAY_TIMEOUT);
        // 临床路径（G29）：找不到 404、状态冲突 409、内容/原因缺失 422
        assertThat(handler.statusOf(ErrorCode.PATHWAY_TEMPLATE_NOT_FOUND)).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(handler.statusOf(ErrorCode.PATHWAY_INSTANCE_NOT_FOUND)).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(handler.statusOf(ErrorCode.PATHWAY_TASK_NOT_FOUND)).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(handler.statusOf(ErrorCode.PATHWAY_TEMPLATE_DUPLICATE)).isEqualTo(HttpStatus.CONFLICT);
        assertThat(handler.statusOf(ErrorCode.PATHWAY_INSTANCE_EXISTS)).isEqualTo(HttpStatus.CONFLICT);
        assertThat(handler.statusOf(ErrorCode.PATHWAY_INSTANCE_INCOMPLETE)).isEqualTo(HttpStatus.CONFLICT);
        assertThat(handler.statusOf(ErrorCode.PATHWAY_VISIT_INVALID)).isEqualTo(HttpStatus.CONFLICT);
        assertThat(handler.statusOf(ErrorCode.PATHWAY_TEMPLATE_INVALID))
                .isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(handler.statusOf(ErrorCode.PATHWAY_TASK_SKIP_REASON_REQUIRED))
                .isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(handler.statusOf(ErrorCode.PATHWAY_EXIT_REASON_REQUIRED))
                .isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
    }

    @Test
    @DisplayName("业务错误码不得落到 5xx 兜底（否则前端会把业务拒绝当系统故障重试）")
    void businessCodesAreNotServerErrors() {
        for (ErrorCode code : ErrorCode.values()) {
            // 这两个本身就是"上游/系统不可用"，应为 5xx；其余业务拒绝不应触发客户端重试
            if (code == ErrorCode.UPSTREAM_UNAVAILABLE || code == ErrorCode.GATEWAY_TIMEOUT
                    || code == ErrorCode.INTERNAL_ERROR) {
                continue;
            }
            HttpStatus status = handler.statusOf(code);
            assertThat(status.is5xxServerError())
                    .as("错误码 %s 被映射为 5xx，业务拒绝不应触发客户端重试", code)
                    .isFalse();
        }
    }
}
