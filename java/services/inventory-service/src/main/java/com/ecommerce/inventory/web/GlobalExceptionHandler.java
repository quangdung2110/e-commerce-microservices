package com.ecommerce.inventory.web;

import com.ecommerce.inventory.common.ApiException;
import com.ecommerce.inventory.common.ErrorCode;
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

/** Maps every non-durable failure to the shared problem shape (interfaces.md §5). */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    private final Problems problems;

    public GlobalExceptionHandler(Problems problems) {
        this.problems = problems;
    }

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<Object> api(ApiException e, HttpServletRequest request) {
        return problems.response(e.code(), e.getMessage(), e.errors(), commandId(request), e.retryAfterSeconds());
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
        e.getParameterValidationResults().forEach(result -> result.getResolvableErrors().forEach(error ->
                errors.add(new ApiException.FieldError(result.getMethodParameter().getParameterName(),
                        error.getDefaultMessage()))));
        return invalid("Request validation failed", errors, request);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<Object> constraint(ConstraintViolationException e, HttpServletRequest request) {
        List<ApiException.FieldError> errors = e.getConstraintViolations().stream()
                .map(v -> new ApiException.FieldError(v.getPropertyPath().toString(), v.getMessage()))
                .toList();
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
        return invalid(e.getMessage(), List.of(), request);
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<Object> noResource(NoResourceFoundException e, HttpServletRequest request) {
        return problems.response(ErrorCode.NOT_FOUND, "No such resource", List.of(), null, null);
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<Object> method(HttpRequestMethodNotSupportedException e) {
        return problems.response(ErrorCode.NOT_FOUND, e.getMessage(), List.of(), null, null);
    }

    @ExceptionHandler(DataAccessException.class)
    public ResponseEntity<Object> dataAccess(DataAccessException e, HttpServletRequest request) {
        log.error("authoritative store failure on {}", request.getRequestURI(), e);
        return problems.response(ErrorCode.INVENTORY_UNAVAILABLE, "The authoritative store is unavailable",
                List.of(), commandId(request), 5);
    }

    private ResponseEntity<Object> invalid(String detail, List<ApiException.FieldError> errors,
                                           HttpServletRequest request) {
        return problems.response(ErrorCode.INVALID_REQUEST, detail, errors, commandId(request), null);
    }

    private static String commandId(HttpServletRequest request) {
        return request.getHeader("Idempotency-Key");
    }
}
