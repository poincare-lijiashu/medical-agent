package com.medassist.tech.web;

import com.medassist.common.api.ErrorCode;
import com.medassist.common.web.AbstractApiExceptionHandler;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 医技异常映射。
 *
 * <p>语义要点：状态机冲突 / 危急值未闭环 → 409；申请、标本、报告不存在 → 404；
 * 参数不合规 → 422；EMPI 不可用 → 503。
 */
@RestControllerAdvice
public class GlobalExceptionHandler extends AbstractApiExceptionHandler {

    @Override
    protected HttpStatus statusOf(ErrorCode code) {
        return switch (code) {
            case NOT_FOUND, PATIENT_NOT_FOUND, TECH_EXAM_ITEM_NOT_FOUND,
                 TECH_APPLICATION_NOT_FOUND, TECH_SPECIMEN_NOT_FOUND,
                 TECH_REPORT_NOT_FOUND, TECH_CRITICAL_NOT_FOUND -> HttpStatus.NOT_FOUND;
            case UNAUTHORIZED -> HttpStatus.UNAUTHORIZED;
            case FORBIDDEN -> HttpStatus.FORBIDDEN;
            case CONFLICT, PATIENT_DUPLICATE, PATIENT_MERGE_CONFLICT,
                 TECH_APPLICATION_STATUS_INVALID, TECH_REPORT_STATUS_INVALID,
                 TECH_CRITICAL_STATUS_INVALID,
                 DRUG_STOCK_INSUFFICIENT, DRUG_BATCH_EXPIRED -> HttpStatus.CONFLICT;
            case VALIDATION_FAILED -> HttpStatus.UNPROCESSABLE_ENTITY;
            case TOO_MANY_REQUESTS -> HttpStatus.TOO_MANY_REQUESTS;
            case UPSTREAM_UNAVAILABLE -> HttpStatus.SERVICE_UNAVAILABLE;
            default -> HttpStatus.BAD_REQUEST;
        };
    }
}
