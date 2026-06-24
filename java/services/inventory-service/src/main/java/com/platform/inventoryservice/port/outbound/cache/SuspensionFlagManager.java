package com.platform.inventoryservice.port.outbound.cache;

import com.platform.inventoryservice.domain.vo.SkuWarehouseKey;
import java.util.Optional;

public interface SuspensionFlagManager {
    void suspend(SkuWarehouseKey key, String reason);
    boolean unsuspend(SkuWarehouseKey key);
    boolean isSuspended(SkuWarehouseKey key);
    Optional<SuspensionInfo> getInfo(SkuWarehouseKey key);
}
