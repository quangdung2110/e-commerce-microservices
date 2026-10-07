package com.ecommerce.inventory.common;

import java.util.List;

/**
 * A non-durable failure answered as a problem response: validation, authentication,
 * idempotency-key reuse, technical unavailability, export gone. Durable business
 * rejections use {@link DomainRejection} so the command executor can persist them.
 */
public class ApiException extends RuntimeException {

    private final ErrorCode code;
    private final List<FieldError> errors;
    private final Integer retryAfterSeconds;

    public ApiException(ErrorCode code, String detail) {
        this(code, detail, List.of(), null);
    }

    public ApiException(ErrorCode code, String detail, List<FieldError> errors, Integer retryAfterSeconds) {
        super(detail);
        this.code = code;
        this.errors = errors == null ? List.of() : errors;
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public static ApiException invalid(String detail) {
        return new ApiException(ErrorCode.INVALID_REQUEST, detail);
    }

    public static ApiException invalid(String field, String message) {
        return new ApiException(ErrorCode.INVALID_REQUEST, "Request validation failed",
                List.of(new FieldError(field, message)), null);
    }

    public static ApiException busy() {
        return new ApiException(ErrorCode.INVENTORY_BUSY,
                "Conflicting work is still in progress; retry the same command ID", List.of(), 1);
    }

    public static ApiException unavailable(String detail) {
        return new ApiException(ErrorCode.INVENTORY_UNAVAILABLE, detail, List.of(), 5);
    }

    public ErrorCode code() {
        return code;
    }

    public List<FieldError> errors() {
        return errors;
    }

    public Integer retryAfterSeconds() {
        return retryAfterSeconds;
    }

    public record FieldError(String field, String message) {
    }
}
