package com.platform.productservice.dto.mapper;

import com.platform.productservice.domain.entity.Sku;
import com.platform.productservice.domain.vo.ProductId;
import com.platform.productservice.domain.vo.SkuCode;
import com.platform.productservice.domain.vo.SkuId;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Map;

/**
 * Mapper for converting between Sku domain entities and DTOs.
 * Note: This is a manual implementation following the existing codebase pattern.
 */
@Component
public class SkuMapper {

    /**
     * Converts a create SKU request to a Sku domain entity.
     * Generates a new SkuId and initializes timestamps.
     */
    public Sku toDomain(CreateSkuRequest request, String productId) {
        if (request == null) return null;

        Sku sku = new Sku();
        sku.setSkuCode(request.skuCode() != null ? SkuCode.of(request.skuCode()) : null);
        sku.setOriginalPrice(request.originalPrice());
        sku.setPromotionalPrice(request.promotionalPrice());
        sku.setDisplayQuantity(request.displayQuantity());
        sku.setVariantAttributes(request.variantAttributes() != null ? request.variantAttributes() : Map.of());
        sku.initialize(productId != null ? ProductId.of(productId) : null);
        return sku;
    }

    /**
     * Converts an update SKU request to update a Sku domain entity.
     * Does not modify ID, productId, or timestamps.
     */
    public void toDomainUpdate(UpdateSkuRequest request, Sku sku) {
        if (request == null || sku == null) return;

        if (request.skuCode() != null) sku.setSkuCode(SkuCode.of(request.skuCode()));
        if (request.originalPrice() != null) sku.setOriginalPrice(request.originalPrice());
        if (request.promotionalPrice() != null) sku.setPromotionalPrice(request.promotionalPrice());
        if (request.displayQuantity() != null) sku.setDisplayQuantity(request.displayQuantity());
        if (request.variantAttributes() != null) sku.setVariantAttributes(request.variantAttributes());
        sku.setUpdatedAt(java.time.LocalDateTime.now());
    }

    /**
     * Converts a Sku domain entity to a response DTO.
     */
    public SkuResponse toResponse(Sku sku) {
        if (sku == null) return null;

        return new SkuResponse(
                sku.getId() != null ? sku.getId().getValue() : null,
                sku.getProductId() != null ? sku.getProductId().getValue() : null,
                sku.getSkuCode(),
                sku.getOriginalPrice(),
                sku.getPromotionalPrice(),
                sku.getBestPrice(),
                sku.hasPromotion(),
                sku.getStatus() != null ? sku.getStatus().name() : null,
                sku.isDeleted(),
                sku.getDisplayQuantity(),
                sku.getVariantAttributes(),
                sku.getCreatedAt(),
                sku.getUpdatedAt()
        );
    }

    /**
     * Create SKU request DTO.
     */
    public record CreateSkuRequest(
            String skuCode,
            BigDecimal originalPrice,
            BigDecimal promotionalPrice,
            int displayQuantity,
            Map<String, Object> variantAttributes
    ) {}

    /**
     * Update SKU request DTO.
     */
    public record UpdateSkuRequest(
            String skuCode,
            BigDecimal originalPrice,
            BigDecimal promotionalPrice,
            Integer displayQuantity,
            Map<String, Object> variantAttributes
    ) {}

    /**
     * SKU response DTO.
     */
    public record SkuResponse(
            String id,
            String productId,
            String skuCode,
            BigDecimal originalPrice,
            BigDecimal promotionalPrice,
            BigDecimal bestPrice,
            boolean hasPromotion,
            String status,
            boolean isDeleted,
            int displayQuantity,
            Map<String, Object> variantAttributes,
            java.time.LocalDateTime createdAt,
            java.time.LocalDateTime updatedAt
    ) {}
}
