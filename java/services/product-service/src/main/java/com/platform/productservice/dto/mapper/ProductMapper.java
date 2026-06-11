package com.platform.productservice.dto.mapper;

import com.platform.productservice.domain.entity.Category;
import com.platform.productservice.domain.entity.Product;
import com.platform.productservice.domain.entity.ProductMedia;
import com.platform.productservice.domain.entity.Sku;
import com.platform.productservice.domain.vo.CategoryId;
import com.platform.productservice.domain.vo.Slug;
import com.platform.productservice.dto.request.CreateProductRequest;
import com.platform.productservice.dto.request.CreateSkuRequest;
import com.platform.productservice.dto.request.UpdateProductRequest;
import com.platform.productservice.dto.response.CategoryResponse;
import com.platform.productservice.dto.response.MediaResponse;
import com.platform.productservice.dto.response.ProductResponse;
import com.platform.productservice.dto.response.SkuResponse;
import com.platform.productservice.port.outbound.database.CategoryRepository;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Mapper for converting between Product domain entities and DTOs.
 */
@Component
public class ProductMapper {

    private final CategoryRepository categoryRepository;
    private final SkuMapper skuMapper;

    public ProductMapper(CategoryRepository categoryRepository, SkuMapper skuMapper) {
        this.categoryRepository = categoryRepository;
        this.skuMapper = skuMapper;
    }

    public Product toDomain(CreateProductRequest request) {
        if (request == null) return null;

        Product product = new Product();
        product.setName(request.name());
        if (request.slug() != null && !request.slug().isBlank()) {
            product.setSlug(Slug.of(request.slug()));
        }
        product.setDescription(request.description());
        if (request.categoryId() != null && !request.categoryId().isBlank()) {
            product.setCategoryId(CategoryId.of(request.categoryId()));
        }
        product.setAttributes(request.attributes() != null ? request.attributes() : new HashMap<>());
        product.setSkus(toSkuList(request.skus(), product));
        product.setMedia(toMediaList(request.media()));
        return product;
    }

    public void toDomainUpdate(UpdateProductRequest request, Product product) {
        if (request == null || product == null) return;

        if (request.name() != null) product.setName(request.name());
        if (request.slug() != null && !request.slug().isBlank()) {
            product.setSlug(Slug.of(request.slug()));
        }
        if (request.description() != null) product.setDescription(request.description());
        if (request.categoryId() != null && !request.categoryId().isBlank()) {
            product.setCategoryId(CategoryId.of(request.categoryId()));
        }
        if (request.attributes() != null) product.setAttributes(request.attributes());
        product.setUpdatedAt(java.time.LocalDateTime.now());
    }

    /**
     * Converts a Product domain entity to a full response DTO including SKUs, media, and category name.
     */
    public ProductResponse toResponse(Product product) {
        if (product == null) return null;

        String categoryName = null;
        if (product.getCategoryId() != null) {
            categoryName = categoryRepository.findById(product.getCategoryId())
                    .map(Category::getName)
                    .orElse(null);
        }

        List<SkuResponse> skuResponses = null;
        if (product.getSkus() != null) {
            skuResponses = product.getSkus().stream()
                    .filter(sku -> !sku.isDeleted())
                    .map(skuMapper::toResponse)
                    .collect(Collectors.toList());
        }

        List<MediaResponse> mediaResponses = null;
        if (product.getMedia() != null) {
            mediaResponses = product.getMedia().stream()
                    .map(this::toMediaResponse)
                    .collect(Collectors.toList());
        }

        return new ProductResponse(
                product.getId() != null ? product.getId().getValue() : null,
                product.getName(),
                product.getSlug() != null ? product.getSlug().getValue() : null,
                product.getDescription(),
                product.getCategoryId() != null ? product.getCategoryId().getValue() : null,
                categoryName,
                product.getStatus() != null ? product.getStatus().name() : null,
                product.isDeleted(),
                product.isAdminHidden(),
                product.getAttributes(),
                skuResponses,
                mediaResponses,
                product.getCreatedAt(),
                product.getUpdatedAt()
        );
    }

    /**
     * Converts a Product domain entity to a summary response DTO (without SKUs/media).
     */
    public ProductResponse toSummaryResponse(Product product) {
        if (product == null) return null;

        String categoryName = null;
        if (product.getCategoryId() != null) {
            categoryName = categoryRepository.findById(product.getCategoryId())
                    .map(Category::getName)
                    .orElse(null);
        }

        return new ProductResponse(
                product.getId() != null ? product.getId().getValue() : null,
                product.getName(),
                product.getSlug() != null ? product.getSlug().getValue() : null,
                product.getDescription(),
                product.getCategoryId() != null ? product.getCategoryId().getValue() : null,
                categoryName,
                product.getStatus() != null ? product.getStatus().name() : null,
                product.isDeleted(),
                product.isAdminHidden(),
                product.getAttributes(),
                null,
                null,
                product.getCreatedAt(),
                product.getUpdatedAt()
        );
    }

    private List<Sku> toSkuList(List<CreateSkuRequest> requests, Product product) {
        if (requests == null) return new ArrayList<>();

        List<Sku> skus = new ArrayList<>();
        for (CreateSkuRequest req : requests) {
            Sku sku = skuMapper.toDomain(req, product.getId() != null ? product.getId().getValue() : null);
            sku.setMedia(toMediaList(req.media()));
            skus.add(sku);
        }
        return skus;
    }

    private List<ProductMedia> toMediaList(List<com.platform.productservice.dto.request.CreateMediaRequest> requests) {
        if (requests == null) return new ArrayList<>();

        List<ProductMedia> mediaList = new ArrayList<>();
        for (var req : requests) {
            ProductMedia media = new ProductMedia();
            media.setMediaType(com.platform.productservice.domain.enums.MediaType.valueOf(req.mediaType()));
            media.setUrl(req.url());
            media.setThumbnailUrl(req.thumbnailUrl());
            media.setSortOrder(req.sortOrder() != null ? req.sortOrder() : 0);
            media.initialize();
            mediaList.add(media);
        }
        return mediaList;
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
}
