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
                 EMR_RECORD_NOT_FOUND, EMR_CHANGE_REQUEST_NOT_FOUND,
                 EMR_HOMEPAGE_NOT_FOUND, EMR_HQMS_NOT_FOUND,
                 ICD_ITEM_NOT_FOUND, CODING_NOT_FOUND,
                 CODING_CORRECTION_NOT_FOUND, CA_SIGNATURE_NOT_FOUND,
                 CONSENT_NOT_FOUND, CONSENT_TEMPLATE_NOT_FOUND -> HttpStatus.NOT_FOUND;
            case UNAUTHORIZED -> HttpStatus.UNAUTHORIZED;
            case FORBIDDEN, CODING_CORRECTION_SELF_REVIEW -> HttpStatus.FORBIDDEN;
            case CONFLICT, PATIENT_DUPLICATE, PATIENT_MERGE_CONFLICT,
                 EMR_RECORD_STATUS_INVALID, EMR_QC_BLOCKED,
                 EMR_SIGN_LEVEL_INVALID, EMR_CHANGE_REQUEST_INVALID,
                 EMR_HOMEPAGE_STATUS_INVALID, EMR_HOMEPAGE_INCOMPLETE,
                 EMR_HOMEPAGE_SOURCE_INVALID, EMR_HQMS_EXPORT_INVALID,
                 EMR_HQMS_EXPORT_EMPTY, HOMEPAGE_NOT_CODED,
                 CODING_STATUS_INVALID, CODING_CORRECTION_NOT_CODED,
                 CODING_CORRECTION_STATUS_INVALID,
                 CA_SIGNATURE_STATUS_INVALID, CA_VERIFY_FAILED, CA_SIGNATURE_EXISTS,
                 CONSENT_STATUS_INVALID, CONSENT_TEMPLATE_STATUS_INVALID
                 -> HttpStatus.CONFLICT;
            // 快照损坏属"这份数据不可用"（G79），必须 4xx：5xx 会让客户端当成系统故障重试。
            // 具体原因由异常文案说明（"请核对来源数据"），便于病案室定位。
            case EMR_HOMEPAGE_SNAPSHOT_INVALID,
                 VALIDATION_FAILED, EMR_SIGN_POLICY_INVALID,
                 CODING_INVALID, CODING_CORRECTION_INVALID,
                 CODING_CORRECTION_REASON_REQUIRED,
                 CODING_CORRECTION_CANCEL_REASON_REQUIRED,
                 CA_SIGNATURE_INVALID, CA_REVOKE_REASON_REQUIRED,
                 CONSENT_SIGNER_REQUIRED, CONSENT_REVOKE_REASON_REQUIRED,
                 CONSENT_SIGNATURE_INVALID
                 -> HttpStatus.UNPROCESSABLE_ENTITY;
            case TOO_MANY_REQUESTS -> HttpStatus.TOO_MANY_REQUESTS;
            case UPSTREAM_UNAVAILABLE, CA_PROVIDER_UNAVAILABLE -> HttpStatus.SERVICE_UNAVAILABLE;
            case GATEWAY_TIMEOUT -> HttpStatus.GATEWAY_TIMEOUT;
            default -> HttpStatus.BAD_REQUEST;
        };
    }
}
