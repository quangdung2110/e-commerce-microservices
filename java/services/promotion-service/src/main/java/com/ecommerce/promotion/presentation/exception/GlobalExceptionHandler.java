package com.ecommerce.promotion.presentation.exception;

import com.ecommerce.promotion.application.exception.*;
import com.ecommerce.promotion.presentation.dto.ApiErrorResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;
import java.util.stream.Collectors;

/**
 * Global Exception Handler — @ControllerAdvice.
 * Theo thiết kế các Exception classes đã định nghĩa ở Layer 2:
 *
 * - ResourceNotFoundException    → 404 Not Found
 * - BudgetExhaustedException     → 409 Conflict
 * - DuplicateClaimException      → 409 Conflict
 * - FlashSaleSoldOutException    → 410 Gone
 * - PromotionExpiredException    → 410 Gone
 * - RuleConditionNotMetException → 422 Unprocessable Entity
 * - RateLimitExceededException   → 429 Too Many Requests
 * - CoreBusinessException (base) → 400 Bad Request
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    // =========================================================================
    // 404 Not Found
    // =========================================================================

    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<ApiErrorResponse> handleResourceNotFound(ResourceNotFoundException ex) {
        log.warn("Resource not found: {}", ex.getMessage());
        return buildResponse(HttpStatus.NOT_FOUND, ex.getErrorCode(), ex.getMessage());
    }

    // =========================================================================
    // 409 Conflict
    // =========================================================================

    @ExceptionHandler(BudgetExhaustedException.class)
    public ResponseEntity<ApiErrorResponse> handleBudgetExhausted(BudgetExhaustedException ex) {
        log.warn("Budget exhausted: {}", ex.getMessage());
        return buildResponse(HttpStatus.CONFLICT, ex.getErrorCode(), ex.getMessage());
    }

    @ExceptionHandler(DuplicateClaimException.class)
    public ResponseEntity<ApiErrorResponse> handleDuplicateClaim(DuplicateClaimException ex) {
        log.warn("Duplicate claim: {}", ex.getMessage());
        return buildResponse(HttpStatus.CONFLICT, ex.getErrorCode(), ex.getMessage());
    }

    // =========================================================================
    // 410 Gone
    // =========================================================================

    @ExceptionHandler(FlashSaleSoldOutException.class)
    public ResponseEntity<ApiErrorResponse> handleFlashSaleSoldOut(FlashSaleSoldOutException ex) {
        log.warn("Flash sale sold out: {}", ex.getMessage());
        return buildResponse(HttpStatus.GONE, ex.getErrorCode(), ex.getMessage());
    }

    @ExceptionHandler(PromotionExpiredException.class)
    public ResponseEntity<ApiErrorResponse> handlePromotionExpired(PromotionExpiredException ex) {
        log.warn("Promotion expired: {}", ex.getMessage());
        return buildResponse(HttpStatus.GONE, ex.getErrorCode(), ex.getMessage());
    }

    // =========================================================================
    // 422 Unprocessable Entity
    // =========================================================================

    @ExceptionHandler(RuleConditionNotMetException.class)
    public ResponseEntity<ApiErrorResponse> handleRuleConditionNotMet(RuleConditionNotMetException ex) {
        log.warn("Rule condition not met: {}", ex.getMessage());
        return buildResponse(HttpStatus.UNPROCESSABLE_ENTITY, ex.getErrorCode(), ex.getMessage());
    }

    // =========================================================================
    // 429 Too Many Requests
    // =========================================================================

    @ExceptionHandler(RateLimitExceededException.class)
    public ResponseEntity<ApiErrorResponse> handleRateLimitExceeded(RateLimitExceededException ex) {
        log.warn("Rate limit exceeded: {}", ex.getMessage());
        return buildResponse(HttpStatus.TOO_MANY_REQUESTS, ex.getErrorCode(), ex.getMessage());
    }

    // =========================================================================
    // 400 Bad Request — Base business exception
    // =========================================================================

    @ExceptionHandler(CoreBusinessException.class)
    public ResponseEntity<ApiErrorResponse> handleCoreBusinessException(CoreBusinessException ex) {
        log.warn("Business error: {}", ex.getMessage());
        return buildResponse(HttpStatus.BAD_REQUEST, ex.getErrorCode(), ex.getMessage());
    }

    // =========================================================================
    // 400 Bad Request — Validation errors (@Valid)
    // =========================================================================

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiErrorResponse> handleValidationErrors(MethodArgumentNotValidException ex) {
        String details = ex.getBindingResult().getFieldErrors().stream()
                .map(fe -> fe.getField() + ": " + fe.getDefaultMessage())
                .collect(Collectors.joining("; "));
        log.warn("Validation error: {}", details);
        return buildResponse(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", details);
    }

    // =========================================================================
    // 500 Internal Server Error — catch-all
    // =========================================================================

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiErrorResponse> handleGenericException(Exception ex) {
        log.error("Unexpected error", ex);
        return buildResponse(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR",
                "An unexpected error occurred. Please try again later.");
    }

    // =========================================================================
    // Helper
    // =========================================================================

    private ResponseEntity<ApiErrorResponse> buildResponse(HttpStatus status, String errorCode, String message) {
        ApiErrorResponse body = new ApiErrorResponse(
                Instant.now(),
                status.value(),
                status.getReasonPhrase(),
                errorCode,
                message
        );
        return ResponseEntity.status(status).body(body);
    }
}
