package com.platform.inventoryservice.infrastructure.persistence.outbound.mapper;

import com.platform.inventoryservice.domain.entity.InventoryLedgerEntry;
import com.platform.inventoryservice.infrastructure.persistence.jpa.entity.InventoryLedgerJpaEntity;
import org.mapstruct.Mapper;
import org.mapstruct.ReportingPolicy;

@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.IGNORE)
public interface InventoryLedgerMapper {
    InventoryLedgerEntry toDomain(InventoryLedgerJpaEntity entity);
    InventoryLedgerJpaEntity toJpa(InventoryLedgerEntry domain);
}
