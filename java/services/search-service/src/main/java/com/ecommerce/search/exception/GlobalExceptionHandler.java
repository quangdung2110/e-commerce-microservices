package com.ecommerce.search.exception;

import com.ecommerce.search.common.ErrorCode;
import com.ecommerce.search.domain.exception.InvalidCursorException;
import com.ecommerce.search.domain.exception.SearchException;
import com.ecommerce.search.dto.ErrorResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.http.converter.HttpMessageNotReadableException;

import java.util.HashMap;
import java.util.Map;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(InvalidCursorException.class)
    public ResponseEntity<ErrorResponse> handleInvalidCursor(InvalidCursorException ex, HttpServletRequest req) {
        log.warn("Invalid cursor: {}", ex.getMessage());
        return buildResponse(ErrorCode.INVALID_CURSOR, ex.getMessage(), null, req);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException ex, HttpServletRequest req) {
        Map<String, Object> details = new HashMap<>();
        ex.getBindingResult().getFieldErrors().forEach(err ->
            details.put(err.getField(), err.getDefaultMessage())
        );
        return buildResponse(ErrorCode.INVALID_QUERY_PARAMETER, "Validation failed", details, req);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ErrorResponse> handleConstraintViolation(ConstraintViolationException ex, HttpServletRequest req) {
        Map<String, Object> details = new HashMap<>();
        ex.getConstraintViolations().forEach(violation ->
            details.put(violation.getPropertyPath().toString(), violation.getMessage())
        );
        return buildResponse(ErrorCode.INVALID_QUERY_PARAMETER, "Constraint violation", details, req);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> handleHttpMessageNotReadable(HttpMessageNotReadableException ex, HttpServletRequest req) {
        log.warn("Message not readable: {}", ex.getMessage());
        return buildResponse(ErrorCode.INVALID_QUERY_PARAMETER, "Invalid request body", null, req);
    }

    @ExceptionHandler(SearchException.class)
    public ResponseEntity<ErrorResponse> handleSearch(SearchException ex, HttpServletRequest req) {
        log.error("Search error: {}", ex.getMessage(), ex);
        return buildResponse(ex.getErrorCode(), ex.getMessage(), null, req);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleGeneric(Exception ex, HttpServletRequest req) {
        log.error("Unexpected error", ex);
        return buildResponse(ErrorCode.INTERNAL_ERROR, "Internal server error", null, req);
    }

    private ResponseEntity<ErrorResponse> buildResponse(ErrorCode code, String message,
                                                         Map<String, Object> details,
                                                         HttpServletRequest req) {
        String requestId = MDC.get("request_id");
        if (requestId == null) {
            requestId = req.getHeader("X-Request-Id");
        }
        ErrorResponse body = ErrorResponse.builder()
            .errorCode(code.getCode())
            .message(message)
            .details(details)
            .requestId(requestId)
            .build();
        return ResponseEntity.status(HttpStatus.valueOf(code.getHttpStatus())).body(body);
    }
}
