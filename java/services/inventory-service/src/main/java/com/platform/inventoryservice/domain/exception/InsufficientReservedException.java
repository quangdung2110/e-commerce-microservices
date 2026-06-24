package com.platform.inventoryservice.domain.exception;

import com.platform.inventoryservice.common.ErrorCode;

public class InsufficientReservedException extends DomainException {

    public InsufficientReservedException(String skuId, long warehouseId) {
        super(ErrorCode.INSUFFICIENT_RESERVED,
                "Insufficient reserved at warehouse " + warehouseId + " for sku: " + skuId);
    }
}