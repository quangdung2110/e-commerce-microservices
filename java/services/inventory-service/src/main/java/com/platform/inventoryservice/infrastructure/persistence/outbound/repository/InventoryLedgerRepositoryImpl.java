package com.platform.inventoryservice.infrastructure.persistence.outbound.repository;

import com.platform.inventoryservice.domain.entity.InventoryLedgerEntry;
import com.platform.inventoryservice.infrastructure.persistence.jpa.spring.InventoryLedgerJpaRepository;
import com.platform.inventoryservice.infrastructure.persistence.outbound.mapper.InventoryLedgerMapper;
import com.platform.inventoryservice.port.outbound.database.InventoryLedgerRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

@Repository
@RequiredArgsConstructor
public class InventoryLedgerRepositoryImpl implements InventoryLedgerRepository {

    private final InventoryLedgerJpaRepository jpa;
    private final InventoryLedgerMapper mapper;

    @Override
    @Transactional
    public void append(InventoryLedgerEntry entry) {
        jpa.save(mapper.toJpa(entry));
    }

    @Override
    @Transactional(readOnly = true)
    public boolean existsByEventId(UUID eventId) {
        return jpa.findByEventId(eventId).isPresent();
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<InventoryLedgerEntry> findLatest(String skuId, long warehouseId) {
        return jpa.findFirstBySkuIdAndWarehouseIdOrderByCreatedAtDesc(skuId, warehouseId)
            .map(mapper::toDomain);
    }
}
