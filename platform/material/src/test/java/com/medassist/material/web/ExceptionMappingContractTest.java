package com.medassist.material.web;

import com.medassist.common.api.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 物资错误码 → HTTP 状态契约。
 *
 * <p>新增枚举若漏配分支会落到 default(400)，前端会把业务拒绝当参数错误；
 * 这里用全量遍历 + 关键码断言把"漏配"变成可见失败。
 */
class ExceptionMappingContractTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    @DisplayName("每个错误码都必须有确定的 HTTP 状态")
    void everyErrorCodeHasStatus() {
        for (ErrorCode code : ErrorCode.values()) {
            assertThat(handler.statusOf(code)).as("错误码 %s 未映射 HTTP 状态", code).isNotNull();
        }
    }

    @Test
    @DisplayName("物资专属码映射：不存在 404 / 冲突 409 / 内容不合法 422")
    void materialCodesMapToExpectedStatus() {
        assertThat(handler.statusOf(ErrorCode.CONSUMABLE_NOT_FOUND)).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(handler.statusOf(ErrorCode.CONSUMABLE_ISSUE_ITEM_NOT_FOUND))
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(handler.statusOf(ErrorCode.CONSUMABLE_STOCK_INSUFFICIENT))
                .isEqualTo(HttpStatus.CONFLICT);
        assertThat(handler.statusOf(ErrorCode.CONSUMABLE_BATCH_EXPIRED))
                .isEqualTo(HttpStatus.CONFLICT);
        assertThat(handler.statusOf(ErrorCode.CONSUMABLE_TRACE_EXCEEDS))
                .isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(handler.statusOf(ErrorCode.CONSUMABLE_ISSUE_INVALID))
                .isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
    }

    @Test
    @DisplayName("业务错误码不得落到 5xx")
    void businessCodesAreNotServerErrors() {
        for (ErrorCode code : ErrorCode.values()) {
            String name = code.name();
            if (name.endsWith("UNAVAILABLE") || name.endsWith("TIMEOUT")
                    || code == ErrorCode.INTERNAL_ERROR) {
                continue;
            }
            assertThat(handler.statusOf(code).is5xxServerError())
                    .as("错误码 %s 被映射为 5xx，业务拒绝不应触发客户端重试", code)
                    .isFalse();
        }
    }
}
