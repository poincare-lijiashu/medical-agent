package com.medassist.masterdata.web;

import com.medassist.common.api.ErrorCode;
import com.medassist.common.web.AbstractApiExceptionHandler;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 主数据错误码 → HTTP 状态。
 *
 * <p>语义约定：
 * <ul>
 *   <li>域/条目/映射/申请/批次不存在 → 404（越权与不存在同码，不泄露存在性）；</li>
 *   <li>编码重复、状态机冲突、自审、导入校验和不一致 → 409（可刷新后重试或换个判断）；</li>
 *   <li>内容不合法（字段/日期/映射/导入行）→ 422，前端可直接定位到表单；</li>
 *   <li>源表不可读 → 503（依赖故障，不是用户输入错误）；</li>
 *   <li>缺少真实操作人 → 403（已认证但没有可审计的主体）。</li>
 * </ul>
 */
@RestControllerAdvice
public class MasterDataExceptionHandler extends AbstractApiExceptionHandler {

    @Override
    protected HttpStatus statusOf(ErrorCode code) {
        return switch (code) {
            case MDM_DOMAIN_NOT_FOUND, MDM_ITEM_NOT_FOUND, MDM_MAPPING_NOT_FOUND,
                 MDM_CHANGE_REQUEST_NOT_FOUND, MDM_IMPORT_NOT_FOUND,
                 MDM_EXTERNAL_SYSTEM_NOT_FOUND, MDM_DQ_RULE_NOT_FOUND, MDM_DQ_RUN_NOT_FOUND,
                 MDM_DQ_ISSUE_NOT_FOUND, NOT_FOUND -> HttpStatus.NOT_FOUND;
            case MDM_ITEM_DUPLICATE, MDM_MAPPING_DUPLICATE, MDM_CHANGE_REQUEST_STATUS_INVALID,
                 MDM_CHANGE_REQUEST_SELF_REVIEW, MDM_IMPORT_CHECKSUM_MISMATCH,
                 MDM_DQ_RULE_DUPLICATE, MDM_DQ_ISSUE_STATUS_INVALID,
                 CONFLICT -> HttpStatus.CONFLICT;
            case MDM_CHANGE_REQUEST_INVALID, MDM_MAPPING_INVALID, MDM_IMPORT_INVALID,
                 MDM_ITEM_STATUS_INVALID, MDM_DOMAIN_NOT_SYNCABLE, MDM_REASON_REQUIRED,
                 MDM_DQ_RULE_INVALID, MDM_DQ_SQL_UNSAFE, MDM_DQ_SQL_FAILED,
                 MDM_DQ_ISSUE_RESOLUTION_REQUIRED, MDM_DQ_ISSUE_WAIVER_REASON_REQUIRED,
                 VALIDATION_FAILED -> HttpStatus.UNPROCESSABLE_ENTITY;
            case MDM_ACTOR_REQUIRED, MDM_DQ_ACTOR_REQUIRED, FORBIDDEN -> HttpStatus.FORBIDDEN;
            case UNAUTHORIZED -> HttpStatus.UNAUTHORIZED;
            case MDM_SYNC_FAILED, UPSTREAM_UNAVAILABLE -> HttpStatus.SERVICE_UNAVAILABLE;
            case TOO_MANY_REQUESTS -> HttpStatus.TOO_MANY_REQUESTS;
            case GATEWAY_TIMEOUT -> HttpStatus.GATEWAY_TIMEOUT;
            default -> HttpStatus.BAD_REQUEST;
        };
    }
}
