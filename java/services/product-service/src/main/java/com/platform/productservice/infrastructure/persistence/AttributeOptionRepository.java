package com.platform.productservice.infrastructure.persistence;

import com.platform.productservice.domain.entity.AttributeOptionEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface AttributeOptionRepository extends JpaRepository<AttributeOptionEntity, UUID> {

    List<AttributeOptionEntity> findByAttributeIdOrderBySortOrderAscCodeAsc(UUID attributeId);

    List<AttributeOptionEntity> findByAttributeIdInOrderBySortOrderAscCodeAsc(Collection<UUID> attributeIds);

    boolean existsByAttributeIdAndCode(UUID attributeId, String code);

    boolean existsByAttributeIdAndCodeAndOptionIdNot(UUID attributeId, String code, UUID optionId);

    boolean existsByAttributeId(UUID attributeId);
}
