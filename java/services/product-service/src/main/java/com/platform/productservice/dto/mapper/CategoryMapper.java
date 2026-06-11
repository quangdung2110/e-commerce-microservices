package com.platform.productservice.dto.mapper;

import com.platform.productservice.domain.entity.Category;
import com.platform.productservice.domain.vo.CategoryId;
import com.platform.productservice.domain.vo.Slug;
import com.platform.productservice.dto.request.CreateCategoryRequest;
import com.platform.productservice.dto.request.UpdateCategoryRequest;
import com.platform.productservice.dto.response.CategoryResponse;
import com.platform.productservice.dto.response.CategoryTreeResponse;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Mapper for converting between Category domain entities and DTOs.
 */
@Component
public class CategoryMapper {

    public Category toDomain(CreateCategoryRequest request) {
        if (request == null) return null;

        Category category = new Category();
        category.setName(request.name());
        if (request.slug() != null && !request.slug().isBlank()) {
            category.setSlug(Slug.of(request.slug()));
        }
        if (request.parentId() != null && !request.parentId().isBlank()) {
            category.setParentId(CategoryId.of(request.parentId()));
        }
        if (request.isLeaf() != null) {
            category.setLeaf(request.isLeaf());
        } else {
            category.setLeaf(true);
        }
        category.initialize();
        return category;
    }

    public void toDomainUpdate(UpdateCategoryRequest request, Category category) {
        if (request == null || category == null) return;

        if (request.name() != null) category.rename(request.name());
        if (request.slug() != null && !request.slug().isBlank()) {
            category.setSlug(Slug.of(request.slug()));
        }
        if (request.parentId() != null) {
            if (request.parentId().isBlank()) {
                category.setParentId(null);
            } else {
                category.setParentId(CategoryId.of(request.parentId()));
            }
        }
        if (request.isLeaf() != null) {
            if (request.isLeaf()) {
                category.makeLeaf();
            } else {
                category.makeNonLeaf();
            }
        }
        category.setUpdatedAt(java.time.LocalDateTime.now());
    }

    public CategoryResponse toResponse(Category category) {
        if (category == null) return null;

        return new CategoryResponse(
                category.getId() != null ? category.getId().getValue() : null,
                category.getParentId() != null ? category.getParentId().getValue() : null,
                category.getName(),
                category.getSlug() != null ? category.getSlug().getValue() : null,
                category.getLevel(),
                category.isLeaf(),
                null,
                category.getCreatedAt(),
                category.getUpdatedAt()
        );
    }

    public CategoryResponse toTreeResponse(Category category) {
        if (category == null) return null;

        return new CategoryResponse(
                category.getId() != null ? category.getId().getValue() : null,
                category.getParentId() != null ? category.getParentId().getValue() : null,
                category.getName(),
                category.getSlug() != null ? category.getSlug().getValue() : null,
                category.getLevel(),
                category.isLeaf(),
                List.of(),
                category.getCreatedAt(),
                category.getUpdatedAt()
        );
    }

    public CategoryTreeResponse toTreeResponse(List<Category> roots, java.util.Map<String, java.util.List<Category>> childrenMap) {
        if (roots == null) return new CategoryTreeResponse(List.of());

        List<CategoryResponse> rootResponses = roots.stream()
                .map(c -> buildTree(c, childrenMap))
                .collect(Collectors.toList());

        return new CategoryTreeResponse(rootResponses);
    }

    private CategoryResponse buildTree(Category category, java.util.Map<String, java.util.List<Category>> childrenMap) {
        List<CategoryResponse> childResponses = null;
        if (childrenMap != null && category.getId() != null) {
            List<Category> children = childrenMap.get(category.getId().getValue());
            if (children != null && !children.isEmpty()) {
                childResponses = children.stream()
                        .map(c -> buildTree(c, childrenMap))
                        .collect(Collectors.toList());
            }
        }

        return new CategoryResponse(
                category.getId() != null ? category.getId().getValue() : null,
                category.getParentId() != null ? category.getParentId().getValue() : null,
                category.getName(),
                category.getSlug() != null ? category.getSlug().getValue() : null,
                category.getLevel(),
                category.isLeaf(),
                childResponses != null ? childResponses : List.of(),
                category.getCreatedAt(),
                category.getUpdatedAt()
        );
    }
}
