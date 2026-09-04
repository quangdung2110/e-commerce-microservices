package com.platform.productservice.infrastructure.persistence;

import com.platform.productservice.domain.entity.AttributeEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface AttributeRepository extends JpaRepository<AttributeEntity, UUID> {

    boolean existsByCode(String code);

    boolean existsByCodeAndAttributeIdNot(String code, UUID attributeId);

    List<AttributeEntity> findAllByOrderByCodeAsc();

    List<AttributeEntity> findByAttributeIdIn(Collection<UUID> attributeIds);
}
