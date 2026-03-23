package com.ecommerce.promotion.application.exception;

/**
 * Base exception cho tất cả business logic errors.
 * Theo pattern product-service: CoreBusinessException.
 */
public class CoreBusinessException extends RuntimeException {

    private final String errorCode;

    public CoreBusinessException(String message, String errorCode) {
        super(message);
        this.errorCode = errorCode;
    }

    public String getErrorCode() {
        return errorCode;
    }
}
