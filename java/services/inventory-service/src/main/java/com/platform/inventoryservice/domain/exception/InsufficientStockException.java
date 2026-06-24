package com.platform.inventoryservice.domain.exception;

import com.platform.inventoryservice.common.ErrorCode;

public class InsufficientStockException extends DomainException {

    public InsufficientStockException(String skuId) {
        super(ErrorCode.INSUFFICIENT_STOCK, "Insufficient stock for sku: " + skuId);
    }
}