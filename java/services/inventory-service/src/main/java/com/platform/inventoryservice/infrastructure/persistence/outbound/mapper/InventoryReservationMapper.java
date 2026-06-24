package com.platform.inventoryservice.infrastructure.persistence.outbound.mapper;

import com.platform.inventoryservice.domain.entity.InventoryReservation;
import com.platform.inventoryservice.infrastructure.persistence.jpa.entity.InventoryReservationJpaEntity;
import org.mapstruct.Mapper;
import org.mapstruct.ReportingPolicy;

@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.IGNORE)
public interface InventoryReservationMapper {
    InventoryReservation toDomain(InventoryReservationJpaEntity entity);
    InventoryReservationJpaEntity toJpa(InventoryReservation domain);
}
