package com.ecommerce.review.controller;

import com.ecommerce.review.common.ErrorCode;
import com.ecommerce.review.common.ReviewException;
import com.ecommerce.review.config.ReviewProperties;
import com.ecommerce.review.storage.StorageUnavailableException;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Maps every failure to RFC 9457 {@code application/problem+json} with the project
 * extensions {@code code}, {@code trace_id}, {@code retryable} and {@code errors[]}.
 *
 * <p>Nothing sensitive reaches a body here: no review content, display name, reporter
 * identity, legal takedown reason, presigned URL, object key or notification recipient
 * ({@code docs/review/operations.md 4}).
 */
@RestControllerAdvice
public class ProblemExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ProblemExceptionHandler.class);

    private final ReviewProperties properties;

    public ProblemExceptionHandler(ReviewProperties properties) {
        this.properties = properties;
    }

    @ExceptionHandler(ReviewException.class)
    public ResponseEntity<Map<String, Object>> onReviewException(ReviewException e, HttpServletRequest request) {
        if (e.code().status().is5xxServerError()) {
            log.error("request {} failed with {}", request.getRequestURI(), e.code(), e);
        }
        return problem(e.code(), e.getMessage(), e.errors(), request);
    }

    @ExceptionHandler({MethodArgumentTypeMismatchException.class,
            MissingServletRequestParameterException.class,
            MethodArgumentNotValidException.class,
            HandlerMethodValidationException.class,
            HttpMessageNotReadableException.class})
    public ResponseEntity<Map<String, Object>> onBadRequest(Exception e, HttpServletRequest request) {
        return problem(ErrorCode.INVALID_REQUEST, e.getMessage(), List.of(), request);
    }

    /**
     * A media signer or metadata read that is down is retryable and never an attachment:
     * Review does not attach an object it could not verify.
     */
    @ExceptionHandler(StorageUnavailableException.class)
    public ResponseEntity<Map<String, Object>> onStorageFailure(StorageUnavailableException e,
                                                                HttpServletRequest request) {
        log.error("media storage call for {} failed: {}", request.getRequestURI(), e.getMessage());
        return problem(ErrorCode.MEDIA_STORAGE_UNAVAILABLE,
                "review media storage is not available right now", List.of(), request);
    }

    /**
     * A PostgreSQL failure answers 503, never a synthetic empty page or a zero aggregate:
     * the BFF has to be able to tell "no reviews" from "cannot read reviews"
     * (WF-REVIEW-005).
     */
    @ExceptionHandler({DataAccessResourceFailureException.class, QueryTimeoutException.class})
    public ResponseEntity<Map<String, Object>> onStoreFailure(Exception e, HttpServletRequest request) {
        log.error("review store call for {} failed: {}", request.getRequestURI(), e.getMessage());
        return problem(ErrorCode.REVIEW_STORE_UNAVAILABLE,
                "the review store is not available right now", List.of(), request);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> onUnexpected(Exception e, HttpServletRequest request) {
        log.error("unexpected failure on {}", request.getRequestURI(), e);
        return problem(ErrorCode.INTERNAL_ERROR, "unexpected internal error", List.of(), request);
    }

    private ResponseEntity<Map<String, Object>> problem(ErrorCode code,
                                                        String detail,
                                                        List<ReviewException.FieldError> errors,
                                                        HttpServletRequest request) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("type", code.typeUri());
        body.put("title", code.title());
        body.put("status", code.status().value());
        body.put("detail", detail == null || detail.isBlank() ? code.title() : detail);
        body.put("instance", request.getRequestURI());
        body.put("code", code.name());
        body.put("retryable", code.retryable());

        String traceId = request.getHeader(properties.gateway().requestIdHeader());
        if (traceId != null && !traceId.isBlank()) {
            body.put("trace_id", traceId);
        }
        if (!errors.isEmpty()) {
            body.put("errors", errors.stream()
                    .map(error -> Map.of("field", error.field(), "message", error.message()))
                    .toList());
        }

        HttpStatus status = code.status();
        ResponseEntity.BodyBuilder builder = ResponseEntity.status(status)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON);
        if (code.retryable()) {
            builder.header("Retry-After", "5");
        }
        return builder.body(body);
    }
}
