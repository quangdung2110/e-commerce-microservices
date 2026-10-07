package com.ecommerce.pdp.api;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.databind.JsonNode;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/**
 * {@code ProductDetailResponse} của contracts/http/product-detail-bff/v1/openapi.yaml.
 * Field nullable vẫn được serialize thành {@code null} (Jackson inclusion {@code always}).
 */
public record ProductDetailResponse(String productId,
                                    String name,
                                    String slug,
                                    String description,
                                    long catalogRevision,
                                    Category category,
                                    List<Attribute> attributes,
                                    List<VariantAxis> variantAxes,
                                    List<Media> media,
                                    List<Sku> skus,
                                    ReviewSection reviewSection) {

    public enum AvailabilityStatus { IN_STOCK, OUT_OF_STOCK, UNKNOWN }

    public enum ReviewSectionStatus { AVAILABLE, UNAVAILABLE }

    public record CategoryRef(String categoryId, String name, String slug) {
    }

    public record Category(String categoryId, String name, String slug, List<CategoryRef> path) {
    }

    public record Attribute(String code, String name, String valueType, JsonNode value, String valueLabel) {
    }

    public record VariantOption(JsonNode value, String label) {
    }

    public record VariantAxis(String code, String name, String valueType, List<VariantOption> options) {
    }

    public record Media(String mediaId, String skuId, String mediaType, String url, String altText, int sortOrder) {
    }

    public record Sku(String skuId,
                      String skuCode,
                      Map<String, JsonNode> variantAttributes,
                      String thumbnailUrl,
                      long basePriceVnd,
                      long sellingPriceVnd,
                      AvailabilityStatus availabilityStatus,
                      OffsetDateTime availabilityObservedAt) {
    }

    public record ReviewSummary(double averageRating, long totalReviews, long totalWithMedia) {
    }

    public record ReviewPreviewItem(String reviewId,
                                    int rating,
                                    String content,
                                    String authorName,
                                    String variantName,
                                    boolean hasMedia,
                                    OffsetDateTime createdAt) {
    }

    /** {@code summary} bắt buộc khi {@code AVAILABLE}, {@code null} khi {@code UNAVAILABLE}. */
    public record ReviewSection(ReviewSectionStatus status, ReviewSummary summary, List<ReviewPreviewItem> preview) {

        public static ReviewSection unavailable() {
            return new ReviewSection(ReviewSectionStatus.UNAVAILABLE, null, List.of());
        }
    }

    /**
     * Response degrade — có SKU {@code UNKNOWN} hoặc review {@code UNAVAILABLE} — luôn
     * {@code no-store} để CDN không giữ trạng thái sự cố (ADR-004 §5).
     */
    @JsonIgnore
    public boolean degraded() {
        return reviewSection.status() == ReviewSectionStatus.UNAVAILABLE
                || skus.stream().anyMatch(sku -> sku.availabilityStatus() == AvailabilityStatus.UNKNOWN);
    }
}
