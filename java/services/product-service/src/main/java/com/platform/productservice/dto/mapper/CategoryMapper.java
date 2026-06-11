package com.platform.productservice.dto.mapper;

import com.platform.productservice.domain.entity.Category;
import com.platform.productservice.domain.vo.CategoryId;
import com.platform.productservice.domain.vo.Slug;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Mapper for converting between Category domain entities and DTOs.
 * Note: This is a manual implementation following the existing codebase pattern.
 */
@Component
public class CategoryMapper {

    /**
     * Converts a create category request to a Category domain entity.
     * Generates a new CategoryId and initializes timestamps.
     */
    public Category toDomain(CreateCategoryRequest request) {
        if (request == null) return null;

        Category category = new Category();
        category.setName(request.name());
        category.setSlug(request.slug() != null ? Slug.of(request.slug()) : null);
        category.setParentId(request.parentId() != null ? CategoryId.of(request.parentId()) : null);
        category.setLevel(request.level());
        category.setLeaf(true);
        category.initialize();
        return category;
    }

    /**
     * Converts an update category request to update a Category domain entity.
     * Does not modify ID or timestamps.
     */
    public void toDomainUpdate(UpdateCategoryRequest request, Category category) {
        if (request == null || category == null) return;

        if (request.name() != null) category.rename(request.name());
        if (request.slug() != null) category.setSlug(Slug.of(request.slug()));
        if (request.parentId() != null) category.setParentId(CategoryId.of(request.parentId()));
        category.setUpdatedAt(java.time.LocalDateTime.now());
    }

    /**
     * Converts a Category domain entity to a response DTO.
     */
    public CategoryResponse toResponse(Category category) {
        if (category == null) return null;

        return new CategoryResponse(
                category.getId() != null ? category.getId().getValue() : null,
                category.getParentId() != null ? category.getParentId().getValue() : null,
                category.getName(),
                category.getSlug() != null ? category.getSlug().getValue() : null,
                category.getLevel(),
                category.isLeaf(),
                category.getCreatedAt(),
                category.getUpdatedAt()
        );
    }

    /**
     * Converts a Category domain entity to a tree response DTO with children.
     */
    public CategoryTreeResponse toTreeResponse(Category category) {
        if (category == null) return null;

        return new CategoryTreeResponse(
                category.getId() != null ? category.getId().getValue() : null,
                category.getParentId() != null ? category.getParentId().getValue() : null,
                category.getName(),
                category.getSlug() != null ? category.getSlug().getValue() : null,
                category.getLevel(),
                category.isLeaf(),
                List.of()
        );
    }

    /**
     * Create category request DTO.
     */
    public record CreateCategoryRequest(
            String name,
            String slug,
            String parentId,
            int level
    ) {}

    /**
     * Update category request DTO.
     */
    public record UpdateCategoryRequest(
            String name,
            String slug,
            String parentId
    ) {}

    /**
     * Category response DTO.
     */
    public record CategoryResponse(
            String id,
            String parentId,
            String name,
            String slug,
            int level,
            boolean isLeaf,
            java.time.LocalDateTime createdAt,
            java.time.LocalDateTime updatedAt
    ) {}

    /**
     * Category tree response DTO with nested children.
     */
    public record CategoryTreeResponse(
            String id,
            String parentId,
            String name,
            String slug,
            int level,
            boolean isLeaf,
            List<CategoryTreeResponse> children
    ) {}
}
