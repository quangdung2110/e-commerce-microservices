package com.platform.inventoryservice.domain.exception;

import com.platform.inventoryservice.common.ErrorCode;

public class OutOfStockException extends DomainException {

    public OutOfStockException(String skuId, long warehouseId) {
        super(ErrorCode.OUT_OF_STOCK,
                "Out of stock at warehouse " + warehouseId + " for sku: " + skuId);
    }
}