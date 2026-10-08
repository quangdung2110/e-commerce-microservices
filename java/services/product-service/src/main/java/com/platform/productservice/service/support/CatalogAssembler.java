package com.platform.productservice.service.support;

import com.platform.productservice.domain.FacetDefinition;
import com.platform.productservice.domain.entity.AttributeEntity;
import com.platform.productservice.domain.entity.AttributeOptionEntity;
import com.platform.productservice.domain.entity.CategoryAttributeEntity;
import com.platform.productservice.domain.entity.CategoryEntity;
import com.platform.productservice.domain.entity.ProductEntity;
import com.platform.productservice.domain.entity.ProductMediaEntity;
import com.platform.productservice.domain.entity.SkuEntity;
import com.platform.productservice.domain.enums.MediaType;
import com.platform.productservice.web.dto.AttributeDtos;
import com.platform.productservice.web.dto.CategoryDtos;
import com.platform.productservice.web.dto.ProductDtos;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Predicate;

/** Chuyển entity sang DTO HTTP và sang media list dùng cho catalog event. */
@Component
public class CatalogAssembler {

    /** Trùng maxItems của sku.media trong catalog-common.schema.json. */
    private static final int MAX_EVENT_MEDIA = 100;

    private static final Comparator<ProductMediaEntity> MEDIA_ORDER =
            Comparator.comparingInt(ProductMediaEntity::getSortOrder)
                    .thenComparing(ProductMediaEntity::getMediaId);

    public ProductDtos.ProductResponse toProduct(ProductEntity product,
                                                 List<SkuEntity> skus,
                                                 List<ProductMediaEntity> media) {
        List<ProductDtos.MediaResponse> productMedia = media.stream()
                .filter(item -> item.getSkuId() == null)
                .sorted(MEDIA_ORDER)
                .map(this::toMedia)
                .toList();
        List<ProductDtos.SkuResponse> skuResponses = skus.stream()
                .map(sku -> toSku(sku, media))
                .toList();
        return new ProductDtos.ProductResponse(
                product.getProductId(),
                product.getCategoryId(),
                product.getName(),
                product.getSlug(),
                product.getDescription(),
                product.getAttributes(),
                product.getStatus(),
                product.getCatalogRevision(),
                productMedia,
                skuResponses,
                product.getCreatedAt(),
                product.getUpdatedAt());
    }

    public ProductDtos.SkuResponse toSku(SkuEntity sku, List<ProductMediaEntity> media) {
        List<ProductDtos.MediaResponse> skuMedia = media.stream()
                .filter(item -> sku.getSkuId().equals(item.getSkuId()))
                .sorted(MEDIA_ORDER)
                .map(this::toMedia)
                .toList();
        return new ProductDtos.SkuResponse(
                sku.getSkuId(),
                sku.getProductId(),
                sku.getSkuCode(),
                sku.getBasePriceVnd(),
                sku.getSellingPriceVnd(),
                sku.getStatus(),
                sku.getVariantAttributes(),
                skuMedia,
                effectiveThumbnail(sku.getSkuId(), media),
                sku.getCreatedAt(),
                sku.getUpdatedAt());
    }

    /**
     * Thumbnail hiệu lực của một SKU (PRODUCT-BR-012, ADR-013): {@code IMAGE} của
     * chính SKU có {@code sort_order} nhỏ nhất, fallback {@code IMAGE} cấp Product
     * có {@code sort_order} nhỏ nhất, còn lại {@code null}. {@code VIDEO} không bao
     * giờ được chọn và {@code null} là giá trị hợp lệ, không phải lỗi contract.
     *
     * <p>Product sở hữu rule này nên phát cùng giá trị ở cả catalog event lẫn
     * export HTTP; consumer không được tự derive lại từ {@code media[]}.
     *
     * @param media toàn bộ media của Product, không phải kết quả {@link #eventMedia}
     *              — list đó đã cắt còn {@value #MAX_EVENT_MEDIA} phần tử, nên một
     *              SKU nhiều media riêng sẽ đẩy media cấp Product ra ngoài và làm
     *              mất nhánh fallback
     */
    public String effectiveThumbnail(UUID skuId, List<ProductMediaEntity> media) {
        return firstImage(media, item -> skuId.equals(item.getSkuId()))
                .or(() -> firstImage(media, item -> item.getSkuId() == null))
                .map(ProductMediaEntity::getStorageKey)
                .orElse(null);
    }

    private Optional<ProductMediaEntity> firstImage(List<ProductMediaEntity> media,
                                                    Predicate<ProductMediaEntity> scope) {
        return media.stream()
                .filter(item -> item.getMediaType() == MediaType.IMAGE)
                .filter(scope)
                .min(MEDIA_ORDER);
    }

