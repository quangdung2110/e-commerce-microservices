package com.platform.inventoryservice.port.outbound.cache;

import com.platform.inventoryservice.domain.enums.LuaReturnCode;

public record StockMutationResult(
    LuaReturnCode returnCode,
    long availableAfter,
    long reservedAfter
) {
}
