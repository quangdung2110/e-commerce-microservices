package com.ecommerce.search.worker.productcards;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * One {@code product_cards} document derived from every SKU document of a Product (ADR-027).
 *
 * <p>The nested {@code skus} are the live SKU documents verbatim, so every filter, sort and the
 * representative choice of ADR-020 keep applying to one SKU at a time. A tombstoned SKU is left
 * out, as the SKU query's {@code is_deleted = false} filter leaves it out; a Product whose SKUs are
 * all tombstoned has no card.
 *
 * @param version the newest {@code indexed_at} (epoch milliseconds) among all its SKU documents,
 *                tombstones included: written with {@code version_type=external_gte}, a rebuild
 *                that read an older state can never replace one that read a newer state
 */
public record ProductCard(String productId, long version, List<JsonNode> liveSkus) {

    public boolean deleted() {
        return liveSkus.isEmpty();
    }

    public ObjectNode document() {
        ObjectNode document = JsonNodeFactory.instance.objectNode();
        document.put("product_id", productId);
        document.put("source_version", version);
        document.putArray("skus").addAll(liveSkus);
        return document;
    }

    /** Builds the card of one Product from all of its SKU documents, in any order. */
    public static ProductCard of(String productId, List<? extends JsonNode> skuDocuments) {
        long version = 0L;
        List<JsonNode> live = new java.util.ArrayList<>();
        for (JsonNode sku : skuDocuments) {
            version = Math.max(version, epochMillis(sku.path("indexed_at").asText(null)));
            if (!sku.path("is_deleted").asBoolean(false)) {
                live.add(sku);
            }
        }
        live.sort(java.util.Comparator.comparing(sku -> sku.path("sku_id").asText("")));
        return new ProductCard(productId, version, List.copyOf(live));
    }

    /** {@code indexed_at} as epoch milliseconds; 0 when absent or unreadable, which only loses ordering. */
    public static long epochMillis(String indexedAt) {
        if (indexedAt == null || indexedAt.isBlank()) {
            return 0L;
        }
        try {
            return Instant.parse(indexedAt).toEpochMilli();
        } catch (RuntimeException notInstant) {
            try {
                return OffsetDateTime.parse(indexedAt).toInstant().toEpochMilli();
            } catch (RuntimeException unreadable) {
                return 0L;
            }
        }
    }
}
