package com.platform.inventoryservice.domain.exception;

import com.platform.inventoryservice.common.ErrorCode;

public class SkuSuspendedException extends DomainException {

    public SkuSuspendedException(String skuId, long warehouseId, String reason) {
        super(ErrorCode.SKU_SUSPENDED,
                "SKU " + skuId + " at warehouse " + warehouseId + " suspended: " + reason);
    }
}