package com.medassist.material.web;

import com.medassist.common.api.ErrorCode;
import com.medassist.common.web.AbstractApiExceptionHandler;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 物资耗材异常映射。
 *
 * <p>语义：
 * <ul>
 *   <li>耗材/批次/出库单不存在 → 404；</li>
 *   <li>库存不足、批次过期 → 409（业务冲突，改单或换批，不是重试同请求）；</li>
 *   <li>出库明细重复、追溯超量 → 422（请求内容不合法）。</li>
 * </ul>
 */
@RestControllerAdvice
public class GlobalExceptionHandler extends AbstractApiExceptionHandler {

    @Override
    protected HttpStatus statusOf(ErrorCode code) {
        return switch (code) {
            case NOT_FOUND, CONSUMABLE_NOT_FOUND, CONSUMABLE_BATCH_NOT_FOUND,
                 CONSUMABLE_ISSUE_NOT_FOUND, CONSUMABLE_ISSUE_ITEM_NOT_FOUND,
                 CONSUMABLE_RETURN_NOT_FOUND, CONSUMABLE_RETURN_BATCH_NOT_FOUND -> HttpStatus.NOT_FOUND;
            case UNAUTHORIZED -> HttpStatus.UNAUTHORIZED;
            case FORBIDDEN, CONSUMABLE_RETURN_SELF_REVIEW -> HttpStatus.FORBIDDEN;
            case CONFLICT, CONSUMABLE_STOCK_INSUFFICIENT, CONSUMABLE_BATCH_EXPIRED,
                 CONSUMABLE_RETURN_STATUS_INVALID,
                 CONSUMABLE_DEPT_STOCK_INSUFFICIENT -> HttpStatus.CONFLICT;
            case VALIDATION_FAILED, CONSUMABLE_TRACE_EXCEEDS, CONSUMABLE_ISSUE_INVALID,
                 CONSUMABLE_RETURN_ITEM_INVALID, CONSUMABLE_RETURN_REJECT_REASON_REQUIRED,
                 CONSUMABLE_RETURN_CANCEL_REASON_REQUIRED -> HttpStatus.UNPROCESSABLE_ENTITY;
            case TOO_MANY_REQUESTS -> HttpStatus.TOO_MANY_REQUESTS;
            case UPSTREAM_UNAVAILABLE -> HttpStatus.SERVICE_UNAVAILABLE;
            case GATEWAY_TIMEOUT -> HttpStatus.GATEWAY_TIMEOUT;
            default -> HttpStatus.BAD_REQUEST;
        };
    }
}
