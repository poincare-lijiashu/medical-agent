package com.medassist.kb.web;

import com.medassist.common.api.ErrorCode;
import com.medassist.common.web.AbstractApiExceptionHandler;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 知识库异常映射。
 *
 * <p>语义要点：空间/文档不存在 → 404；编码或内容重复、状态机冲突 → 409；
 * 非成员 → 403（不是 404：成员可以知道空间存在，但无权访问）；向量模型未配置 → 503。
 */
@RestControllerAdvice
public class GlobalExceptionHandler extends AbstractApiExceptionHandler {

    @Override
    protected HttpStatus statusOf(ErrorCode code) {
        return switch (code) {
            case NOT_FOUND, KB_SPACE_NOT_FOUND, KB_DOCUMENT_NOT_FOUND -> HttpStatus.NOT_FOUND;
            case UNAUTHORIZED -> HttpStatus.UNAUTHORIZED;
            case FORBIDDEN, KB_SPACE_ACCESS_DENIED -> HttpStatus.FORBIDDEN;
            case CONFLICT, KB_SPACE_DUPLICATE, KB_DOCUMENT_DUPLICATE,
                 KB_SPACE_STATUS_INVALID, KB_DOCUMENT_STATUS_INVALID -> HttpStatus.CONFLICT;
            case VALIDATION_FAILED, KB_QUERY_INVALID, KB_PARSE_UNSUPPORTED -> HttpStatus.UNPROCESSABLE_ENTITY;
            case TOO_MANY_REQUESTS -> HttpStatus.TOO_MANY_REQUESTS;
            case UPSTREAM_UNAVAILABLE, KB_EMBEDDING_UNAVAILABLE -> HttpStatus.SERVICE_UNAVAILABLE;
            default -> HttpStatus.BAD_REQUEST;
        };
    }
}
