package com.ecommerce.search.worker.productcards;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** ADR-027: a card is a pure function of a Product's SKU documents. */
class ProductCardTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void nestsTheLiveSkusInSkuIdOrderAndVersionsByTheNewestWrite() {
        ProductCard card = ProductCard.of("p-1", List.of(
                sku("sku-b", false, "2026-10-16T10:00:00.000Z"),
                sku("sku-a", false, "2026-10-16T10:00:02.500Z")));

        assertThat(card.deleted()).isFalse();
        assertThat(card.liveSkus()).extracting(node -> node.get("sku_id").asText()).containsExactly("sku-a", "sku-b");
        assertThat(card.version()).isEqualTo(java.time.Instant.parse("2026-10-16T10:00:02.500Z").toEpochMilli());
        assertThat(card.document().get("product_id").asText()).isEqualTo("p-1");
        assertThat(card.document().get("skus")).hasSize(2);
    }

    @Test
    void leavesTombstonesOutButStillVersionsByThem() {
        // The SKU query filters is_deleted = false, so a tombstone never matches; its newer write still
        // has to win over a card built before the tombstone.
        ProductCard card = ProductCard.of("p-1", List.of(
                sku("sku-a", false, "2026-10-16T10:00:00.000Z"),
                sku("sku-b", true, "2026-10-16T10:05:00.000Z")));

        assertThat(card.liveSkus()).extracting(node -> node.get("sku_id").asText()).containsExactly("sku-a");
        assertThat(card.version()).isEqualTo(java.time.Instant.parse("2026-10-16T10:05:00.000Z").toEpochMilli());
    }

    @Test
    void aProductWhoseSkusAreAllTombstonedHasNoCard() {
        ProductCard card = ProductCard.of("p-1", List.of(sku("sku-a", true, "2026-10-16T10:00:00.000Z")));

        assertThat(card.deleted()).isTrue();
    }

    @Test
    void readsOffsetTimestampsAndToleratesMissingOnes() {
        assertThat(ProductCard.epochMillis("2026-10-16T17:00:00.000+07:00"))
                .isEqualTo(java.time.Instant.parse("2026-10-16T10:00:00.000Z").toEpochMilli());
        assertThat(ProductCard.epochMillis(null)).isZero();
        assertThat(ProductCard.epochMillis("not a date")).isZero();
    }

    private static ObjectNode sku(String skuId, boolean deleted, String indexedAt) {
        ObjectNode node = JSON.createObjectNode();
        node.put("sku_id", skuId);
        node.put("product_id", "p-1");
        node.put("is_deleted", deleted);
        node.put("indexed_at", indexedAt);
        return node;
    }
}
