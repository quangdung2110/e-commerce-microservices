package com.platform.inventoryservice.infrastructure.persistence.outbound.repository;

import com.platform.inventoryservice.domain.entity.PhysicalInventory;
import com.platform.inventoryservice.domain.vo.AvailableStock;
import com.platform.inventoryservice.domain.vo.SkuWarehouseKey;
import com.platform.inventoryservice.infrastructure.persistence.jpa.custom.PhysicalInventoryJpaCustom.ReservedAdjustment;
import com.platform.inventoryservice.infrastructure.persistence.jpa.entity.PhysicalInventoryJpaEntity;
import com.platform.inventoryservice.infrastructure.persistence.jpa.spring.PhysicalInventoryJpaRepository;
import com.platform.inventoryservice.infrastructure.persistence.outbound.mapper.PhysicalInventoryMapper;
import com.platform.inventoryservice.port.outbound.database.PhysicalInventoryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Repository
@RequiredArgsConstructor
public class PhysicalInventoryRepositoryImpl implements PhysicalInventoryRepository {

    private final PhysicalInventoryJpaRepository jpa;
    private final PhysicalInventoryMapper mapper;

    @Override
    @Transactional(readOnly = true)
    public Optional<PhysicalInventory> findByKey(SkuWarehouseKey key) {
        return jpa.findBySkuIdAndWarehouseId(key.skuId(), key.warehouseId())
                .map(mapper::toDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public List<AvailableStock> findAvailableBySkuIds(Collection<String> skuIds) {
        return jpa.findAvailableBySkuIds(skuIds).stream()
            .map(p -> new AvailableStock(p.skuId(), p.productId(), p.warehouseId(), p.available()))
            .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public Map<String, Long> sumAvailableBySkuIds(Collection<String> skuIds) {
        return jpa.sumAvailableBySkuIds(skuIds);
    }

    @Override
    @Transactional
    public PhysicalInventory upsert(SkuWarehouseKey key, String productId, int actualQtyDelta) {
        Optional<PhysicalInventoryJpaEntity> existing =
            jpa.findBySkuIdAndWarehouseId(key.skuId(), key.warehouseId());

        if (existing.isPresent()) {
            PhysicalInventory domain = mapper.toDomain(existing.get());
            domain.addActualQuantity(actualQtyDelta);
            PhysicalInventoryJpaEntity updated = mapper.toJpa(domain);
            return mapper.toDomain(jpa.save(updated));
        }
        PhysicalInventory newDomain = PhysicalInventory.forUpsert(key, productId, actualQtyDelta);
        return mapper.toDomain(jpa.save(mapper.toJpa(newDomain)));
    }

    @Override
    @Transactional
    public void adjustReserved(SkuWarehouseKey key, int delta) {
        jpa.bulkAdjustReserved(List.of(
            new ReservedAdjustment(key.skuId(), key.warehouseId(), delta)
        ));
    }

    @Override
    @Transactional
    public void adjustActual(SkuWarehouseKey key, int delta) {
        upsert(key, null, delta);
    }

    @Override
    @Transactional
    public int applyDeduct(SkuWarehouseKey key, int qty) {
        PhysicalInventoryJpaEntity existing = jpa.findBySkuIdAndWarehouseId(key.skuId(), key.warehouseId())
                .orElseThrow(() -> new IllegalStateException("Physical inventory not found for deduct: " + key));
        PhysicalInventory domain = mapper.toDomain(existing);
        domain.applyDeduct(qty);
        jpa.save(mapper.toJpa(domain));
        return domain.getActualQuantity();
    }
}
