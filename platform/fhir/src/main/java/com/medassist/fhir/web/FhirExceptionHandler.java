package com.medassist.fhir.web;

import com.medassist.common.api.ErrorCode;
import com.medassist.common.exception.BusinessException;
import jakarta.servlet.http.HttpServletRequest;
import org.hl7.fhir.r4.model.OperationOutcome;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/**
 * FHIR 错误壳：所有失败都返回 {@link OperationOutcome}，而不是平台通用的
 * {@code ApiResponse}。
 *
 * <p>原因：FHIR 客户端（含 Connectathon 测试工具）按标准解析错误；
 * 返回私有 JSON 壳会被当成"响应格式非法"，反而掩盖真正的错误信息。
 *
 * <p>边界：技术异常只回通用文案 + 日志留痕，绝不明文透出堆栈或 SQL。
 */
@RestControllerAdvice
public class FhirExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(FhirExceptionHandler.class);

    private final FhirFormats formats;

    public FhirExceptionHandler(FhirFormats formats) {
        this.formats = formats;
    }

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<String> business(BusinessException e, HttpServletRequest request) {
        return outcome(request, statusOf(e.errorCode()), issueType(e.errorCode()), e.getMessage());
    }

    @ExceptionHandler({MethodArgumentTypeMismatchException.class,
            MissingServletRequestParameterException.class,
            HttpMessageNotReadableException.class})
    public ResponseEntity<String> badRequest(Exception e, HttpServletRequest request) {
        return outcome(request, HttpStatus.BAD_REQUEST, OperationOutcome.IssueType.INVALID,
                "请求参数格式不正确，请检查后重试");
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<String> methodNotSupported(HttpRequestMethodNotSupportedException e,
                                                     HttpServletRequest request) {
        return outcome(request, HttpStatus.METHOD_NOT_ALLOWED,
                OperationOutcome.IssueType.NOTSUPPORTED,
                "本服务仅提供 FHIR 只读接口（GET）；写入请调用对应业务域接口");
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<String> unexpected(Exception e, HttpServletRequest request) {
        // 只记类名与消息；堆栈进日志，不进响应体
        log.error("fhir.unhandled path={} error={}", request.getRequestURI(), e.toString(), e);
        return outcome(request, HttpStatus.INTERNAL_SERVER_ERROR,
                OperationOutcome.IssueType.EXCEPTION,
                "系统处理异常，已记录日志，请联系管理员");
    }

    private ResponseEntity<String> outcome(HttpServletRequest request, HttpStatus status,
                                           OperationOutcome.IssueType issueType, String message) {
        OperationOutcome outcome = new OperationOutcome();
        outcome.addIssue()
                .setSeverity(OperationOutcome.IssueSeverity.ERROR)
                .setCode(issueType)
                .setDiagnostics(message == null || message.isBlank()
                        ? status.getReasonPhrase() : message);
        return formats.response(outcome, safeFormat(request), status);
    }

    private FhirFormats.Format safeFormat(HttpServletRequest request) {
        try {
            return formats.negotiate(request.getParameter("_format"),
                    request.getHeader("Accept"));
        } catch (BusinessException e) {
            // 连 _format 都非法时，用 JSON 保证错误本身可读
            return FhirFormats.Format.JSON;
        }
    }

    private static HttpStatus statusOf(ErrorCode code) {
        return switch (code) {
            case FORBIDDEN -> HttpStatus.FORBIDDEN;
            case UNAUTHORIZED -> HttpStatus.UNAUTHORIZED;
            case NOT_FOUND, PATIENT_NOT_FOUND, VISIT_NOT_FOUND -> HttpStatus.NOT_FOUND;
            case CONFLICT -> HttpStatus.CONFLICT;
            case TOO_MANY_REQUESTS -> HttpStatus.TOO_MANY_REQUESTS;
            case UPSTREAM_UNAVAILABLE -> HttpStatus.SERVICE_UNAVAILABLE;
            case INTERNAL_ERROR -> HttpStatus.INTERNAL_SERVER_ERROR;
            // 含 VALIDATION_FAILED：FHIR 惯例对非法检索参数返回 400
            default -> HttpStatus.BAD_REQUEST;
        };
    }

    private static OperationOutcome.IssueType issueType(ErrorCode code) {
        return switch (code) {
            case FORBIDDEN -> OperationOutcome.IssueType.FORBIDDEN;
            case UNAUTHORIZED -> OperationOutcome.IssueType.LOGIN;
            case NOT_FOUND, PATIENT_NOT_FOUND, VISIT_NOT_FOUND -> OperationOutcome.IssueType.NOTFOUND;
            case CONFLICT -> OperationOutcome.IssueType.CONFLICT;
            case TOO_MANY_REQUESTS -> OperationOutcome.IssueType.THROTTLED;
            case UPSTREAM_UNAVAILABLE -> OperationOutcome.IssueType.TRANSIENT;
            case INTERNAL_ERROR -> OperationOutcome.IssueType.EXCEPTION;
            default -> OperationOutcome.IssueType.INVALID;
        };
    }
}
