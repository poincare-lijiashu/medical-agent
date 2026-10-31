package com.medassist.auth.web;

import com.medassist.auth.security.LoginThrottleProperties;
import com.medassist.common.api.ErrorCode;
import com.medassist.common.exception.BusinessException;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.mock.http.MockHttpInputMessage;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 异常映射测试（M7：客户端输入错误此前统一返回 500）。
 *
 * <p>覆盖：类型不匹配 / 报文不可读 / 缺参 → 400；唯一约束 → 409；429 带真实 Retry-After。
 */
class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler(
            new LoginThrottleProperties(true, 5, 30, Duration.ofMinutes(5)));

    @Test
    void typeMismatchMapsTo400() {
        MethodArgumentTypeMismatchException e = new MethodArgumentTypeMismatchException(
                "abc", UUID.class, "id", null, new IllegalArgumentException("bad uuid"));

        ResponseEntity<?> response = handler.handleTypeMismatch(e);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().toString()).contains("40000").contains("id");
    }

    @Test
    void unreadableBodyMapsTo400() {
        ResponseEntity<?> response = handler.handleUnreadable(
                new HttpMessageNotReadableException("bad json", new MockHttpInputMessage(new byte[0])));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void missingParamMapsTo400() {
        ResponseEntity<?> response = handler.handleMissingParam(
                new MissingServletRequestParameterException("from", "Instant"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().toString()).contains("from");
    }

    @Test
    void dataIntegrityViolationMapsTo409() {
        ResponseEntity<?> response = handler.handleIntegrity(
                new DataIntegrityViolationException("duplicate key"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void tooManyRequestsCarriesRealRetryAfterFromThrottleWindow() {
        ResponseEntity<?> response = handler.handleBusiness(
                new BusinessException(ErrorCode.TOO_MANY_REQUESTS));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        // 窗口 5 分钟 → 300s（不再写死，但必须与配置一致）
        assertThat(response.getHeaders().getFirst("Retry-After")).isEqualTo("300");
    }

    @Test
    void businessConflictUses409() {
        ResponseEntity<?> response = handler.handleBusiness(new BusinessException(ErrorCode.CONFLICT));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }
}
