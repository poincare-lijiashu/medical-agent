package com.medassist.license.web;

import com.medassist.common.api.ErrorCode;
import com.medassist.common.web.AbstractApiExceptionHandler;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 授权错误码 → HTTP 状态。
 *
 * <p>语义约定：
 * <ul>
 *   <li>授权书不存在 → 404；</li>
 *   <li>编号重复 / 状态不允许（已吊销、已被替代）→ 409；</li>
 *   <li>签名不通过、实例不匹配、内容不合法、用量非法 → 422（提交内容不可用，需换文件或改配置）；</li>
 *   <li>签发能力关闭、缺操作人 → 403；</li>
 *   <li>验签密钥不可用 → 503（部署配置问题，不是用户输入错误）。</li>
 * </ul>
 */
@RestControllerAdvice
public class LicenseExceptionHandler extends AbstractApiExceptionHandler {

    @Override
    protected HttpStatus statusOf(ErrorCode code) {
        return switch (code) {
            case LICENSE_NOT_FOUND, NOT_FOUND -> HttpStatus.NOT_FOUND;
            case LICENSE_DUPLICATE, LICENSE_STATUS_INVALID, CONFLICT -> HttpStatus.CONFLICT;
            case LICENSE_SIGNATURE_INVALID, LICENSE_INSTANCE_MISMATCH, LICENSE_PAYLOAD_INVALID,
                 LICENSE_USAGE_INVALID, VALIDATION_FAILED -> HttpStatus.UNPROCESSABLE_ENTITY;
            case LICENSE_ISSUANCE_DISABLED, LICENSE_ACTOR_REQUIRED, FORBIDDEN -> HttpStatus.FORBIDDEN;
            case LICENSE_KEY_UNAVAILABLE, UPSTREAM_UNAVAILABLE -> HttpStatus.SERVICE_UNAVAILABLE;
            case UNAUTHORIZED -> HttpStatus.UNAUTHORIZED;
            case TOO_MANY_REQUESTS -> HttpStatus.TOO_MANY_REQUESTS;
            default -> HttpStatus.BAD_REQUEST;
        };
    }
}
