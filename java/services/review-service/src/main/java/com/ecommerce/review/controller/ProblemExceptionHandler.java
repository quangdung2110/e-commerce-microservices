package com.ecommerce.review.controller;

import com.ecommerce.review.common.ErrorCode;
import com.ecommerce.review.common.ReviewException;
import com.ecommerce.review.config.ReviewProperties;
import com.ecommerce.review.storage.StorageUnavailableException;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Path;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Maps every failure to RFC 9457 {@code application/problem+json} with the project
 * extensions {@code code}, {@code trace_id}, {@code retryable} and {@code errors[]}.
 *
 * <p>Nothing sensitive reaches a body here: no review content, display name, reporter
 * identity, legal takedown reason, presigned URL, object key or notification recipient
 * ({@code docs/review/operations.md 4}). Framework exception text is not forwarded
 * either — it names Java classes and method signatures
 * ({@code contracts/conventions/http.md}) — so every client-facing {@code detail} and
 * {@code errors[].message} below is written here, and the offending field travels as a
 * wire (snake_case) name.
 *
 * <p>A request the service cannot route, or whose method or media type it does not
 * accept, is answered as the 404, 405 or 415 it is. Only a failure that is genuinely the
 * service's own reaches {@link #onUnexpected}.
 */
@RestControllerAdvice
public class ProblemExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ProblemExceptionHandler.class);

    /** The Problem schema bounds {@code trace_id} to 16..64 characters. */
    private static final int TRACE_ID_MIN = 16;
    private static final int TRACE_ID_MAX = 64;
    private static final int MAX_FIELD_ERRORS = 100;

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

    /**
     * Bean Validation on a {@code @RequestParam} or {@code @PathVariable}, raised because
     * the controllers carry {@code @Validated}. Without this handler a request such as
     * {@code ?limit=0} fell through to {@link #onUnexpected} and answered 500.
     */
    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<Map<String, Object>> onConstraintViolation(ConstraintViolationException e,
                                                                     HttpServletRequest request) {
        List<ReviewException.FieldError> errors = e.getConstraintViolations().stream()
                .sorted(Comparator.comparing((ConstraintViolation<?> v) -> v.getPropertyPath().toString()))
                .map(violation -> new ReviewException.FieldError(
                        wireName(leafName(violation.getPropertyPath())), violation.getMessage()))
                .toList();
        return invalidRequest("A request parameter is outside its allowed range", errors, request);
    }

    /** A bound {@code @Valid} request body. */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> onBodyInvalid(MethodArgumentNotValidException e,
                                                             HttpServletRequest request) {
        List<ReviewException.FieldError> errors = e.getBindingResult().getAllErrors().stream()
                .map(error -> new ReviewException.FieldError(
                        error instanceof FieldError field ? wireName(field.getField()) : "body",
                        error.getDefaultMessage() == null ? "is invalid" : error.getDefaultMessage()))
                .toList();
        return invalidRequest("The request body failed validation", errors, request);
    }

    /** Method validation that Spring performs itself when a controller is not {@code @Validated}. */
    @ExceptionHandler(HandlerMethodValidationException.class)
    public ResponseEntity<Map<String, Object>> onMethodValidation(HandlerMethodValidationException e,
                                                                  HttpServletRequest request) {
        List<ReviewException.FieldError> errors = new ArrayList<>();
        e.getParameterValidationResults().forEach(result -> {
            String parameter = result.getMethodParameter().getParameterName();
            String field = parameter == null ? "request" : wireName(parameter);
            result.getResolvableErrors().forEach(error -> errors.add(new ReviewException.FieldError(
                    field, error.getDefaultMessage() == null ? "is invalid" : error.getDefaultMessage())));
        });
        return invalidRequest("A request parameter is outside its allowed range", errors, request);
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<Map<String, Object>> onTypeMismatch(MethodArgumentTypeMismatchException e,
                                                              HttpServletRequest request) {
        Class<?> required = e.getRequiredType();
        String expected = required == null ? "a valid value" : "a valid " + describe(required);
        return invalidRequest("A request parameter has the wrong type",
                List.of(new ReviewException.FieldError(wireName(e.getName()), "must be " + expected)), request);
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<Map<String, Object>> onMissingParameter(MissingServletRequestParameterException e,
                                                                  HttpServletRequest request) {
        return invalidRequest("A required request parameter is missing",
                List.of(new ReviewException.FieldError(wireName(e.getParameterName()), "is required")), request);
    }

    /**
     * An unreadable body: malformed JSON, a value of the wrong type, an enum member that
     * does not exist, or — because requests are strict — a field the contract does not
     * define.
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, Object>> onUnreadableBody(HttpMessageNotReadableException e,
                                                                HttpServletRequest request) {
        if (e.getCause() instanceof UnrecognizedPropertyException unknown) {
            return invalidRequest("The request body carries a field this operation does not define",
                    List.of(new ReviewException.FieldError(path(unknown), "is not a field of this request")), request);
        }
        if (e.getCause() instanceof JsonMappingException mapping && !mapping.getPath().isEmpty()) {
            return invalidRequest("The request body has an invalid value",
                    List.of(new ReviewException.FieldError(path(mapping), "has an invalid value")), request);
        }
        return invalidRequest("The request body is not valid JSON", List.of(), request);
    }

    @ExceptionHandler({NoResourceFoundException.class, NoHandlerFoundException.class})
    public ResponseEntity<Map<String, Object>> onNoRoute(Exception e, HttpServletRequest request) {
        return problem(ErrorCode.NOT_FOUND, "This service has no such operation", List.of(), request);
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<Map<String, Object>> onMethodNotAllowed(HttpRequestMethodNotSupportedException e,
                                                                  HttpServletRequest request) {
        ResponseEntity<Map<String, Object>> response = problem(ErrorCode.METHOD_NOT_ALLOWED,
                "This operation does not accept " + e.getMethod(), List.of(), request);
        if (e.getSupportedHttpMethods() == null || e.getSupportedHttpMethods().isEmpty()) {
            return response;
        }
        return ResponseEntity.status(response.getStatusCode())
                .headers(response.getHeaders())
                .allow(e.getSupportedHttpMethods().toArray(HttpMethod[]::new))
                .body(response.getBody());
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<Map<String, Object>> onUnsupportedMediaType(HttpMediaTypeNotSupportedException e,
                                                                      HttpServletRequest request) {
        return problem(ErrorCode.UNSUPPORTED_MEDIA_TYPE,
                "This operation only accepts application/json", List.of(), request);
    }

    /**
     * The caller asked for a representation Review never produces. A body in a type the
     * caller refuses would only fail again, so the answer is the bare status.
     */
    @ExceptionHandler(HttpMediaTypeNotAcceptableException.class)
    public ResponseEntity<Void> onNotAcceptable(HttpMediaTypeNotAcceptableException e) {
        return ResponseEntity.status(HttpStatus.NOT_ACCEPTABLE).build();
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
     * (WF-REVIEW-005). {@link TransientDataAccessException} covers a query timeout and a
     * lock that could not be acquired as well as a lost connection, which is the "lock
     * dependency temporarily unavailable" the OpenAPI declares retryable.
     */
    @ExceptionHandler({DataAccessResourceFailureException.class, TransientDataAccessException.class})
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

    private ResponseEntity<Map<String, Object>> invalidRequest(String detail,
                                                               List<ReviewException.FieldError> errors,
                                                               HttpServletRequest request) {
        return problem(ErrorCode.INVALID_REQUEST, detail, errors, request);
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

        // X-Request-Id is a caller-chosen 8..128 character string, while the Problem schema
        // bounds trace_id to 16..64. Echoing it blindly would emit a body that violates the
        // shared schema, so a value outside the bounds is simply not echoed.
        String traceId = request.getHeader(properties.gateway().requestIdHeader());
        if (traceId != null && traceId.length() >= TRACE_ID_MIN && traceId.length() <= TRACE_ID_MAX) {
            body.put("trace_id", traceId);
        }
        if (!errors.isEmpty()) {
            body.put("errors", errors.stream()
                    .limit(MAX_FIELD_ERRORS)
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

    /** {@code listProductReviews.limit} and {@code limit} both name the parameter {@code limit}. */
    private static String leafName(Path path) {
        String name = null;
        for (Path.Node node : path) {
            if (node.getName() != null) {
                name = node.getName();
            }
        }
        return name == null ? "request" : name;
    }

    /** A JSON path such as {@code media_asset_ids[0]}, in the names the wire uses. */
    private static String path(JsonMappingException e) {
        StringBuilder path = new StringBuilder();
        for (JsonMappingException.Reference reference : e.getPath()) {
            if (reference.getFieldName() != null) {
                if (path.length() > 0) {
                    path.append('.');
                }
                path.append(reference.getFieldName());
            } else if (reference.getIndex() >= 0) {
                path.append('[').append(reference.getIndex()).append(']');
            }
        }
        return path.length() == 0 ? "body" : path.toString();
    }

    private static String wireName(String javaName) {
        return ((PropertyNamingStrategies.NamingBase) PropertyNamingStrategies.SNAKE_CASE).translate(javaName);
    }

    private static String describe(Class<?> type) {
        if (type.isEnum()) {
            return "member of " + java.util.Arrays.stream(type.getEnumConstants())
                    .map(Object::toString).collect(Collectors.joining(", "));
        }
        return switch (type.getSimpleName()) {
            case "UUID" -> "UUID";
            case "int", "Integer", "long", "Long" -> "integer";
            case "boolean", "Boolean" -> "boolean";
            default -> "value";
        };
    }
}
