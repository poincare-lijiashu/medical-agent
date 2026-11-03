package com.medassist.common.web;

import com.medassist.common.api.ApiResponse;
import com.medassist.common.api.ErrorCode;
import com.medassist.common.exception.BusinessException;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.util.stream.Collectors;

/**
 * 统一异常映射基类（各服务的 {@code @RestControllerAdvice} 继承它）。
 *
 * <p>存在的理由：auth 与 empi 各自维护一份几乎相同的处理器，已出现漂移——
 * empi 缺 429 分支、retry-after 固定 300s 与可配置窗口脱节（S17）；
 * 更严重的是客户端输入错误（{@code reveal=abc}、非法 UUID、未转义的时间戳）全部落进
 * 兜底 500，污染错误率告警且让集成方无法排障（M7）。
 *
 * <p>契约：
 * <ul>
 *   <li>参数格式错误 / 报文不可读 / 缺少必填参数 → <b>400</b> {@link ErrorCode#BAD_REQUEST}；</li>
 *   <li>Bean Validation 失败 → <b>422</b> {@link ErrorCode#VALIDATION_FAILED}；</li>
 *   <li>唯一约束/外键冲突（并发建档等）→ <b>409</b> {@link ErrorCode#CONFLICT}；</li>
 *   <li>其余未预期异常 → <b>500</b>，落全栈日志，响应体不泄露内部细节。</li>
 * </ul>
 *
 * <p>子类只需给出业务错误码 → HTTP 状态的映射（各域语义不同，如 PATIENT_DUPLICATE 用 409）。
 */
public abstract class AbstractApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(AbstractApiExceptionHandler.class);

    /** 业务错误码 → HTTP 状态（各域语义不同，必须由子类显式给出）。 */
    protected abstract HttpStatus statusOf(ErrorCode code);

    /** 429 响应的 Retry-After（秒）；子类可覆盖为限速窗口的真实值。 */
    protected long retryAfterSeconds() {
        return 300L;
    }

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiResponse<Void>> handleBusiness(BusinessException e) {
        HttpStatus status = statusOf(e.errorCode());
        ResponseEntity.BodyBuilder builder = ResponseEntity.status(status);
        if (status == HttpStatus.TOO_MANY_REQUESTS) {
            builder.header("Retry-After", String.valueOf(retryAfterSeconds()));
        }
        return builder.body(ApiResponse.fail(e.errorCode(), e.getMessage()));
    }

    /** Bean Validation：字段级提示直接回显（面向医护的中文文案）。 */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Void>> handleValidation(MethodArgumentNotValidException e) {
        String msg = e.getBindingResult().getFieldErrors().stream()
                .map(fe -> fe.getDefaultMessage() == null ? fe.getField() : fe.getDefaultMessage())
                .distinct()
                .collect(Collectors.joining("；"));
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
                .body(ApiResponse.fail(ErrorCode.VALIDATION_FAILED,
                        msg.isBlank() ? ErrorCode.VALIDATION_FAILED.defaultMessage() : msg));
    }

    /** 方法参数约束校验（@Validated + @RequestParam 约束）。 */
    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ApiResponse<Void>> handleConstraint(ConstraintViolationException e) {
        String msg = e.getConstraintViolations().stream()
                .map(ConstraintViolation::getMessage)
                .distinct()
                .collect(Collectors.joining("；"));
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
                .body(ApiResponse.fail(ErrorCode.VALIDATION_FAILED,
                        msg.isBlank() ? ErrorCode.VALIDATION_FAILED.defaultMessage() : msg));
    }

    /** 路径变量/查询参数类型不匹配（如 reveal=abc、UUID 非法、时间戳格式错误）→ 400。 */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiResponse<Void>> handleTypeMismatch(MethodArgumentTypeMismatchException e) {
        String name = e.getName() == null ? "参数" : e.getName();
        // 不回显原始值：可能含恶意内容
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiResponse.fail(ErrorCode.BAD_REQUEST, "参数格式不正确：" + name));
    }

    /** 请求体缺失/JSON 结构非法 → 400。 */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiResponse<Void>> handleUnreadable(HttpMessageNotReadableException e) {
        log.debug("request.unreadable reason={}", e.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiResponse.fail(ErrorCode.BAD_REQUEST, "请求体格式不正确或缺失"));
    }

    /** 缺少必填查询参数 → 400。 */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ApiResponse<Void>> handleMissingParam(MissingServletRequestParameterException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiResponse.fail(ErrorCode.BAD_REQUEST, "缺少必填参数：" + e.getParameterName()));
    }

    /**
     * 数据库约束冲突分流。
     *
     * <p>只有"唯一 / 外键 / 排他"冲突才是可重试的并发结果（409）；
     * CHECK / NOT NULL 违反意味着业务逻辑写出了账实不符的数据，属于服务端缺陷，
     * 必须 500 + 告警，否则集成方会把它当成可重试冲突无限重试，并掩盖真实问题。
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ApiResponse<Void>> handleIntegrity(DataIntegrityViolationException e) {
        String raw = e.getMostSpecificCause() == null ? "" : e.getMostSpecificCause().getMessage();
        String sqlState = sqlStateOf(e);
        boolean retryable = isRetryableConflict(sqlState, raw);
        if (retryable) {
            log.warn("data.integrity.conflict sqlState={} reason={}", sqlState, raw);
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(ApiResponse.fail(ErrorCode.CONFLICT));
        }
        log.error("data.integrity.invariant_violated sqlState={} reason={}", sqlState, raw, e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiResponse.fail(ErrorCode.INTERNAL_ERROR));
    }

    /** PostgreSQL SQLState：23505 唯一、23503 外键、23P01 排他。 */
    private static boolean isRetryableConflict(String sqlState, String message) {
        if (sqlState != null) {
            return switch (sqlState) {
                case "23505", "23503", "23P01" -> true;
                // 23514 CHECK / 23502 NOT NULL / 23514 等属不变量被破坏，不作 409
                default -> false;
            };
        }
        // 拿不到 SQLState 时退回消息特征（兼容非 PG 或包装异常）
        String lower = message == null ? "" : message.toLowerCase();
        return lower.contains("duplicate key")
                || lower.contains("unique constraint")
                || lower.contains("foreign key");
    }

    private static String sqlStateOf(DataIntegrityViolationException e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof java.sql.SQLException sql && sql.getSQLState() != null) {
                return sql.getSQLState();
            }
        }
        return null;
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleUnexpected(Exception e) {
        log.error("unhandled.exception", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiResponse.fail(ErrorCode.INTERNAL_ERROR));
    }
}
