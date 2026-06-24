package com.platform.inventoryservice.port.outbound.database;

import com.platform.inventoryservice.domain.entity.InventoryLedgerEntry;

import java.util.Optional;
import java.util.UUID;

public interface InventoryLedgerRepository {
    void append(InventoryLedgerEntry entry);
    boolean existsByEventId(UUID eventId);
    Optional<InventoryLedgerEntry> findLatest(String skuId, long warehouseId);
}
