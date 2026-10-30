package com.medassist.common.exception;

import com.medassist.common.api.ErrorCode;

/**
 * 业务异常（可预期、可面向医护呈现）。
 *
 * <p>与 {@link ErrorCode} 绑定：错误码稳定、文案面向使用者。
 * 技术异常（NPE、SQL 错误等）一律不包装成本异常，由各服务的全局异常处理器
 * 统一转 {@link ErrorCode#INTERNAL_ERROR} 并落日志，绝不明文透出堆栈。
 */
public class BusinessException extends RuntimeException {

    private final ErrorCode errorCode;

    public BusinessException(ErrorCode errorCode) {
        super(errorCode.defaultMessage());
        this.errorCode = errorCode;
    }

    public BusinessException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public ErrorCode errorCode() {
        return errorCode;
    }
}
