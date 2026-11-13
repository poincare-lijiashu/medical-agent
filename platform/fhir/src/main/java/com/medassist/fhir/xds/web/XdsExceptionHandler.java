package com.medassist.fhir.xds.web;

import com.medassist.common.api.ApiResponse;
import com.medassist.common.api.ErrorCode;
import com.medassist.common.exception.BusinessException;
import com.medassist.common.security.AuthHeaders;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/**
 * XDS 错误壳：统一返回平台 {@link ApiResponse}，而不是 FHIR 的 OperationOutcome。
 *
 * <p>为什么限定包范围：FHIR 控制器需要标准 OperationOutcome，XDS 的控制台页面
 * 走平台统一信封；两个 Advice 同时生效时，由本类的高优先级 + 包范围保证只接管
 * {@code com.medassist.fhir.xds.web} 下的请求。
 */
@RestControllerAdvice(basePackageClasses = XdsController.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
public class XdsExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(XdsExceptionHandler.class);

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiResponse<Void>> business(BusinessException e,
                                                      HttpServletRequest request) {
        ErrorCode code = e.errorCode();
        return ResponseEntity.status(statusOf(code))
                .body(ApiResponse.fail(code, e.getMessage(), traceId(request)));
    }

    @ExceptionHandler({MethodArgumentTypeMismatchException.class,
            MissingServletRequestParameterException.class,
            HttpMessageNotReadableException.class})
    public ResponseEntity<ApiResponse<Void>> badRequest(Exception e,
                                                        HttpServletRequest request) {
        return ResponseEntity.badRequest()
                .body(ApiResponse.fail(ErrorCode.XDS_METADATA_INVALID,
                        "请求参数格式不正确，请检查后重试", traceId(request)));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> unexpected(Exception e, HttpServletRequest request) {
        log.error("xds.unhandled path={} error={}", request.getRequestURI(), e.toString(), e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiResponse.fail(ErrorCode.INTERNAL_ERROR,
                        ErrorCode.INTERNAL_ERROR.defaultMessage(), traceId(request)));
    }

    private static HttpStatus statusOf(ErrorCode code) {
        return switch (code) {
            case XDS_DOCUMENT_NOT_FOUND, XDS_SOURCE_NOT_FOUND, PATIENT_NOT_FOUND -> HttpStatus.NOT_FOUND;
            case XDS_DOCUMENT_DUPLICATE, CONFLICT -> HttpStatus.CONFLICT;
            case XDS_HASH_MISMATCH -> HttpStatus.INTERNAL_SERVER_ERROR;
            case XDS_METADATA_INVALID, XDS_CONTENT_INVALID, XDS_SIZE_MISMATCH,
                 XDS_QUERY_TOO_BROAD, XDS_SOURCE_INVALID, XDS_STATUS_INVALID ->
                    HttpStatus.UNPROCESSABLE_ENTITY;
            case FORBIDDEN, XDS_ACTOR_REQUIRED -> HttpStatus.FORBIDDEN;
            case UNAUTHORIZED -> HttpStatus.UNAUTHORIZED;
            case NOT_FOUND -> HttpStatus.NOT_FOUND;
            case VALIDATION_FAILED, BAD_REQUEST -> HttpStatus.BAD_REQUEST;
            default -> HttpStatus.INTERNAL_SERVER_ERROR;
        };
    }

    private static String traceId(HttpServletRequest request) {
        return request.getHeader(AuthHeaders.TRACE_ID);
    }
}
