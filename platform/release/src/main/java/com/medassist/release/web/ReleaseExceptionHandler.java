package com.medassist.release.web;

import com.medassist.common.api.ErrorCode;
import com.medassist.common.web.AbstractApiExceptionHandler;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 发布错误码 → HTTP 状态。
 */
@RestControllerAdvice
public class ReleaseExceptionHandler extends AbstractApiExceptionHandler {

    @Override
    protected HttpStatus statusOf(ErrorCode code) {
        return switch (code) {
            case REL_COMPONENT_NOT_FOUND, REL_VERSION_NOT_FOUND, REL_TRAIN_NOT_FOUND,
                 REL_PATCH_NOT_FOUND, REL_DEPLOYMENT_NOT_FOUND, REL_WINDOW_NOT_FOUND,
                 REL_CI_SOURCE_NOT_FOUND, NOT_FOUND -> HttpStatus.NOT_FOUND;
            case REL_VERSION_DUPLICATE, REL_TRAIN_DUPLICATE, REL_TRAIN_STATUS_INVALID,
                 REL_VERSION_STATUS_INVALID, REL_PATCH_STATUS_INVALID, REL_PATCH_SELF_REVIEW,
                 REL_FREEZE_ACTIVE, REL_COMPATIBILITY_CONFLICT, REL_ROLLBACK_NO_TARGET,
                 CONFLICT -> HttpStatus.CONFLICT;
            case REL_TRAIN_ITEM_INVALID, REL_RELEASE_INVALID, REL_CHECKLIST_INCOMPLETE,
                 REL_DR_DRILL_REQUIRED, REL_PATCH_INVALID, REL_WINDOW_INVALID,
                 REL_CI_PAYLOAD_INVALID, VALIDATION_FAILED -> HttpStatus.UNPROCESSABLE_ENTITY;
            case REL_ACTOR_REQUIRED, FORBIDDEN -> HttpStatus.FORBIDDEN;
            case UNAUTHORIZED, REL_CI_TOKEN_INVALID -> HttpStatus.UNAUTHORIZED;
            case TOO_MANY_REQUESTS -> HttpStatus.TOO_MANY_REQUESTS;
            case GATEWAY_TIMEOUT -> HttpStatus.GATEWAY_TIMEOUT;
            default -> HttpStatus.BAD_REQUEST;
        };
    }
}
