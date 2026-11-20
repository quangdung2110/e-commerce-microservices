package com.platform.productservice.infrastructure.persistence;

import com.platform.productservice.domain.entity.CategoryAttributeEntity;
import com.platform.productservice.domain.entity.CategoryAttributeId;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface CategoryAttributeRepository
        extends JpaRepository<CategoryAttributeEntity, CategoryAttributeId> {

    List<CategoryAttributeEntity> findByIdCategoryId(UUID categoryId);

    List<CategoryAttributeEntity> findByIdCategoryIdIn(Collection<UUID> categoryIds);

    List<CategoryAttributeEntity> findByIdAttributeId(UUID attributeId);

    boolean existsByIdAttributeId(UUID attributeId);
}
