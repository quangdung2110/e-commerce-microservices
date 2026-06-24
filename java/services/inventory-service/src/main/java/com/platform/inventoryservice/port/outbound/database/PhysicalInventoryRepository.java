package com.platform.inventoryservice.port.outbound.database;

import com.platform.inventoryservice.domain.entity.PhysicalInventory;
import com.platform.inventoryservice.domain.vo.AvailableStock;
import com.platform.inventoryservice.domain.vo.SkuWarehouseKey;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public interface PhysicalInventoryRepository {
    Optional<PhysicalInventory> findByKey(SkuWarehouseKey key);
    List<AvailableStock> findAvailableBySkuIds(Collection<String> skuIds);
    Map<String, Long> sumAvailableBySkuIds(Collection<String> skuIds);
    PhysicalInventory upsert(SkuWarehouseKey key, String productId, int actualQtyDelta);
    void adjustReserved(SkuWarehouseKey key, int delta);
    void adjustActual(SkuWarehouseKey key, int delta);
    int applyDeduct(SkuWarehouseKey key, int qty);
}
