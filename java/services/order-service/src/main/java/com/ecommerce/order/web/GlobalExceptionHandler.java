package com.ecommerce.order.web;

import com.ecommerce.order.common.ApiException;
import com.ecommerce.order.common.ErrorCode;
import com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Maps every failure to the shared problem shape; DB outage is retryable 503. */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    private final Problems problems;

    public GlobalExceptionHandler(Problems problems) {
        this.problems = problems;
    }

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<Object> api(ApiException e, HttpServletRequest request) {
        return problems.response(e, request);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Object> invalidBody(MethodArgumentNotValidException e, HttpServletRequest request) {
        List<ApiException.FieldError> errors = new ArrayList<>();
        e.getBindingResult().getFieldErrors().forEach(error ->
                errors.add(new ApiException.FieldError(error.getField(), error.getDefaultMessage())));
        e.getBindingResult().getGlobalErrors().forEach(error ->
                errors.add(new ApiException.FieldError(error.getObjectName(), error.getDefaultMessage())));
        return invalid("Request validation failed", errors, request);
    }

    @ExceptionHandler(HandlerMethodValidationException.class)
    public ResponseEntity<Object> invalidParameters(HandlerMethodValidationException e, HttpServletRequest request) {
        List<ApiException.FieldError> errors = new ArrayList<>();
        e.getAllValidationResults().forEach(result -> result.getResolvableErrors().forEach(error ->
                errors.add(new ApiException.FieldError(result.getMethodParameter().getParameterName(),
                        error.getDefaultMessage()))));
        return invalid("Request validation failed", errors, request);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<Object> constraint(ConstraintViolationException e, HttpServletRequest request) {
        List<ApiException.FieldError> errors = e.getConstraintViolations().stream()
                .map(v -> new ApiException.FieldError(v.getPropertyPath().toString(), v.getMessage())).toList();
        return invalid("Request validation failed", errors, request);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Object> unreadable(HttpMessageNotReadableException e, HttpServletRequest request) {
        if (e.getCause() instanceof UnrecognizedPropertyException unknown) {
            return invalid("Unknown field", List.of(new ApiException.FieldError(unknown.getPropertyName(),
                    "is not allowed")), request);
        }
        return invalid("Malformed JSON request body", List.of(), request);
    }

    @ExceptionHandler({MissingRequestHeaderException.class, MissingServletRequestParameterException.class,
            MethodArgumentTypeMismatchException.class, HttpMediaTypeNotSupportedException.class})
    public ResponseEntity<Object> badInput(Exception e, HttpServletRequest request) {
        // Spring's own messages name Java classes and parameter types; the wire gets a fixed sentence.
        String detail = switch (e) {
            case MissingRequestHeaderException header -> "Missing required header " + header.getHeaderName();
            case MissingServletRequestParameterException parameter ->
                    "Missing required parameter " + parameter.getParameterName();
            case MethodArgumentTypeMismatchException mismatch -> "Invalid value for parameter " + mismatch.getName();
            default -> "Unsupported request content type";
        };
        return invalid(detail, List.of(), request);
    }

    @ExceptionHandler({NoResourceFoundException.class, HttpRequestMethodNotSupportedException.class})
    public ResponseEntity<Object> noResource(Exception e, HttpServletRequest request) {
        return problems.response(ErrorCode.NOT_FOUND, "No such resource", List.of(), request.getRequestURI(), null,
                Map.of());
    }

    @ExceptionHandler(DataAccessException.class)
    public ResponseEntity<Object> dataAccess(DataAccessException e, HttpServletRequest request) {
        log.error("Order store failure on {}", request.getRequestURI(), e);
        return problems.response(ErrorCode.ORDER_UNAVAILABLE, "The order store is unavailable", List.of(),
                request.getRequestURI(), 5, Map.of());
    }

    private ResponseEntity<Object> invalid(String detail, List<ApiException.FieldError> errors,
                                           HttpServletRequest request) {
        return problems.response(ErrorCode.INVALID_REQUEST, detail, errors, request.getRequestURI(), null, Map.of());
    }
}
