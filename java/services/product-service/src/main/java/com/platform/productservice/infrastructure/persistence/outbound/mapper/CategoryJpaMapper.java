package com.platform.productservice.infrastructure.persistence.outbound.mapper;

import com.platform.productservice.domain.entity.Category;
import com.platform.productservice.domain.vo.CategoryId;
import com.platform.productservice.infrastructure.persistence.entity.CategoryJpaEntity;
import org.springframework.stereotype.Component;

@Component
public class CategoryJpaMapper {

    public Category toDomain(CategoryJpaEntity entity) {
        if (entity == null) return null;

        Category category = new Category();
        category.setId(CategoryId.of(entity.getCategoryId()));
        if (entity.getParentId() != null) {
            category.setParentId(CategoryId.of(entity.getParentId()));
        }
        category.setName(entity.getName());
        category.setSlug(entity.getSlug());
        category.setLevel(entity.getLevel() != null ? entity.getLevel() : 0);
        category.setLeaf(entity.getIsLeaf() != null && entity.getIsLeaf());
        category.setCreatedAt(entity.getCreatedAt());
        category.setUpdatedAt(entity.getUpdatedAt());
        return category;
    }

    public CategoryJpaEntity toEntity(Category category) {
        if (category == null) return null;

        CategoryJpaEntity entity = new CategoryJpaEntity();
        entity.setCategoryId(category.getId() != null ? category.getId().getValue() : null);
        entity.setParentId(category.getParentId() != null ? category.getParentId().getValue() : null);
        entity.setName(category.getName());
        entity.setSlug(category.getSlug());
        entity.setLevel(category.getLevel());
        entity.setIsLeaf(category.isLeaf());
        entity.setCreatedAt(category.getCreatedAt());
        entity.setUpdatedAt(category.getUpdatedAt());
        return entity;
    }
}
