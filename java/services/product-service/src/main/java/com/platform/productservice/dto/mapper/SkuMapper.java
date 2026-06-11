package com.platform.productservice.dto.mapper;

import com.platform.productservice.domain.entity.ProductMedia;
import com.platform.productservice.domain.entity.Sku;
import com.platform.productservice.domain.vo.ProductId;
import com.platform.productservice.domain.vo.SkuId;
import com.platform.productservice.dto.request.CreateMediaRequest;
import com.platform.productservice.dto.request.CreateSkuRequest;
import com.platform.productservice.dto.request.UpdateSkuRequest;
import com.platform.productservice.dto.response.MediaResponse;
import com.platform.productservice.dto.response.SkuResponse;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Mapper for converting between Sku domain entities and DTOs.
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
        sku.setSkuCode(request.skuCode());
        sku.setOriginalPrice(request.originalPrice());
        sku.setVariantAttributes(convertVariantAttributes(request.variantAttributes()));
        sku.initialize(productId != null ? ProductId.of(productId) : null);
        return sku;
    }

    /**
     * Converts an update SKU request to update a Sku domain entity.
     * Does not modify ID, productId, or timestamps.
     */
    public void toDomainUpdate(UpdateSkuRequest request, Sku sku) {
        if (request == null || sku == null) return;

        if (request.skuCode() != null) sku.setSkuCode(request.skuCode());
        if (request.originalPrice() != null) sku.setOriginalPrice(request.originalPrice());
        if (request.variantAttributes() != null) sku.setVariantAttributes(convertVariantAttributes(request.variantAttributes()));
        sku.setUpdatedAt(java.time.LocalDateTime.now());
    }

    /**
     * Converts a Sku domain entity to a response DTO.
     */
    public SkuResponse toResponse(Sku sku) {
        if (sku == null) return null;

        List<MediaResponse> mediaResponses = null;
        if (sku.getMedia() != null) {
            mediaResponses = sku.getMedia().stream()
                    .map(this::toMediaResponse)
                    .collect(Collectors.toList());
        }

        return new SkuResponse(
                sku.getId() != null ? sku.getId().getValue() : null,
                sku.getSkuCode(),
                sku.getOriginalPrice(),
                sku.getPromotionalPrice(),
                sku.getBestPrice(),
                sku.hasPromotion(),
                sku.getStatus() != null ? sku.getStatus().name() : null,
                sku.getDisplayQuantity(),
                sku.getVariantAttributes() != null ? sku.getVariantAttributes() : new HashMap<>(),
                mediaResponses,
                sku.getCreatedAt(),
                sku.getUpdatedAt()
        );
    }

    private MediaResponse toMediaResponse(ProductMedia media) {
        if (media == null) return null;
        return new MediaResponse(
                media.getId() != null ? media.getId().getValue() : null,
                media.getSkuId() != null ? media.getSkuId().getValue() : null,
                media.getMediaType() != null ? media.getMediaType().name() : null,
                media.getUrl(),
                media.getThumbnailUrl(),
                media.getSortOrder()
        );
    }

    public List<ProductMedia> toMediaList(List<CreateMediaRequest> requests, String skuId) {
        if (requests == null) return new ArrayList<>();

        List<ProductMedia> mediaList = new ArrayList<>();
        for (CreateMediaRequest req : requests) {
            ProductMedia media = new ProductMedia();
            if (skuId != null) {
                media.setSkuId(SkuId.of(skuId));
            }
            media.setMediaType(com.platform.productservice.domain.enums.MediaType.valueOf(req.mediaType()));
            media.setUrl(req.url());
            media.setThumbnailUrl(req.thumbnailUrl());
            media.setSortOrder(req.sortOrder() != null ? req.sortOrder() : 0);
            media.initialize();
            mediaList.add(media);
        }
        return mediaList;
    }

    private Map<String, Object> convertVariantAttributes(Map<String, String> source) {
        if (source == null || source.isEmpty()) {
            return new HashMap<>();
        }
        return new HashMap<>(source);
    }
}
