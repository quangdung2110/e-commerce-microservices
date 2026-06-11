package com.platform.productservice.common.errors;

import org.springframework.http.HttpStatus;

public enum ErrorCode {

    PRODUCT_NOT_FOUND("PRODUCT_001", HttpStatus.NOT_FOUND.value(), "Product not found"),
    SKU_NOT_FOUND("SKU_001", HttpStatus.NOT_FOUND.value(), "SKU not found"),
    CATEGORY_NOT_FOUND("CATEGORY_001", HttpStatus.NOT_FOUND.value(), "Category not found"),
    MEDIA_NOT_FOUND("MEDIA_001", HttpStatus.NOT_FOUND.value(), "Media not found"),

    INVALID_STATE_TRANSITION("STATE_001", HttpStatus.BAD_REQUEST.value(), "Invalid state transition"),
    DUPLICATE_SLUG("DUP_001", HttpStatus.CONFLICT.value(), "Slug already exists"),
    DUPLICATE_SKU_CODE("DUP_002", HttpStatus.CONFLICT.value(), "SKU code already exists"),

    VALIDATION_ERROR("VAL_001", HttpStatus.BAD_REQUEST.value(), "Validation error"),
    CONCURRENCY_CONFLICT("CONC_001", HttpStatus.CONFLICT.value(), "Concurrency conflict"),
    LOCK_ACQUISITION_FAILED("LOCK_001", HttpStatus.SERVICE_UNAVAILABLE.value(), "Lock acquisition failed"),
    CACHE_ERROR("CACHE_001", HttpStatus.SERVICE_UNAVAILABLE.value(), "Cache error"),
    OUTBOX_ERROR("OUTBOX_001", HttpStatus.INTERNAL_SERVER_ERROR.value(), "Outbox error"),

    UNAUTHORIZED("AUTH_001", HttpStatus.UNAUTHORIZED.value(), "Unauthorized"),
    FORBIDDEN("AUTH_002", HttpStatus.FORBIDDEN.value(), "Forbidden"),
    INTERNAL_ERROR("INT_001", HttpStatus.INTERNAL_SERVER_ERROR.value(), "Internal server error");

    private final String code;
    private final int httpStatus;
    private final String message;

    ErrorCode(String code, int httpStatus, String message) {
        this.code = code;
        this.httpStatus = httpStatus;
        this.message = message;
    }

    public String getCode() { return code; }
    public int getHttpStatus() { return httpStatus; }
    public String getMessage() { return message; }

    public ErrorCodeException toException() {
        return new ErrorCodeException(this);
    }

    public ErrorCodeException toException(String detail) {
        return new ErrorCodeException(this, detail);
    }

    public static class ErrorCodeException extends RuntimeException {
        private final ErrorCode errorCode;
        private final String detail;

        public ErrorCodeException(ErrorCode errorCode) {
            super(errorCode.message);
            this.errorCode = errorCode;
            this.detail = null;
        }

        public ErrorCodeException(ErrorCode errorCode, String detail) {
            super(errorCode.message + ": " + detail);
            this.errorCode = errorCode;
            this.detail = detail;
        }

        public ErrorCode getErrorCode() { return errorCode; }
        public String getDetail() { return detail; }
    }
}
