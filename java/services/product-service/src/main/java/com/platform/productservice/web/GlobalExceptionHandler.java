package com.platform.productservice.web;

import com.platform.productservice.common.DomainException;
import com.platform.productservice.common.ErrorCode;
import com.platform.productservice.infrastructure.event.EventContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.net.URI;
import java.util.List;

/**
 * Mọi lỗi trả về {@code application/problem+json} theo RFC 9457 và schema
 * shared/json-schema/v1/problem.schema.json.
 *
 * <p>Không trả exception message của tầng dưới, SQL, hostname hay secret cho
 * client (contracts/conventions/http.md).
 */
@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

    private static final String PROBLEM_TYPE_BASE = "https://contracts.ecommerce.local/problems/";
    private static final String RETRY_AFTER_SECONDS = "1";

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
        log.debug("Rejected request {} {}: {}", request.getMethod(), request.getRequestURI(),
                exception.getMessage());
        return respond(ErrorCode.INVALID_REQUEST, problem(ErrorCode.INVALID_REQUEST, detail, request));
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ProblemDetail> handleIntegrity(DataIntegrityViolationException exception,
                                                         HttpServletRequest request) {
        log.warn("Constraint violation on {} {}", request.getMethod(), request.getRequestURI(), exception);
        return respond(ErrorCode.CATALOG_CONSTRAINT_VIOLATION,
                problem(ErrorCode.CATALOG_CONSTRAINT_VIOLATION,
                        "The change conflicts with a catalog constraint; reload and retry", request));
    }

    /**
     * Lock timeout và lỗi tạm thời của PostgreSQL là retryable: client thử lại
     * sau, không phải lỗi nghiệp vụ (docs/product/operations.md).
     */
    @ExceptionHandler({PessimisticLockingFailureException.class,
            QueryTimeoutException.class,
            TransientDataAccessException.class})
    public ResponseEntity<ProblemDetail> handleTransient(Exception exception, HttpServletRequest request) {
        log.warn("Transient storage failure on {} {}", request.getMethod(), request.getRequestURI(), exception);
        ProblemDetail problem = problem(ErrorCode.CATALOG_UNAVAILABLE,
                "Catalog storage is temporarily unavailable", request);
        return respond(ErrorCode.CATALOG_UNAVAILABLE, problem);
    }

    @ExceptionHandler({org.springframework.web.servlet.resource.NoResourceFoundException.class,
            org.springframework.web.HttpRequestMethodNotSupportedException.class})
    public ResponseEntity<ProblemDetail> handleRouting(org.springframework.web.ErrorResponse exception,
                                                      HttpServletRequest request) {
        ProblemDetail body = ProblemDetail.forStatus(exception.getStatusCode());
        body.setTitle(exception.getStatusCode().value() == 404 ? "Not Found" : "Method Not Allowed");
        body.setDetail("No handler supports this request");
        body.setInstance(URI.create(request.getRequestURI()));
        body.setProperty("code", exception.getStatusCode().value() == 404 ? "NOT_FOUND" : "METHOD_NOT_ALLOWED");
        body.setProperty("retryable", false);
        traceId().ifPresent(id -> body.setProperty("trace_id", id));
        return ResponseEntity.status(exception.getStatusCode()).headers(exception.getHeaders()).body(body);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ProblemDetail> handleUnexpected(Exception exception, HttpServletRequest request) {
        log.error("Unhandled failure on {} {}", request.getMethod(), request.getRequestURI(), exception);
        return respond(ErrorCode.INTERNAL_ERROR,
                problem(ErrorCode.INTERNAL_ERROR, "Unexpected server error", request));
    }

    /**
     * Mọi lỗi retryable kèm {@code Retry-After}, đúng response {@code RetryableUnavailable}
     * của OpenAPI — kể cả {@code CATALOG_UNAVAILABLE} ném từ domain khi không chụp được
     * replay offsets của export, không chỉ lỗi tạm thời của PostgreSQL.
     */
    private ResponseEntity<ProblemDetail> respond(ErrorCode code, ProblemDetail problem) {
        ResponseEntity.BodyBuilder response = ResponseEntity.status(code.status());
        if (code.retryable()) {
            response.header(HttpHeaders.RETRY_AFTER, RETRY_AFTER_SECONDS);
        }
        return response.body(problem);
    }

    private ProblemDetail problem(ErrorCode code, String detail, HttpServletRequest request) {
        ProblemDetail problem = ProblemDetail.forStatus(code.status());
        problem.setType(URI.create(PROBLEM_TYPE_BASE + code.name().toLowerCase().replace('_', '-')));
        problem.setTitle(code.title());
        problem.setDetail(detail == null ? code.title() : detail);
        problem.setInstance(URI.create(request.getRequestURI()));
        problem.setProperty("code", code.name());
        problem.setProperty("retryable", code.retryable());
        traceId().ifPresent(traceId -> problem.setProperty("trace_id", traceId));
        return problem;
    }

    /** trace-id của W3C traceparent nếu request mang trace context hợp lệ. */
    private java.util.Optional<String> traceId() {
        String traceparent = EventContext.traceparent();
        if (traceparent == null) {
            return java.util.Optional.empty();
        }
        String[] parts = traceparent.split("-");
        return parts.length >= 2 && parts[1].length() == 32
                ? java.util.Optional.of(parts[1])
                : java.util.Optional.empty();
    }

    private record ProblemFieldError(String field, String message) {
    }
}
