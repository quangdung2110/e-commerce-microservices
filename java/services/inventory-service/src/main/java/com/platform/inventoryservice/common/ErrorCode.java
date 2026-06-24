package com.platform.inventoryservice.common;

public enum ErrorCode {

    INVALID_REQUEST(400),
    WAREHOUSE_NOT_FOUND(404),
    SKU_NOT_FOUND(404),
    RESERVATION_NOT_FOUND(404),
    SKU_NOT_SUSPENDED(404),
    ORDER_NOT_FOUND(404),
    INSUFFICIENT_STOCK(409),
    REFUND_NOT_EXPECTED(409),
    REFUND_SCOPE_MISMATCH(409),
    OUT_OF_STOCK(409),
    INSUFFICIENT_RESERVED(409),
    RELEASE_UNDERFLOW(409),
    SKU_SUSPENDED(409),
    INVARIANT_DRIFT(500),
    INTERNAL_ERROR(500);

    private final int httpStatus;

    ErrorCode(int httpStatus) {
        this.httpStatus = httpStatus;
    }

    public int httpStatus() {
        return httpStatus;
    }
}