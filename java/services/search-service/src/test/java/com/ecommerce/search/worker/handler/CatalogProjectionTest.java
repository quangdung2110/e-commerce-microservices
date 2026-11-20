package com.ecommerce.search.worker.handler;

import com.ecommerce.search.domain.document.CategoryDocument;
import com.ecommerce.search.domain.event.CatalogPayloads;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/** Transform of a catalog snapshot into catalog-owned document fields ({@code WF-SEARCH-005}). */
class CatalogProjectionTest {

    private static final Instant NOW = Instant.parse("2026-09-06T10:15:30.123Z");

    @Test
    void writesOnlyFieldsTheCatalogUpsertScriptAllows() {
        // catalog_upsert.painless throws on anything outside this allowlist, so a field
        // added here without updating the script would fail every write at runtime.
        Map<String, Object> fields = CatalogProjection.toFields(product(), sku(), Optional.empty());

        assertThat(fields.keySet()).isSubsetOf(
                "sku_id", "sku_code", "product_id", "product_slug",
                "category_id", "category_slug", "category_name", "category_ancestor_ids", "category_slug_path",
                "name", "description", "thumbnail_storage_key",
                "product_status", "sku_status",
                "product_attribute_tokens", "variant_attribute_tokens", "filter_tokens",
                "base_price_vnd", "selling_price_vnd", "has_promotion", "updated_at");
    }

    @Test
    void neverWritesAvailabilityOrRatingFields() {
        // SEARCH-BR-009: one source may not clear another's data. The catalog upsert has
        // no exception; only the tombstone may set is_in_stock (ADR-011).
        Map<String, Object> fields = CatalogProjection.toFields(product(), sku(), Optional.empty());

        assertThat(fields).doesNotContainKeys(
                "is_in_stock", "availability_revision", "product_rating_avg", "product_rating_count");
    }

    @Test
    void stillIndexesTheDocumentWhenCategoryEnrichmentMisses() {
        // SEARCH-BR-008: a missing category must not drop the product from the index.
        Map<String, Object> fields = CatalogProjection.toFields(product(), sku(), Optional.empty());

        assertThat(fields).containsKey("sku_id");
        assertThat(fields).doesNotContainKeys("category_ancestor_ids", "category_slug_path");
    }

    @Test
    void materialisesAncestorsWhenEnrichmentHits() {
        CategoryDocument category = new CategoryDocument("cat-2", "Phones", "phones", "cat-1",
                List.of("cat-1", "cat-2"), List.of("electronics", "phones"), 1, true, null,
                10L, NOW, false, null, NOW, NOW, 3L, "evt-1");

        Map<String, Object> fields = CatalogProjection.toFields(product(), sku(), Optional.of(category));

        assertThat(fields).containsEntry("category_ancestor_ids", List.of("cat-1", "cat-2"));
        assertThat(fields).containsEntry("category_slug_path", List.of("electronics", "phones"));
    }

    @Test
    void unionsProductAndVariantTokensIntoFilterTokens() {
        Map<String, Object> fields = CatalogProjection.toFields(product(), sku(), Optional.empty());

        assertThat(fields).containsEntry("product_attribute_tokens", List.of("brand:s:apple"));
        assertThat(fields).containsEntry("variant_attribute_tokens", List.of("color:s:black"));
        assertThat(fields).containsEntry("filter_tokens", List.of("brand:s:apple", "color:s:black"));
    }

    @Test
    void derivesHasPromotionFromThePublishedPrices() {
        assertThat(CatalogProjection.toFields(product(), sku(), Optional.empty()))
                .containsEntry("has_promotion", true);
    }

    @Test
    void copiesTheThumbnailProductAlreadyResolvedIncludingNull() {
        CatalogPayloads.SkuSnapshot withoutImage = new CatalogPayloads.SkuSnapshot(
                "sku-1", "SKU-1", 1000L, 900L, "ACTIVE", Map.of(), null, NOW);

        assertThat(CatalogProjection.toFields(product(), withoutImage, Optional.empty()))
                .containsEntry("thumbnail_storage_key", null);
    }

