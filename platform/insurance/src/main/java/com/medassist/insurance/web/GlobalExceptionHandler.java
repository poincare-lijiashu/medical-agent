package com.medassist.insurance.web;

import com.medassist.common.api.ErrorCode;
import com.medassist.common.web.AbstractApiExceptionHandler;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 医保异常映射：不存在 404；参保/清单状态/无收费明细 → 409；上游不可用 → 503。
 */
@RestControllerAdvice
public class GlobalExceptionHandler extends AbstractApiExceptionHandler {

    @Override
    protected HttpStatus statusOf(ErrorCode code) {
        return switch (code) {
            case NOT_FOUND, PATIENT_NOT_FOUND, INSURANCE_ITEM_NOT_FOUND,
                 INSURANCE_MEMBER_NOT_FOUND, INSURANCE_BILL_NOT_FOUND,
                 INSURANCE_EXPORT_NOT_FOUND, INSURANCE_SCHEME_NOT_FOUND,
                 INSURANCE_GROUP_NOT_FOUND, INSURANCE_REALTIME_NOT_FOUND -> HttpStatus.NOT_FOUND;
            case UNAUTHORIZED -> HttpStatus.UNAUTHORIZED;
            case FORBIDDEN -> HttpStatus.FORBIDDEN;
            case CONFLICT, PATIENT_DUPLICATE, PATIENT_MERGE_CONFLICT,
                 INSURANCE_NOT_ENROLLED, INSURANCE_BILL_STATUS_INVALID,
                 INSURANCE_NO_CHARGE, INSURANCE_EXPORT_EMPTY,
                 INSURANCE_EXPORT_INVALID, INSURANCE_SCHEME_INVALID,
                 INSURANCE_SCHEME_RATE_DUPLICATE -> HttpStatus.CONFLICT;
            case VALIDATION_FAILED, INSURANCE_SCHEME_RATE_MISSING
                 -> HttpStatus.UNPROCESSABLE_ENTITY;
            case TOO_MANY_REQUESTS -> HttpStatus.TOO_MANY_REQUESTS;
            case UPSTREAM_UNAVAILABLE -> HttpStatus.SERVICE_UNAVAILABLE;
            case GATEWAY_TIMEOUT -> HttpStatus.GATEWAY_TIMEOUT;
            default -> HttpStatus.BAD_REQUEST;
        };
    }
}
