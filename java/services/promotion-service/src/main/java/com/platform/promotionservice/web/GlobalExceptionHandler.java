package com.platform.promotionservice.web;

import com.platform.promotionservice.common.CallerContext;
import com.platform.promotionservice.common.DomainException;
import com.platform.promotionservice.common.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.RedisSystemException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.web.ErrorResponse;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.net.URI;
import java.util.List;
import java.util.Optional;

/**
 * Mọi lỗi trả {@code application/problem+json} theo RFC 9457 và
 * shared/json-schema/v1/problem.schema.json. Không lộ SQL/hostname/secret.
 */
@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

    private static final String PROBLEM_TYPE_BASE = "https://contracts.ecommerce.local/problems/";

    @ExceptionHandler(DomainException.class)
    public ResponseEntity<ProblemDetail> handleDomain(DomainException exception, HttpServletRequest request) {
        ProblemDetail problem = problem(exception.errorCode(), exception.getMessage(), request);
        if (!exception.fieldErrors().isEmpty()) {
            problem.setProperty("errors", exception.fieldErrors().stream()
                    .map(error -> new ProblemFieldError(error.field(), error.message()))
                    .toList());
        }
        return respond(exception.errorCode(), problem);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ProblemDetail> handleValidation(MethodArgumentNotValidException exception,
                                                          HttpServletRequest request) {
        List<ProblemFieldError> errors = exception.getBindingResult().getFieldErrors().stream()
                .map(error -> new ProblemFieldError(error.getField(), error.getDefaultMessage()))
                .toList();
        ProblemDetail problem = problem(ErrorCode.INVALID_REQUEST, "Request body failed validation", request);
        problem.setProperty("errors", errors);
        return respond(ErrorCode.INVALID_REQUEST, problem);
    }

    @ExceptionHandler({HttpMessageNotReadableException.class,
            MissingRequestHeaderException.class,
            MethodArgumentTypeMismatchException.class,
            ConstraintViolationException.class})
    public ResponseEntity<ProblemDetail> handleBadRequest(Exception exception, HttpServletRequest request) {
        String detail = exception instanceof MissingRequestHeaderException missing
                ? "Header " + missing.getHeaderName() + " is required"
                : "Request is not valid for this endpoint";
        log.debug("Rejected request {} {}: {}", request.getMethod(), request.getRequestURI(), exception.getMessage());
        return respond(ErrorCode.INVALID_REQUEST, problem(ErrorCode.INVALID_REQUEST, detail, request));
    }

    /**
     * Route lạ hoặc sai method: giữ status 404/405 (kèm {@code Allow}) nhưng nói problem+json với code
     * INVALID_REQUEST; catalog không có mã riêng cho chúng (cùng quy ước Checkout). Không log stack trace:
     * đây là lỗi của client, không phải {@code INTERNAL_ERROR}.
     */
    @ExceptionHandler({NoResourceFoundException.class, NoHandlerFoundException.class,
            HttpRequestMethodNotSupportedException.class})
    public ResponseEntity<ProblemDetail> handleUnrouted(Exception exception, HttpServletRequest request) {
        ErrorResponse routing = (ErrorResponse) exception;
        log.debug("Unrouted request {} {}: {}", request.getMethod(), request.getRequestURI(), exception.getMessage());
        ProblemDetail problem = problem(ErrorCode.INVALID_REQUEST, routing.getStatusCode(),
                "No operation matches this request", request);
        ResponseEntity.BodyBuilder builder = ResponseEntity.status(routing.getStatusCode());
        routing.getHeaders().forEach((name, values) -> builder.header(name, values.toArray(String[]::new)));
        return builder.body(problem);
    }

    /** Content-Type không được operation chấp nhận (vd PATCH không phải merge-patch+json): 400, không 415. */
    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ProblemDetail> handleUnsupportedMediaType(HttpMediaTypeNotSupportedException exception,
                                                                    HttpServletRequest request) {
        log.debug("Unsupported media type on {} {}: {}", request.getMethod(), request.getRequestURI(),
                exception.getMessage());
        return respond(ErrorCode.INVALID_REQUEST, problem(ErrorCode.INVALID_REQUEST,
                "Content-Type is not supported by this operation", request));
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ProblemDetail> handleIntegrity(DataIntegrityViolationException exception,
                                                         HttpServletRequest request) {
        log.warn("Constraint violation on {} {}", request.getMethod(), request.getRequestURI(), exception);
        return respond(ErrorCode.PROMOTION_CONSTRAINT_VIOLATION,
                problem(ErrorCode.PROMOTION_CONSTRAINT_VIOLATION,
                        "The change conflicts with a promotion constraint; reload and retry", request));
    }

    /** Lock timeout / lỗi tạm thời PostgreSQL / Redis write path → 503 retryable. */
    @ExceptionHandler({PessimisticLockingFailureException.class,
            QueryTimeoutException.class,
            TransientDataAccessException.class,
            CannotCreateTransactionException.class,
            RedisConnectionFailureException.class,
            RedisSystemException.class})
    public ResponseEntity<ProblemDetail> handleTransient(Exception exception, HttpServletRequest request) {
        log.warn("Dependency failure on {} {}", request.getMethod(), request.getRequestURI(), exception);
        ProblemDetail problem = problem(ErrorCode.PROMOTION_UNAVAILABLE,
                "Promotion dependency is temporarily unavailable", request);
        return ResponseEntity.status(ErrorCode.PROMOTION_UNAVAILABLE.status())
                .header(HttpHeaders.RETRY_AFTER, "1")
                .body(problem);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ProblemDetail> handleUnexpected(Exception exception, HttpServletRequest request) {
        log.error("Unhandled failure on {} {}", request.getMethod(), request.getRequestURI(), exception);
        return respond(ErrorCode.INTERNAL_ERROR, problem(ErrorCode.INTERNAL_ERROR, "Unexpected server error", request));
    }

    private ResponseEntity<ProblemDetail> respond(ErrorCode code, ProblemDetail problem) {
        ResponseEntity.BodyBuilder builder = ResponseEntity.status(code.status());
        if (code.retryable()) {
            builder.header(HttpHeaders.RETRY_AFTER, "1");
        }
        return builder.body(problem);
    }

    private ProblemDetail problem(ErrorCode code, String detail, HttpServletRequest request) {
        return problem(code, code.status(), detail, request);
    }

    private ProblemDetail problem(ErrorCode code, HttpStatusCode status, String detail, HttpServletRequest request) {
        ProblemDetail problem = ProblemDetail.forStatus(status);
        problem.setType(URI.create(PROBLEM_TYPE_BASE + code.name().toLowerCase().replace('_', '-')));
        problem.setTitle(code.title());
        problem.setDetail(detail == null ? code.title() : detail);
        problem.setInstance(URI.create(request.getRequestURI()));
        problem.setProperty("code", code.name());
        problem.setProperty("retryable", code.retryable());
        traceId().ifPresent(traceId -> problem.setProperty("trace_id", traceId));
        return problem;
    }

    private Optional<String> traceId() {
        String traceparent = CallerContext.currentTraceparent();
        if (traceparent == null) {
            return Optional.empty();
        }
        String[] parts = traceparent.split("-");
        return parts.length >= 2 && parts[1].length() == 32 ? Optional.of(parts[1]) : Optional.empty();
    }

    private record ProblemFieldError(String field, String message) {
    }
}
