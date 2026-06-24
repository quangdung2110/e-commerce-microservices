package com.platform.inventoryservice.controller;

import com.platform.inventoryservice.common.ErrorCode;
import com.platform.inventoryservice.domain.exception.DomainException;
import com.platform.inventoryservice.domain.exception.RefundScopeMismatchException;
import com.platform.inventoryservice.dto.response.ErrorResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;
import java.util.Map;
import java.util.stream.Collectors;

@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

    @ExceptionHandler(DomainException.class)
    public ResponseEntity<ErrorResponse> handleDomainException(DomainException ex) {
        ErrorCode code = ex.errorCode();
        log.warn("Domain exception: code={} message={}", code, ex.getMessage());

        ErrorResponse body = new ErrorResponse(
            code.name(),
            ex.getMessage(),
            null,
            Instant.now()
        );

        if (ex instanceof RefundScopeMismatchException rsm) {
            body = new ErrorResponse(
                code.name(),
                rsm.getMessage(),
                Map.of(
                    "existing_order_item_ids", rsm.existing(),
                    "requested_order_item_ids", rsm.requested()
                ),
                Instant.now()
            );
        }

        return ResponseEntity.status(code.httpStatus()).body(body);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException ex) {
        String details = ex.getBindingResult().getFieldErrors().stream()
            .map(e -> e.getField() + ": " + e.getDefaultMessage())
            .collect(Collectors.joining("; "));
        return ResponseEntity.badRequest().body(new ErrorResponse(
            "INVALID_REQUEST",
            "Validation failed: " + details,
            null, Instant.now()
        ));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ErrorResponse> handleIllegalArgument(IllegalArgumentException ex) {
        return ResponseEntity.badRequest().body(new ErrorResponse(
            "INVALID_REQUEST",
            ex.getMessage(),
            null, Instant.now()
        ));
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ErrorResponse> handleAccessDenied(AccessDeniedException ex) {
        return ResponseEntity.status(403).body(new ErrorResponse(
            "ACCESS_DENIED", ex.getMessage(), null, Instant.now()
        ));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleAny(Exception ex) {
        log.error("Unhandled exception", ex);
        return ResponseEntity.status(500).body(new ErrorResponse(
            "INTERNAL_ERROR", "Internal server error", null, Instant.now()
        ));
    }
}