    @Test
    void acceptsAPathThatEndsAtTheNodeItself() {
        assertThat(CatalogProjection.hasValidPath(category(List.of(segment("cat-1"), segment("cat-2"))))).isTrue();
    }

    @Test
    void rejectsACyclicOrTruncatedPath() {
        assertThat(CatalogProjection.hasValidPath(category(List.of(segment("cat-2"), segment("cat-2"))))).isFalse();
        assertThat(CatalogProjection.hasValidPath(category(List.of(segment("cat-1"))))).isFalse();
        assertThat(CatalogProjection.hasValidPath(category(List.of()))).isFalse();
    }

    @Test
    void derivesLevelFromTheDepthOfThePath() {
        Map<String, Object> fields = CatalogProjection.toCategoryFields(
                category(List.of(segment("cat-0"), segment("cat-1"), segment("cat-2"))));

        assertThat(fields).containsEntry("level", 2);
        assertThat(fields).containsEntry("ancestor_ids", List.of("cat-0", "cat-1", "cat-2"));
    }

    @Test
    void materialisesFacetDefinitionsAsDescriptorMaps() {
        Map<String, Object> fields = CatalogProjection.toCategoryFields(categoryWith(List.of(
                new CatalogPayloads.FacetDefinition("brand", "Thương hiệu", "TEXT"),
                new CatalogPayloads.FacetDefinition("color", "Màu sắc", "SELECT"))));

        assertThat(fields).containsEntry("facet_definitions", List.of(
                Map.of("code", "brand", "label", "Thương hiệu", "type", "TEXT"),
                Map.of("code", "color", "label", "Màu sắc", "type", "SELECT")));
    }

    @Test
    void anEmptyDescriptorListIsWrittenSoItReplacesThePreviousOne() {
        assertThat(CatalogProjection.toCategoryFields(categoryWith(List.of())))
                .containsEntry("facet_definitions", List.of());
    }

    @Test
    void aSnapshotFromAProducerWithoutDescriptorsLeavesTheFieldUntouched() {
        assertThat(CatalogProjection.toCategoryFields(categoryWith(null))).doesNotContainKey("facet_definitions");
    }

    @Test
    void dropsADescriptorMissingItsCodeOrLabel() {
        Map<String, Object> fields = CatalogProjection.toCategoryFields(categoryWith(List.of(
                new CatalogPayloads.FacetDefinition(null, "No code", "TEXT"),
                new CatalogPayloads.FacetDefinition("brand", null, "TEXT"),
                new CatalogPayloads.FacetDefinition("size", "Kích cỡ", "SELECT"))));

        assertThat(fields).containsEntry("facet_definitions",
                List.of(Map.of("code", "size", "label", "Kích cỡ", "type", "SELECT")));
    }

    private static CatalogPayloads.CategorySnapshot categoryWith(List<CatalogPayloads.FacetDefinition> definitions) {
        return new CatalogPayloads.CategorySnapshot("cat-2", "cat-1", "Phones", "phones", 3L,
                List.of(segment("cat-1"), segment("cat-2")), true, definitions, NOW);
    }

    private static CatalogPayloads.ProductSnapshot product() {
        return new CatalogPayloads.ProductSnapshot("prod-1", "cat-2", "iPhone 15", "iphone-15",
                "A phone", Map.of("brand", "Apple"), "ACTIVE", 42L, NOW);
    }

    private static CatalogPayloads.SkuSnapshot sku() {
        return new CatalogPayloads.SkuSnapshot("sku-1", "SKU-1", 34_990_000L, 28_891_500L, "ACTIVE",
                Map.of("color", "Black"), "media/sku-1.jpg", NOW);
    }

    private static CatalogPayloads.CategorySnapshot category(List<CatalogPayloads.CategoryPathSegment> path) {
        return new CatalogPayloads.CategorySnapshot("cat-2", "cat-1", "Phones", "phones", 3L, path, true, null, NOW);
    }

    private static CatalogPayloads.CategoryPathSegment segment(String id) {
        return new CatalogPayloads.CategoryPathSegment(id, id, id);
    }
}
