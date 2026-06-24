package com.platform.inventoryservice.infrastructure.persistence.jpa.spring;

import com.platform.inventoryservice.infrastructure.persistence.jpa.entity.InventoryLedgerJpaEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface InventoryLedgerJpaRepository
        extends JpaRepository<InventoryLedgerJpaEntity, Long> {

    Optional<InventoryLedgerJpaEntity> findByEventId(UUID eventId);

    List<InventoryLedgerJpaEntity> findBySkuIdOrderByCreatedAtDesc(String skuId);

    Optional<InventoryLedgerJpaEntity> findFirstBySkuIdAndWarehouseIdOrderByCreatedAtDesc(
        String skuId, Long warehouseId);
}