    public ProductDtos.MediaResponse toMedia(ProductMediaEntity media) {
        return new ProductDtos.MediaResponse(
                media.getMediaId(),
                media.getProductId(),
                media.getSkuId(),
                media.getMediaType(),
                media.getStorageKey(),
                media.getAltText(),
                media.getSortOrder(),
                media.getCreatedAt(),
                media.getUpdatedAt());
    }

    public ProductDtos.ProductSummaryResponse toProductSummary(ProductEntity product) {
        return new ProductDtos.ProductSummaryResponse(
                product.getProductId(),
                product.getCategoryId(),
                product.getName(),
                product.getSlug(),
                product.getStatus(),
                product.getCatalogRevision(),
                product.getUpdatedAt());
    }

    /**
     * Media đi kèm một SKU snapshot: media của chính SKU trước, rồi media cấp
     * Product.
     *
     * <p>catalog-common.schema.json chỉ có chỗ cho media ở cấp SKU, nên gallery
     * cấp Product được gửi kèm từng SKU để Search dựng document mà không cần gọi
     * ngược lại Product.
     */
    public List<ProductMediaEntity> eventMedia(UUID skuId, List<ProductMediaEntity> media) {
        List<ProductMediaEntity> own = media.stream()
                .filter(item -> skuId.equals(item.getSkuId()))
                .sorted(MEDIA_ORDER)
                .toList();
        List<ProductMediaEntity> shared = media.stream()
                .filter(item -> item.getSkuId() == null)
                .sorted(MEDIA_ORDER)
                .toList();
        return java.util.stream.Stream.concat(own.stream(), shared.stream())
                .limit(MAX_EVENT_MEDIA)
                .toList();
    }

    public CategoryDtos.CategoryResponse toCategory(CategoryEntity category,
                                                    List<CategoryDtos.CategoryAttributeResponse> attributes) {
        return new CategoryDtos.CategoryResponse(
                category.getCategoryId(),
                category.getParentId(),
                category.getName(),
                category.getSlug(),
                category.getCategoryRevision(),
                attributes,
                category.getCreatedAt(),
                category.getUpdatedAt());
    }

    public CategoryDtos.CategoryAttributeResponse toCategoryAttribute(CategoryAttributeEntity mapping,
                                                                      AttributeEntity attribute) {
        return new CategoryDtos.CategoryAttributeResponse(
                mapping.getId().getCategoryId(),
                mapping.getId().getAttributeId(),
                attribute.getCode(),
                attribute.getName(),
                attribute.getValueType(),
                mapping.isRequired(),
                mapping.isVariant());
    }

    public List<CategoryDtos.CategoryPathSegment> toPath(List<CategoryEntity> path) {
        return path.stream()
                .map(segment -> new CategoryDtos.CategoryPathSegment(
                        segment.getCategoryId(), segment.getName(), segment.getSlug()))
                .toList();
    }

    public AttributeDtos.AttributeResponse toAttribute(AttributeEntity attribute,
                                                       List<AttributeOptionEntity> options) {
        return new AttributeDtos.AttributeResponse(
                attribute.getAttributeId(),
                attribute.getCode(),
                attribute.getName(),
                attribute.getValueType(),
                options.stream().map(this::toAttributeOption).toList(),
                attribute.getCreatedAt(),
                attribute.getUpdatedAt());
    }

    public AttributeDtos.AttributeOptionResponse toAttributeOption(AttributeOptionEntity option) {
        return new AttributeDtos.AttributeOptionResponse(
                option.getOptionId(),
                option.getAttributeId(),
                option.getCode(),
                option.getLabel(),
                option.getSortOrder(),
                option.getCreatedAt(),
                option.getUpdatedAt());
    }

    public CategoryDtos.CategoryExportResponse toCategoryExport(CategoryEntity category,
                                                                List<CategoryEntity> path,
                                                                boolean leaf,
                                                                List<FacetDefinition> facetDefinitions) {
        return new CategoryDtos.CategoryExportResponse(
                category.getCategoryId(),
                category.getParentId(),
                category.getName(),
                category.getSlug(),
                category.getCategoryRevision(),
                toPath(path),
                leaf,
                facetDefinitions.stream()
                        .map(definition -> new CategoryDtos.FacetDefinitionResponse(
                                definition.code(), definition.label(), definition.type()))
                        .toList(),
                category.getUpdatedAt());
    }

    /** Nhóm media theo product_id cho luồng export nhiều Product. */
    public Map<UUID, List<ProductMediaEntity>> groupMediaByProduct(List<ProductMediaEntity> media) {
        return media.stream().collect(
                java.util.stream.Collectors.groupingBy(ProductMediaEntity::getProductId));
    }
}
