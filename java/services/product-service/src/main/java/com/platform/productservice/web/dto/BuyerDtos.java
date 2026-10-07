package com.platform.productservice.web.dto;

import com.platform.productservice.domain.enums.AttributeValueType;
import com.platform.productservice.domain.enums.MediaType;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Projection buyer của {@code GET /internal/v1/product-details/{slug}}, ánh xạ 1-1 với
 * {@code BuyerProductDetail} trong contracts/http/product/v1/openapi.yaml (ADR-018).
 *
 * <p>Khác schema {@code Product} của admin: không có {@code status}, {@code created_at}
 * hay trạng thái SKU; category, nhãn thuộc tính và trục biến thể đã được resolve; media
 * đã cắt theo giới hạn PDP. Các field nullable vẫn được serialize thành {@code null}
 * vì contract đánh dấu chúng bắt buộc.
 */
public final class BuyerDtos {

    private BuyerDtos() {
    }

    public record BuyerProductDetail(UUID productId,
                                     String name,
                                     String slug,
                                     String description,
                                     long catalogRevision,
                                     Instant updatedAt,
                                     BuyerCategory category,
                                     List<BuyerAttributeValue> attributes,
                                     List<BuyerVariantAxis> variantAxes,
                                     List<BuyerMedia> media,
                                     List<BuyerSku> skus) {
    }

    /** {@code path} đi từ category gốc tới chính category lá của Product. */
    public record BuyerCategory(UUID categoryId,
                                String name,
                                String slug,
                                List<CategoryDtos.CategoryPathSegment> path) {
    }

    /** {@code valueLabel} là label option khi {@code SELECT}, còn lại {@code null}. */
    public record BuyerAttributeValue(String code,
                                      String name,
                                      AttributeValueType valueType,
                                      Object value,
                                      String valueLabel) {
    }

    public record BuyerVariantAxis(String code,
                                   String name,
                                   AttributeValueType valueType,
                                   List<BuyerVariantOption> options) {
    }

    /** {@code label} là label option khi trục là {@code SELECT}, còn lại {@code null}. */
    public record BuyerVariantOption(Object value, String label) {
    }

    public record BuyerMedia(UUID mediaId,
                             UUID skuId,
                             MediaType mediaType,
                             String storageKey,
                             String altText,
                             int sortOrder) {
    }

    public record BuyerSku(UUID skuId,
                           String skuCode,
                           long basePriceVnd,
                           long sellingPriceVnd,
                           Map<String, Object> variantAttributes,
                           String thumbnailStorageKey) {
    }
}
