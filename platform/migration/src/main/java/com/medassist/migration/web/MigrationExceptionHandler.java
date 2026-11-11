package com.medassist.migration.web;

import com.medassist.common.api.ErrorCode;
import com.medassist.common.web.AbstractApiExceptionHandler;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 迁移错误码 → HTTP 状态。
 *
 * <p>语义约定：
 * <ul>
 *   <li>目标/批次/档案不存在 → 404；</li>
 *   <li>批次状态不允许、存在阻塞行、回滚冲突、依赖顺序错误 → 409（需要人重新判断）；</li>
 *   <li>模板/行/档案内容不合法 → 422（可直接定位到列或行）；</li>
 *   <li>缺少真实操作人 → 403；</li>
 *   <li>加密载荷无法解密 → 409（通常是密钥轮换后旧批次失效，需要人工确认）。</li>
 * </ul>
 */
@RestControllerAdvice
public class MigrationExceptionHandler extends AbstractApiExceptionHandler {

    @Override
    protected HttpStatus statusOf(ErrorCode code) {
        return switch (code) {
            case MIG_TARGET_NOT_FOUND, MIG_BATCH_NOT_FOUND, MIG_PROFILE_NOT_FOUND,
                 NOT_FOUND -> HttpStatus.NOT_FOUND;
            case MIG_BATCH_STATUS_INVALID, MIG_BATCH_HAS_BLOCKING_ROWS, MIG_ROLLBACK_CONFLICT,
                 MIG_DEPENDENCY_ORDER_INVALID, MIG_PAYLOAD_UNREADABLE,
                 MIG_RECONCILIATION_FAILED, MIG_PROFILE_DUPLICATE, CONFLICT -> HttpStatus.CONFLICT;
            case MIG_TEMPLATE_INVALID, MIG_ROW_INVALID, MIG_PROFILE_INVALID,
                 MIG_REASON_REQUIRED, VALIDATION_FAILED -> HttpStatus.UNPROCESSABLE_ENTITY;
            case MIG_ACTOR_REQUIRED, FORBIDDEN -> HttpStatus.FORBIDDEN;
            case UNAUTHORIZED -> HttpStatus.UNAUTHORIZED;
            case TOO_MANY_REQUESTS -> HttpStatus.TOO_MANY_REQUESTS;
            case GATEWAY_TIMEOUT -> HttpStatus.GATEWAY_TIMEOUT;
            default -> HttpStatus.BAD_REQUEST;
        };
    }
}
