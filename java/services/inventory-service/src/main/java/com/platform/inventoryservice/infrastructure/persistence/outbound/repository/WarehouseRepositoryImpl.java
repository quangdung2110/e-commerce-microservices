package com.platform.inventoryservice.infrastructure.persistence.outbound.repository;

import com.platform.inventoryservice.domain.entity.Warehouse;
import com.platform.inventoryservice.infrastructure.persistence.jpa.spring.WarehouseJpaRepository;
import com.platform.inventoryservice.infrastructure.persistence.outbound.mapper.WarehouseMapper;
import com.platform.inventoryservice.port.outbound.database.WarehouseRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Repository
@RequiredArgsConstructor
public class WarehouseRepositoryImpl implements WarehouseRepository {

    private final WarehouseJpaRepository jpa;
    private final WarehouseMapper mapper;

    @Override
    @Transactional(readOnly = true)
    public Optional<Warehouse> findById(long id) {
        return jpa.findByIdAndActiveTrue(id).map(mapper::toDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public List<Warehouse> findActiveByCityCode(String cityCode) {
        return jpa.findByCityCodeAndActiveTrue(cityCode).stream()
            .map(mapper::toDomain).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<Warehouse> findByIds(java.util.Collection<Long> ids) {
        return jpa.findByIdIn(ids).stream()
            .map(mapper::toDomain).toList();
    }
}
