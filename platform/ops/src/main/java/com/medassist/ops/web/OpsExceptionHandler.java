package com.medassist.ops.web;

import com.medassist.common.api.ErrorCode;
import com.medassist.common.web.AbstractApiExceptionHandler;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 运营错误码 → HTTP 状态。
 *
 * <p>语义约定：
 * <ul>
 *   <li>服务/工单/SLA/日历不存在 → 404；</li>
 *   <li>状态机不允许、版本冲突、挂起中 → 409（需要人刷新或先恢复）；</li>
 *   <li>时限/日历/原因/结论不合法 → 422；</li>
 *   <li>缺少真实操作人 → 403；</li>
 *   <li>扫描失败 → 503（依赖故障，不是用户输入错误）。</li>
 * </ul>
 */
@RestControllerAdvice
public class OpsExceptionHandler extends AbstractApiExceptionHandler {

    @Override
    protected HttpStatus statusOf(ErrorCode code) {
        return switch (code) {
            case OPS_SERVICE_NOT_FOUND, OPS_TICKET_NOT_FOUND, OPS_SLA_POLICY_NOT_FOUND,
                 OPS_CALENDAR_NOT_FOUND, OPS_ROTATION_NOT_FOUND, OPS_SHIFT_NOT_FOUND,
                 OPS_INCIDENT_NOT_FOUND, OPS_POSTMORTEM_NOT_FOUND,
                 OPS_INTEGRATION_SOURCE_NOT_FOUND, NOT_FOUND -> HttpStatus.NOT_FOUND;
            case OPS_TICKET_STATUS_INVALID, OPS_TICKET_TRANSITION_INVALID,
                 OPS_TICKET_VERSION_CONFLICT, OPS_TICKET_PAUSED, OPS_INCIDENT_STATUS_INVALID,
                 OPS_INCIDENT_TRANSITION_INVALID, OPS_POSTMORTEM_SELF_REVIEW,
                 OPS_POSTMORTEM_STATUS_INVALID, CONFLICT -> HttpStatus.CONFLICT;
            case OPS_TICKET_ASSIGNEE_REQUIRED, OPS_TICKET_RESOLUTION_REQUIRED,
                 OPS_SLA_INVALID, OPS_CALENDAR_INVALID, OPS_REASON_REQUIRED,
                 OPS_ROTATION_INVALID, OPS_INCIDENT_INVALID, OPS_POSTMORTEM_INCOMPLETE,
                 OPS_INTEGRATION_PAYLOAD_INVALID, VALIDATION_FAILED -> HttpStatus.UNPROCESSABLE_ENTITY;
            case OPS_ACTOR_REQUIRED, FORBIDDEN -> HttpStatus.FORBIDDEN;
            case UNAUTHORIZED, OPS_INTEGRATION_TOKEN_INVALID -> HttpStatus.UNAUTHORIZED;
            case OPS_SLA_SCAN_FAILED, UPSTREAM_UNAVAILABLE -> HttpStatus.SERVICE_UNAVAILABLE;
            case TOO_MANY_REQUESTS -> HttpStatus.TOO_MANY_REQUESTS;
            case GATEWAY_TIMEOUT -> HttpStatus.GATEWAY_TIMEOUT;
            default -> HttpStatus.BAD_REQUEST;
        };
    }
}
