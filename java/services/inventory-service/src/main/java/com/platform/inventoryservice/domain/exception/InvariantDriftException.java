package com.platform.inventoryservice.domain.exception;

import com.platform.inventoryservice.common.ErrorCode;

public class InvariantDriftException extends DomainException {

    public InvariantDriftException(String skuId, long warehouseId) {
        super(ErrorCode.INVARIANT_DRIFT,
                "Invariant drift at " + skuId + ":" + warehouseId);
    }
}