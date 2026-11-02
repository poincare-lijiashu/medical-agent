package com.medassist.clinical.web;

import com.medassist.common.api.ErrorCode;
import com.medassist.common.web.AbstractApiExceptionHandler;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 临床核心异常映射。
 *
 * <p>语义要点：
 * <ul>
 *   <li>号源已满 / 重复挂号 / 状态机冲突 → <b>409</b>（业务冲突，不是"找不到"）；</li>
 *   <li>就诊、医嘱项目、收费单不存在 → 404；</li>
 *   <li>参数不符合规则 → 422；</li>
 *   <li>EMPI 不可用 → 503（挂号必须失败，客户端应重试而不是"先挂号后补人"）。</li>
 * </ul>
 */
@RestControllerAdvice
public class GlobalExceptionHandler extends AbstractApiExceptionHandler {

    @Override
    protected HttpStatus statusOf(ErrorCode code) {
        return switch (code) {
            case NOT_FOUND, VISIT_NOT_FOUND, PATIENT_NOT_FOUND, ORDER_ITEM_NOT_FOUND,
                 CHARGE_NOT_FOUND -> HttpStatus.NOT_FOUND;
            case UNAUTHORIZED -> HttpStatus.UNAUTHORIZED;
            case FORBIDDEN -> HttpStatus.FORBIDDEN;
            case CONFLICT, PATIENT_DUPLICATE, PATIENT_MERGE_CONFLICT,
                 REGISTRATION_NO_SLOT, REGISTRATION_DUPLICATED,
                 VISIT_STATUS_INVALID, ORDER_STATUS_INVALID, CHARGE_STATUS_INVALID,
                 // 转送未完成不是"参数错误"：是流程未走完，语义为业务冲突（结算被拒）
                 ORDER_TRANSFER_INCOMPLETE,
                 DRUG_STOCK_INSUFFICIENT, DRUG_BATCH_EXPIRED -> HttpStatus.CONFLICT;
            case VALIDATION_FAILED -> HttpStatus.UNPROCESSABLE_ENTITY;
            case TOO_MANY_REQUESTS -> HttpStatus.TOO_MANY_REQUESTS;
            case UPSTREAM_UNAVAILABLE -> HttpStatus.SERVICE_UNAVAILABLE;
            default -> HttpStatus.BAD_REQUEST;
        };
    }
}
