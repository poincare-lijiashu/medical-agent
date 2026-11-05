package com.medassist.clinical.web;

import com.medassist.common.api.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 临床域错误码 → HTTP 状态契约（G78）。
 *
 * <p>{@link GlobalExceptionHandler#statusOf} 用显式 switch 映射，未列出的码会落到
 * {@code default -> BAD_REQUEST}：新增一个"找不到"的专属码却忘了加进 NOT_FOUND 组，
 * 就会把 404 静默变成 400，客户端提示也随之从"记录不存在"变成"参数有误"。
 *
 * <p>守卫的四个专属码统一在 42xxx 段（G78：住院登记/病区/手术记录从通用 40400 拆出来），
 * 本测试锁住它们都是 404。
 */
class ClinicalErrorMappingContractTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    @DisplayName("守卫专属码（就诊/住院登记/病区/手术记录）一律映射 404")
    void guardCodesMapToNotFound() {
        assertThat(handler.statusOf(ErrorCode.VISIT_NOT_FOUND)).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(handler.statusOf(ErrorCode.ADMISSION_NOT_FOUND)).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(handler.statusOf(ErrorCode.WARD_NOT_FOUND)).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(handler.statusOf(ErrorCode.SURGERY_CASE_NOT_FOUND))
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("新增码不允许静默落到默认 400：示例状态与语义一致")
    void representativeCodesMapToExpectedStatus() {
        assertThat(handler.statusOf(ErrorCode.FORBIDDEN)).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(handler.statusOf(ErrorCode.VISIT_STATUS_INVALID)).isEqualTo(HttpStatus.CONFLICT);
        assertThat(handler.statusOf(ErrorCode.VALIDATION_FAILED))
                .isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(handler.statusOf(ErrorCode.UPSTREAM_UNAVAILABLE))
                .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    }
}
