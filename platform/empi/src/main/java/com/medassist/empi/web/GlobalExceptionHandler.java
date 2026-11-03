package com.medassist.empi.web;

import com.medassist.common.api.ErrorCode;
import com.medassist.common.web.AbstractApiExceptionHandler;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * EMPI 服务的异常映射（实现基类，仅给出本域语义差异）。
 *
 * <p>患者相关业务码按各自语义映射：{@code PATIENT_NOT_FOUND} 用 404，
 * {@code PATIENT_DUPLICATE}（证件号重复）与 {@code PATIENT_MERGE_CONFLICT}（合并冲突）用 409，
 * 避免调用方误判为"记录不存在"。
 */
@RestControllerAdvice
public class GlobalExceptionHandler extends AbstractApiExceptionHandler {

    @Override
    protected HttpStatus statusOf(ErrorCode code) {
        return switch (code) {
            case NOT_FOUND, PATIENT_NOT_FOUND, REGISTRATION_NO_SLOT -> HttpStatus.NOT_FOUND;
            case UNAUTHORIZED -> HttpStatus.UNAUTHORIZED;
            case FORBIDDEN -> HttpStatus.FORBIDDEN;
            case CONFLICT, PATIENT_DUPLICATE, PATIENT_MERGE_CONFLICT, REGISTRATION_DUPLICATED,
                 DRUG_STOCK_INSUFFICIENT, DRUG_BATCH_EXPIRED -> HttpStatus.CONFLICT;
            case VALIDATION_FAILED -> HttpStatus.UNPROCESSABLE_ENTITY;
            case TOO_MANY_REQUESTS -> HttpStatus.TOO_MANY_REQUESTS;
            case UPSTREAM_UNAVAILABLE -> HttpStatus.SERVICE_UNAVAILABLE;
            case GATEWAY_TIMEOUT -> HttpStatus.GATEWAY_TIMEOUT;
            default -> HttpStatus.BAD_REQUEST;
        };
    }
}
