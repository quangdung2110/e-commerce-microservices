package com.platform.inventoryservice.infrastructure.persistence.jpa.spring;

import com.platform.inventoryservice.infrastructure.persistence.jpa.entity.WarehouseJpaEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface WarehouseJpaRepository extends JpaRepository<WarehouseJpaEntity, Long> {

    Optional<WarehouseJpaEntity> findByIdAndActiveTrue(Long id);

    List<WarehouseJpaEntity> findByCityCodeAndActiveTrue(String cityCode);

    List<WarehouseJpaEntity> findByIdIn(java.util.Collection<Long> ids);

    @Query("SELECT w.id FROM WarehouseJpaEntity w WHERE w.active = true")
    List<Long> findAllActiveIds();
}
