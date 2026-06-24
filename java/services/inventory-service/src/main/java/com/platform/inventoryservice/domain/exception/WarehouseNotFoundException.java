package com.platform.inventoryservice.domain.exception;

import com.platform.inventoryservice.common.ErrorCode;

public class WarehouseNotFoundException extends DomainException {

    public WarehouseNotFoundException(long warehouseId) {
        super(ErrorCode.WAREHOUSE_NOT_FOUND, "Warehouse not found: " + warehouseId);
    }
}