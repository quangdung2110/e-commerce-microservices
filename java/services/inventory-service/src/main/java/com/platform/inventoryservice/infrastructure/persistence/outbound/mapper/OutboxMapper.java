package com.platform.inventoryservice.infrastructure.persistence.outbound.mapper;

import com.platform.inventoryservice.domain.entity.OutboxEvent;
import com.platform.inventoryservice.infrastructure.persistence.jpa.entity.OutboxJpaEntity;
import org.mapstruct.Mapper;
import org.mapstruct.ReportingPolicy;

@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.IGNORE)
public interface OutboxMapper {
    OutboxEvent toDomain(OutboxJpaEntity entity);
    OutboxJpaEntity toJpa(OutboxEvent domain);
}
