package com.platform.inventoryservice.service;

import com.platform.inventoryservice.domain.exception.SkuNotSuspendedException;
import com.platform.inventoryservice.domain.vo.SkuWarehouseKey;
import com.platform.inventoryservice.port.inbound.UnlockSkuPort;
import com.platform.inventoryservice.port.outbound.cache.SuspensionFlagManager;
import com.platform.inventoryservice.port.outbound.metrics.InventoryMetrics;
import com.platform.inventoryservice.service.command.UnlockSkuCommand;
import com.platform.inventoryservice.service.result.UnlockSkuResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;

@Service
@RequiredArgsConstructor
@Slf4j
public class UnlockSkuService implements UnlockSkuPort {

    private final SuspensionFlagManager suspensionManager;
    private final InventoryMetrics metrics;

    @Override
    public UnlockSkuResult unlock(UnlockSkuCommand cmd) {
        SkuWarehouseKey key = new SkuWarehouseKey(cmd.skuId(), cmd.warehouseId());

        boolean wasSuspended = suspensionManager.isSuspended(key);
        if (!wasSuspended) {
            throw new SkuNotSuspendedException(cmd.skuId(), cmd.warehouseId());
        }

        boolean deleted = suspensionManager.unsuspend(key);

        log.info("SKU unlocked by admin: sku={} warehouse={} reason={} correlation={}",
                 cmd.skuId(), cmd.warehouseId(), cmd.reason(), cmd.correlationId());

        metrics.recordUnlock();

        return new UnlockSkuResult(
            cmd.skuId(), cmd.warehouseId(), deleted, Instant.now());
    }
}
