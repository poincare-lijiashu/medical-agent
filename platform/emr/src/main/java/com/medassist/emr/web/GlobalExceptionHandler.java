package com.medassist.emr.web;

import com.medassist.common.api.ErrorCode;
import com.medassist.common.web.AbstractApiExceptionHandler;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 病历异常映射。
 *
 * <p>语义要点：状态机冲突 / 质控阻断 → 409（调用方需要看到"为什么不能签"）；
 * 模板与病历不存在 → 404；参数不合规 → 422；EMPI 不可用 → 503。
 */
@RestControllerAdvice
public class GlobalExceptionHandler extends AbstractApiExceptionHandler {

    @Override
    protected HttpStatus statusOf(ErrorCode code) {
        return switch (code) {
            case NOT_FOUND, PATIENT_NOT_FOUND, EMR_TEMPLATE_NOT_FOUND,
                 EMR_RECORD_NOT_FOUND -> HttpStatus.NOT_FOUND;
            case UNAUTHORIZED -> HttpStatus.UNAUTHORIZED;
            case FORBIDDEN -> HttpStatus.FORBIDDEN;
            case CONFLICT, PATIENT_DUPLICATE, PATIENT_MERGE_CONFLICT,
                 EMR_RECORD_STATUS_INVALID, EMR_QC_BLOCKED -> HttpStatus.CONFLICT;
            case VALIDATION_FAILED -> HttpStatus.UNPROCESSABLE_ENTITY;
            case TOO_MANY_REQUESTS -> HttpStatus.TOO_MANY_REQUESTS;
            case UPSTREAM_UNAVAILABLE -> HttpStatus.SERVICE_UNAVAILABLE;
            default -> HttpStatus.BAD_REQUEST;
        };
    }
}
