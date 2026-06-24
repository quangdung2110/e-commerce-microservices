package com.platform.inventoryservice.infrastructure.persistence.jpa.custom;

import java.util.Collection;
import java.util.List;
import java.util.Map;

public interface PhysicalInventoryJpaCustom {
    List<AvailableStockProjection> findAvailableBySkuIds(Collection<String> skuIds);

    /** Aggregate per-SKU total available across warehouses. */
    Map<String, Long> sumAvailableBySkuIds(Collection<String> skuIds);

    /** Bulk update reserved_quantity for many rows. */
    int bulkAdjustReserved(Collection<ReservedAdjustment> adjustments);

    /** Bulk upsert (INSERT ... ON CONFLICT DO UPDATE). */
    int bulkUpsert(Collection<PhysicalInventoryUpsert> upserts);

    record AvailableStockProjection(String skuId, String productId, long warehouseId, long available) {}
    record ReservedAdjustment(String skuId, long warehouseId, int delta) {}
    record PhysicalInventoryUpsert(String skuId, String productId, long warehouseId, int actualQty) {}
}
