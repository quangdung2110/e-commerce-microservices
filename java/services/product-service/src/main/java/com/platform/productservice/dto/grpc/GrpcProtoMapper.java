package com.platform.productservice.dto.grpc;

import com.platform.productservice.domain.entity.Category;
import com.platform.productservice.domain.entity.Product;
import com.platform.productservice.domain.entity.Sku;
import com.platform.productservice.infrastructure.redis.cache.model.SkuCacheEntry;
import com.platform.productservice.proto.CategoryNode;
import com.platform.productservice.proto.CategoryTreeSnapshotResponse;
import com.platform.productservice.proto.ProductSkuResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Maps between domain objects / cache entries and generated protobuf messages.
 */
@Component
public class GrpcProtoMapper {

    private static final Logger log = LoggerFactory.getLogger(GrpcProtoMapper.class);

    public ProductSkuResponse toProto(SkuCacheEntry cacheEntry) {
        if (cacheEntry == null) {
            return ProductSkuResponse.getDefaultInstance();
        }

        ProductSkuResponse.Builder builder = ProductSkuResponse.newBuilder()
                .setSkuId(cacheEntry.getSkuId() != null ? cacheEntry.getSkuId() : "")
                .setProductId(cacheEntry.getProductId() != null ? cacheEntry.getProductId() : "")
                .setSkuCode(cacheEntry.getSkuCode() != null ? cacheEntry.getSkuCode() : "")
                .setProductName(cacheEntry.getProductName() != null ? cacheEntry.getProductName() : "")
                .setMainImageUrl(cacheEntry.getMainImageUrl() != null ? cacheEntry.getMainImageUrl() : "")
                .setOriginalPrice(cacheEntry.getOriginalPrice() != null ? cacheEntry.getOriginalPrice().doubleValue() : 0.0)
                .setPromotionalPrice(cacheEntry.getPromotionalPrice() != null ? cacheEntry.getPromotionalPrice().doubleValue() : 0.0)
                .setBestPrice(cacheEntry.getBestPrice() != null ? cacheEntry.getBestPrice().doubleValue() : 0.0)
                .setHasPromotion(cacheEntry.isHasPromotion())
                .setSkuStatus(cacheEntry.getSkuStatus() != null ? cacheEntry.getSkuStatus() : "")
                .setProductStatus(cacheEntry.getProductStatus() != null ? cacheEntry.getProductStatus() : "")
                .setIsAvailable(cacheEntry.isAvailable())
                .setIsDeleted(cacheEntry.isDeleted())
                .setDisplayQuantity(cacheEntry.getDisplayQuantity())
                .setLastInventorySyncAt(cacheEntry.getLastInventorySyncAt());

        if (cacheEntry.getVariantAttributes() != null) {
            builder.putAllVariantAttributes(cacheEntry.getVariantAttributes());
        }

        return builder.build();
    }

    public ProductSkuResponse toProto(Sku sku, Product product) {
        if (sku == null) {
            return ProductSkuResponse.getDefaultInstance();
        }

        ProductSkuResponse.Builder builder = ProductSkuResponse.newBuilder()
                .setSkuId(sku.getId() != null ? sku.getId().getValue() : "")
                .setProductId(sku.getProductId() != null ? sku.getProductId().getValue() : "")
                .setSkuCode(sku.getSkuCode() != null ? sku.getSkuCode() : "")
                .setProductName(product != null ? product.getName() : "")
                .setMainImageUrl("")
                .setOriginalPrice(sku.getOriginalPrice() != null ? sku.getOriginalPrice().doubleValue() : 0.0)
                .setPromotionalPrice(sku.getPromotionalPrice() != null ? sku.getPromotionalPrice().doubleValue() : 0.0)
                .setBestPrice(sku.getBestPrice() != null ? sku.getBestPrice().doubleValue() : 0.0)
                .setHasPromotion(sku.hasPromotion())
                .setSkuStatus(sku.getStatus() != null ? sku.getStatus().name() : "")
                .setProductStatus(product != null && product.getStatus() != null ? product.getStatus().name() : "")
                .setIsAvailable(sku.isAvailable())
                .setIsDeleted(sku.isDeleted() || (product != null && product.isDeleted()))
                .setDisplayQuantity(sku.getDisplayQuantity() != null ? sku.getDisplayQuantity() : 0)
                .setLastInventorySyncAt(sku.getLastInventorySyncAt());

        if (sku.getVariantAttributes() != null) {
            Map<String, String> stringAttrs = sku.getVariantAttributes().entrySet().stream()
                    .collect(Collectors.toMap(
                            Map.Entry::getKey,
                            e -> e.getValue() != null ? String.valueOf(e.getValue()) : ""
                    ));
            builder.putAllVariantAttributes(stringAttrs);
        }

        return builder.build();
    }

    public CategoryNode toProto(Category category) {
        if (category == null) {
            return CategoryNode.getDefaultInstance();
        }

        return CategoryNode.newBuilder()
                .setCategoryId(category.getId() != null ? category.getId().getValue() : "")
                .setParentId(category.getParentId() != null ? category.getParentId().getValue() : "")
                .setName(category.getName() != null ? category.getName() : "")
                .setSlug(category.getSlug() != null ? category.getSlug() : "")
                .setLevel(category.getLevel())
                .setIsLeaf(category.isLeaf())
                .build();
    }

    public List<CategoryNode> toProtoList(List<Category> categories) {
        if (categories == null || categories.isEmpty()) {
            return List.of();
        }

        return categories.stream()
                .map(this::toProto)
                .collect(Collectors.toList());
    }

    public CategoryTreeSnapshotResponse toCategoryTreeProto(List<Category> rootCategories, Map<String, List<Category>> childrenMap) {
        CategoryTreeSnapshotResponse.Builder builder = CategoryTreeSnapshotResponse.newBuilder()
                .setSnapshotTimestamp(System.currentTimeMillis());

        for (Category root : rootCategories) {
            CategoryNode node = buildCategoryNodeRecursive(root, childrenMap);
            builder.addRootCategories(node);
        }

        return builder.build();
    }

    private CategoryNode buildCategoryNodeRecursive(Category category, Map<String, List<Category>> childrenMap) {
        CategoryNode.Builder builder = CategoryNode.newBuilder()
                .setCategoryId(category.getId() != null ? category.getId().getValue() : "")
                .setParentId(category.getParentId() != null ? category.getParentId().getValue() : "")
                .setName(category.getName() != null ? category.getName() : "")
                .setSlug(category.getSlug() != null ? category.getSlug() : "")
                .setLevel(category.getLevel())
                .setIsLeaf(category.isLeaf());

        String categoryId = category.getId() != null ? category.getId().getValue() : "";
        List<Category> children = childrenMap.get(categoryId);
        if (children != null && !children.isEmpty()) {
            for (Category child : children) {
                CategoryNode childNode = buildCategoryNodeRecursive(child, childrenMap);
                builder.addChildren(childNode);
            }
        }

        return builder.build();
    }
}
