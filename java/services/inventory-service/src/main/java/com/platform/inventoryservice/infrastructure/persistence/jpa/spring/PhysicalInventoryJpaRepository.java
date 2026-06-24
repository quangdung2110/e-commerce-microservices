package com.platform.inventoryservice.infrastructure.persistence.jpa.spring;

import com.platform.inventoryservice.infrastructure.persistence.jpa.custom.PhysicalInventoryJpaCustom;
import com.platform.inventoryservice.infrastructure.persistence.jpa.entity.PhysicalInventoryJpaEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface PhysicalInventoryJpaRepository
        extends JpaRepository<PhysicalInventoryJpaEntity, Long>,
                PhysicalInventoryJpaCustom {

    Optional<PhysicalInventoryJpaEntity> findBySkuIdAndWarehouseId(String skuId, Long warehouseId);

    List<PhysicalInventoryJpaEntity> findBySkuIdInAndAvailableQuantityGreaterThan(
        Collection<String> skuIds, int threshold);
}
