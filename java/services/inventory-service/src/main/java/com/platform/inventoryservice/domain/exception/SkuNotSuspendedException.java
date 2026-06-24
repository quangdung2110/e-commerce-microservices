package com.platform.inventoryservice.domain.exception;

import com.platform.inventoryservice.common.ErrorCode;

public class SkuNotSuspendedException extends DomainException {

    public SkuNotSuspendedException(String skuId, long warehouseId) {
        super(ErrorCode.SKU_NOT_SUSPENDED,
                "SKU " + skuId + ":" + warehouseId + " is not suspended");
    }
}