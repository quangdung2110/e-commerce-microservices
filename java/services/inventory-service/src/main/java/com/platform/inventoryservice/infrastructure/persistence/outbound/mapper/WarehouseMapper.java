package com.platform.inventoryservice.infrastructure.persistence.outbound.mapper;

import com.platform.inventoryservice.domain.entity.Warehouse;
import com.platform.inventoryservice.infrastructure.persistence.jpa.entity.WarehouseJpaEntity;
import org.mapstruct.Mapper;
import org.mapstruct.ReportingPolicy;

@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.IGNORE)
public interface WarehouseMapper {
    Warehouse toDomain(WarehouseJpaEntity entity);
    WarehouseJpaEntity toJpa(Warehouse domain);
}
