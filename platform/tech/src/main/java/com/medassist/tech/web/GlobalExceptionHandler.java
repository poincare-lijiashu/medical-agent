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
                 TECH_REPORT_NOT_FOUND, TECH_CRITICAL_NOT_FOUND,
                 TECH_PACS_STUDY_NOT_FOUND, TECH_PACS_REMOTE_NOT_FOUND,
                 TECH_READING_TASK_NOT_FOUND, TECH_DEVICE_NOT_FOUND,
                 TECH_REPORT_TEMPLATE_NOT_FOUND, TECH_WORK_ORDER_NOT_FOUND
                 -> HttpStatus.NOT_FOUND;
            case UNAUTHORIZED -> HttpStatus.UNAUTHORIZED;
            case FORBIDDEN, TECH_READING_NOT_ASSIGNEE -> HttpStatus.FORBIDDEN;
            case CONFLICT, PATIENT_DUPLICATE, PATIENT_MERGE_CONFLICT,
                 TECH_APPLICATION_STATUS_INVALID, TECH_REPORT_STATUS_INVALID,
                 TECH_CRITICAL_STATUS_INVALID, TECH_PACS_STATUS_INVALID,
                 TECH_READING_STATUS_INVALID, TECH_READING_STUDY_NOT_READY,
                 TECH_READING_REPORT_NOT_FINAL,
                 // G35：设备编号重复 / 状态冲突 / 模板编码重复 / 模板状态冲突
                 TECH_DEVICE_DUPLICATE, TECH_DEVICE_STATUS_INVALID,
                 TECH_REPORT_TEMPLATE_DUPLICATE, TECH_REPORT_TEMPLATE_STATUS_INVALID,
                 TECH_WORK_ORDER_STATUS_INVALID, TECH_WORK_ORDER_SELF_ACCEPT,
                 DRUG_STOCK_INSUFFICIENT, DRUG_BATCH_EXPIRED -> HttpStatus.CONFLICT;
            case VALIDATION_FAILED, TECH_DEVICE_QC_INVALID, TECH_REPORT_TEMPLATE_INVALID,
                 TECH_REPORT_STRUCTURED_INVALID, TECH_WORK_ORDER_INVALID,
                 TECH_WORK_ORDER_CANCEL_REASON_REQUIRED -> HttpStatus.UNPROCESSABLE_ENTITY;
            case TOO_MANY_REQUESTS -> HttpStatus.TOO_MANY_REQUESTS;
            case UPSTREAM_UNAVAILABLE, TECH_PACS_NOT_CONFIGURED,
                 TECH_PACS_UPSTREAM_FAILED -> HttpStatus.SERVICE_UNAVAILABLE;
            case GATEWAY_TIMEOUT -> HttpStatus.GATEWAY_TIMEOUT;
            default -> HttpStatus.BAD_REQUEST;
        };
    }
}
