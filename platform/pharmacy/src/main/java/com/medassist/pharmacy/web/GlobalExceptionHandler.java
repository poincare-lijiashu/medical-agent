package com.medassist.pharmacy.web;

import com.medassist.common.api.ErrorCode;
import com.medassist.common.web.AbstractApiExceptionHandler;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 药房异常映射。
 *
 * <p>语义要点：
 * <ul>
 *   <li>库存不足 / 批次过期 / 状态机冲突 → <b>409</b>（业务冲突，调用方应提示而不是重试同一个请求）；</li>
 *   <li>药品、处方、发药单不存在 → 404；</li>
 *   <li>参数不符合规则 → 422；</li>
 *   <li>EMPI 不可用 → 503（开方必须失败，不能"先开方后补患者"）。</li>
 * </ul>
 */
@RestControllerAdvice
public class GlobalExceptionHandler extends AbstractApiExceptionHandler {

    @Override
    protected HttpStatus statusOf(ErrorCode code) {
        return switch (code) {
            case NOT_FOUND, PATIENT_NOT_FOUND, DRUG_NOT_FOUND, PRESCRIPTION_NOT_FOUND,
                 DISPENSE_NOT_FOUND, NARCOTIC_REGISTER_NOT_FOUND,
                 NARCOTIC_PRESCRIBER_NOT_FOUND,
                 SUPPLIER_NOT_FOUND, PURCHASE_ORDER_NOT_FOUND, PURCHASE_ITEM_NOT_FOUND,
                 GOODS_RECEIPT_NOT_FOUND, STOCK_TAKE_NOT_FOUND,
                 STOCK_TAKE_ITEM_NOT_FOUND, STOCK_LOSS_NOT_FOUND,
                 STOCK_LOSS_BATCH_NOT_FOUND -> HttpStatus.NOT_FOUND;
            case UNAUTHORIZED -> HttpStatus.UNAUTHORIZED;
            case FORBIDDEN, STOCK_TAKE_SELF_APPROVAL,
                 STOCK_LOSS_SELF_REVIEW -> HttpStatus.FORBIDDEN;
            case CONFLICT, PATIENT_DUPLICATE, PATIENT_MERGE_CONFLICT,
                 DRUG_STOCK_INSUFFICIENT, DRUG_BATCH_EXPIRED,
                 PRESCRIPTION_STATUS_INVALID,
                 NARCOTIC_PRESCRIBER_STATE_INVALID,
                 ORDER_STATUS_INVALID, CHARGE_STATUS_INVALID,
                 SUPPLIER_INACTIVE, SUPPLIER_LICENSE_EXPIRED,
                 PURCHASE_ORDER_STATUS_INVALID,
                 STOCK_TAKE_STATUS_INVALID, STOCK_TAKE_ACTIVE_EXISTS,
                 STOCK_TAKE_STOCK_DRIFT,
                 STOCK_LOSS_STATUS_INVALID, STOCK_LOSS_STOCK_INSUFFICIENT -> HttpStatus.CONFLICT;
            case VALIDATION_FAILED, NARCOTIC_PRESCRIBER_REQUIRED, NARCOTIC_AMPOULE_OUTSTANDING,
                 NARCOTIC_AMPOULE_INVALID,
                 // P1-D8：退药数量超过已发数量是"请求内容不合法"，不是状态冲突（422）
                 DRUG_RETURN_EXCEEDS,
                 // 验收超量/明细重复同属请求内容不合法（422），客户端应改单而不是重试
                 PURCHASE_RECEIPT_EXCEEDS, PURCHASE_RECEIPT_INVALID,
                 STOCK_TAKE_INCOMPLETE, STOCK_TAKE_EMPTY,
                 STOCK_TAKE_VARIANCE_REASON_REQUIRED,
                 STOCK_LOSS_ITEM_INVALID, STOCK_LOSS_EXCEEDS,
                 STOCK_LOSS_REJECT_REASON_REQUIRED -> HttpStatus.UNPROCESSABLE_ENTITY;
            case TOO_MANY_REQUESTS -> HttpStatus.TOO_MANY_REQUESTS;
            case UPSTREAM_UNAVAILABLE -> HttpStatus.SERVICE_UNAVAILABLE;
            case GATEWAY_TIMEOUT -> HttpStatus.GATEWAY_TIMEOUT;
            default -> HttpStatus.BAD_REQUEST;
        };
    }
}
