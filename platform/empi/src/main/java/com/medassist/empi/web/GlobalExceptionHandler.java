package com.medassist.empi.web;

import com.medassist.common.api.ApiResponse;
import com.medassist.common.api.ErrorCode;
import com.medassist.common.exception.BusinessException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.stream.Collectors;

/**
 * 全局异常处理（EMPI 服务）。
 *
 * <p>注意：患者相关业务码按各自语义映射——{@code PATIENT_NOT_FOUND} 用 404，
 * {@code PATIENT_DUPLICATE}（证件号重复）用 409 而非 404，避免调用方误判为"记录不存在"。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiResponse<Void>> handleBusiness(BusinessException e) {
        HttpStatus status = switch (e.errorCode()) {
            case NOT_FOUND, PATIENT_NOT_FOUND -> HttpStatus.NOT_FOUND;
            case UNAUTHORIZED -> HttpStatus.UNAUTHORIZED;
            case FORBIDDEN -> HttpStatus.FORBIDDEN;
            case CONFLICT, PATIENT_DUPLICATE, PATIENT_MERGE_CONFLICT,
                 REGISTRATION_DUPLICATED, DRUG_STOCK_INSUFFICIENT,
                 DRUG_BATCH_EXPIRED -> HttpStatus.CONFLICT;
            case VALIDATION_FAILED -> HttpStatus.UNPROCESSABLE_ENTITY;
            case UPSTREAM_UNAVAILABLE -> HttpStatus.SERVICE_UNAVAILABLE;
            default -> HttpStatus.BAD_REQUEST;
        };
        return ResponseEntity.status(status)
                .body(ApiResponse.fail(e.errorCode(), e.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Void>> handleValidation(MethodArgumentNotValidException e) {
        String msg = e.getBindingResult().getFieldErrors().stream()
                .map(fe -> fe.getDefaultMessage() == null ? fe.getField() : fe.getDefaultMessage())
                .distinct()
                .collect(Collectors.joining("；"));
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
                .body(ApiResponse.fail(ErrorCode.VALIDATION_FAILED, msg));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleUnexpected(Exception e) {
        log.error("unhandled.exception", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiResponse.fail(ErrorCode.INTERNAL_ERROR));
    }
}
