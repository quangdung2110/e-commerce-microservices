package com.ecommerce.search.worker.handler;

import com.ecommerce.search.domain.document.CategoryDocument;
import com.ecommerce.search.domain.event.CatalogPayloads;
import com.ecommerce.search.domain.model.AttributeToken;
import com.ecommerce.search.service.DiscountCalculator;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * Transforms a Product catalog snapshot into the catalog-owned fields of a SKU document.
 *
 * <p>The returned map is exactly the {@code fields} parameter of
 * {@code catalog_upsert.painless}, whose allowlist rejects anything outside the catalog's
 * own fields — so a Product event can never overwrite stock or rating
 * ({@code SEARCH-BR-009}).
 */
public final class CatalogProjection {

    private CatalogProjection() {
    }

    /**
     * @param category the enriched category node, or empty on an enrichment miss — in
     *                 which case the derived category fields are left out and the document
     *                 is still indexed ({@code SEARCH-BR-008})
     */
    public static Map<String, Object> toFields(CatalogPayloads.ProductSnapshot product,
                                               CatalogPayloads.SkuSnapshot sku,
                                               Optional<CategoryDocument> category) {
        Map<String, Object> fields = new LinkedHashMap<>();

        fields.put("sku_id", sku.skuId());
        fields.put("sku_code", sku.skuCode());
        fields.put("product_id", product.productId());
        fields.put("product_slug", product.slug());

        fields.put("category_id", product.categoryId());
        category.ifPresent(node -> {
            fields.put("category_slug", node.slug());
            fields.put("category_name", node.name());
            fields.put("category_ancestor_ids", node.ancestorIds());
            fields.put("category_slug_path", node.ancestorSlugs());
        });

        fields.put("name", product.name());
        fields.put("description", product.description());
        fields.put("thumbnail_storage_key", sku.thumbnailStorageKey());

        fields.put("product_status", product.status());
        fields.put("sku_status", sku.status());

        TreeSet<String> productTokens = AttributeToken.encodeAll(product.attributes());
        TreeSet<String> variantTokens = AttributeToken.encodeAll(sku.variantAttributes());
        TreeSet<String> filterTokens = new TreeSet<>(productTokens);
        filterTokens.addAll(variantTokens);
        fields.put("product_attribute_tokens", productTokens.stream().toList());
        fields.put("variant_attribute_tokens", variantTokens.stream().toList());
        fields.put("filter_tokens", filterTokens.stream().toList());

        fields.put("base_price_vnd", sku.basePriceVnd());
        fields.put("selling_price_vnd", sku.sellingPriceVnd());
        fields.put("has_promotion", DiscountCalculator.hasPromotion(sku.basePriceVnd(), sku.sellingPriceVnd()));

        // The SKU timestamp is the one the buyer-facing "newest" sort reads; the product
        // timestamp only matters when the SKU carries none.
        fields.put("updated_at", com.ecommerce.search.common.Timestamps.iso(
                sku.updatedAt() != null ? sku.updatedAt() : product.updatedAt()));

        return fields;
    }

    /**
     * Rejects a Category snapshot whose {@code path} is not a usable ancestry: it must be
     * non-empty, end at the node itself and contain no repeated id. A cycle or a truncated
     * path would silently corrupt every branch filter, so the event is dropped and the
     * previous projection kept ({@code WF-SEARCH-006} failure path).
     */
    public static boolean hasValidPath(CatalogPayloads.CategorySnapshot category) {
        List<CatalogPayloads.CategoryPathSegment> path = category.path();
        if (path == null || path.isEmpty()) {
            return false;
        }
        if (!path.get(path.size() - 1).categoryId().equals(category.categoryId())) {
            return false;
        }
        Set<String> seen = new HashSet<>();
        for (CatalogPayloads.CategoryPathSegment segment : path) {
            if (segment.categoryId() == null || !seen.add(segment.categoryId())) {
                return false;
            }
        }
        return true;
    }

    /**
     * Transforms the Category snapshot Product publishes into the category-owned fields.
     *
     * <p>{@code path} is authoritative and already derived by Product; Search materialises
     * {@code ancestor_ids}, {@code ancestor_slugs} and {@code level} from it and never
     * rebuilds the tree itself (ADR-013).
     */
    public static Map<String, Object> toCategoryFields(CatalogPayloads.CategorySnapshot category) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("category_id", category.categoryId());
        fields.put("name", category.name());
        fields.put("slug", category.slug());
        fields.put("parent_id", category.parentId());

        List<CatalogPayloads.CategoryPathSegment> path =
                category.path() == null ? List.of() : category.path();
        fields.put("ancestor_ids", path.stream().map(CatalogPayloads.CategoryPathSegment::categoryId).toList());
        fields.put("ancestor_slugs", path.stream().map(CatalogPayloads.CategoryPathSegment::slug).toList());
        // path includes the node itself, so its depth below the root is size - 1.
        fields.put("level", Math.max(0, path.size() - 1));
        fields.put("is_leaf", Boolean.TRUE.equals(category.isLeaf()));
        fields.put("updated_at", com.ecommerce.search.common.Timestamps.iso(category.updatedAt()));
        return fields;
    }
}
