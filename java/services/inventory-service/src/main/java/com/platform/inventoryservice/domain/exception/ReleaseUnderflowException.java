package com.platform.inventoryservice.domain.exception;

import com.platform.inventoryservice.common.ErrorCode;

public class ReleaseUnderflowException extends DomainException {

    public ReleaseUnderflowException(String skuId, long warehouseId) {
        super(ErrorCode.RELEASE_UNDERFLOW,
                "Release underflow at warehouse " + warehouseId + " for sku: " + skuId);
    }
}