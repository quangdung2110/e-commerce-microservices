package com.platform.inventoryservice.infrastructure.persistence.outbound.mapper;

import com.platform.inventoryservice.domain.entity.PhysicalInventory;
import com.platform.inventoryservice.domain.vo.SkuWarehouseKey;
import com.platform.inventoryservice.infrastructure.persistence.jpa.entity.PhysicalInventoryJpaEntity;
import org.mapstruct.Mapper;
import org.mapstruct.ReportingPolicy;

@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.IGNORE)
public interface PhysicalInventoryMapper {
    PhysicalInventory toDomain(PhysicalInventoryJpaEntity entity);
    PhysicalInventoryJpaEntity toJpa(PhysicalInventory domain);

    default SkuWarehouseKey toKey(PhysicalInventoryJpaEntity entity) {
        if (entity == null) {
            return null;
        }
        return new SkuWarehouseKey(entity.getSkuId(), entity.getWarehouseId());
    }
}
