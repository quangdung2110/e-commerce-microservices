package com.platform.productservice.dto.mapper;

import com.platform.productservice.domain.entity.Product;
import com.platform.productservice.domain.vo.CategoryId;
import com.platform.productservice.domain.vo.Slug;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Mapper for converting between Product domain entities and DTOs.
 * Note: This is a manual implementation following the existing codebase pattern.
 * The request/response DTOs are defined as inner records here for reference;
 * actual DTOs will be created in Module 07 (REST API).
 */
@Component
public class ProductMapper {

    /**
     * Converts a create product request to a Product domain entity.
     * Generates a new ProductId and initializes timestamps.
     */
    public Product toDomain(CreateProductRequest request) {
        if (request == null) return null;

        Product product = new Product();
        product.setName(request.name());
        product.setSlug(request.slug() != null ? Slug.of(request.slug()) : null);
        product.setDescription(request.description());
        product.setCategoryId(request.categoryId() != null ? CategoryId.of(request.categoryId()) : null);
        product.setAttributes(request.attributes() != null ? request.attributes() : Map.of());
        product.initialize();
        return product;
    }

    /**
     * Converts an update product request to update a Product domain entity.
     * Does not modify ID or status.
     */
    public void toDomainUpdate(UpdateProductRequest request, Product product) {
        if (request == null || product == null) return;

        if (request.name() != null) product.setName(request.name());
        if (request.slug() != null) product.setSlug(Slug.of(request.slug()));
        if (request.description() != null) product.setDescription(request.description());
        if (request.categoryId() != null) product.setCategoryId(CategoryId.of(request.categoryId()));
        if (request.attributes() != null) product.setAttributes(request.attributes());
        product.setUpdatedAt(java.time.LocalDateTime.now());
    }

    /**
     * Converts a Product domain entity to a response DTO.
     */
    public ProductResponse toResponse(Product product) {
        if (product == null) return null;

        return new ProductResponse(
                product.getId() != null ? product.getId().getValue() : null,
                product.getName(),
                product.getSlug() != null ? product.getSlug().getValue() : null,
                product.getDescription(),
                product.getCategoryId() != null ? product.getCategoryId().getValue() : null,
                product.getStatus() != null ? product.getStatus().name() : null,
                product.isDeleted(),
                product.isAdminHidden(),
                product.getAttributes(),
                product.getCreatedAt(),
                product.getUpdatedAt()
        );
    }

    /**
     * Create product request DTO.
     */
    public record CreateProductRequest(
            String name,
            String slug,
            String description,
            String categoryId,
            Map<String, Object> attributes
    ) {}

    /**
     * Update product request DTO.
     */
    public record UpdateProductRequest(
            String name,
            String slug,
            String description,
            String categoryId,
            Map<String, Object> attributes
    ) {}

    /**
     * Product response DTO.
     */
    public record ProductResponse(
            String id,
            String name,
            String slug,
            String description,
            String categoryId,
            String status,
            boolean isDeleted,
            boolean isAdminHidden,
            Map<String, Object> attributes,
            java.time.LocalDateTime createdAt,
            java.time.LocalDateTime updatedAt
    ) {}
}
