package com.platform.inventoryservice.domain.exception;

import com.platform.inventoryservice.common.ErrorCode;

/**
 * Base class cho mọi domain exception trong Inventory Service.
 * Mỗi subclass gắn với một {@link ErrorCode} để HTTP layer (M13) map sang status code.
 */
public abstract class DomainException extends RuntimeException {

    private final ErrorCode errorCode;

    protected DomainException(ErrorCode code, String message) {
        super(message);
        this.errorCode = code;
    }

    public ErrorCode errorCode() {
        return errorCode;
    }
}