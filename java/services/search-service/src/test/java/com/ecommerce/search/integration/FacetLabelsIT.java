package com.ecommerce.search.integration;

import com.ecommerce.search.common.Timestamps;
import com.ecommerce.search.domain.document.CategoryDocument;
import com.ecommerce.search.domain.event.CatalogPayloads;
import com.ecommerce.search.dto.SearchDtos;
import com.ecommerce.search.repository.es.ProjectionWriter;
import com.ecommerce.search.service.FacetLabelResolver;
import com.ecommerce.search.worker.handler.BatchExecutor;
import com.ecommerce.search.worker.handler.CatalogProjection;
import com.ecommerce.search.worker.handler.EventBatch;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Product ADR-019: the group label of a facet comes from the {@code facet_definitions} on the
 * Category snapshot, over the real mapping, painless script and HTTP contract. Option labels stay
 * derived because the descriptor carries no options.
 */
class FacetLabelsIT extends AbstractSearchIntegrationTest {

    private static final Instant NOW = Instant.parse("2026-10-09T10:00:00.000Z");

    @Autowired
    private TestRestTemplate rest;
    @Autowired
    private ProjectionWriter writer;
    @Autowired
    private BatchExecutor executor;
    @Autowired
    private FacetLabelResolver labels;

    @BeforeEach
    void forgetCachedLabels() {
        labels.evictAll();
    }

    @Test
    void categoryFacetsUseTheDescriptorLabelForTheGroupAndDeriveOptionLabels() throws IOException {
        indexCategories(1L, definitions(def("brand", "Thương hiệu"), def("color", "Màu sắc")));
        indexProduct("sku-1", "prod-1");

        SearchDtos.FacetSet facets = categoryFacets("root");

        assertThat(facets.filters()).extracting(SearchDtos.AttributeFacet::code)
                .containsExactlyInAnyOrder("brand", "color");
        assertThat(facet(facets, "brand").label()).isEqualTo("Thương hiệu");
        assertThat(facet(facets, "color").label()).isEqualTo("Màu sắc");
        assertThat(facet(facets, "brand").options()).extracting(SearchDtos.FacetOption::label)
                .containsExactly("Apple");
    }

    @Test
    void searchScopedToABranchAndUnscopedSearchUseTheSameLabels() throws IOException {
        indexCategories(1L, definitions(def("brand", "Thương hiệu")));
        indexProduct("sku-1", "prod-1");

        SearchDtos.SearchPage scoped = search("?category=root");
        SearchDtos.SearchPage unscoped = search("");

        assertThat(facet(scoped.facets(), "brand").label()).isEqualTo("Thương hiệu");
        assertThat(facet(unscoped.facets(), "brand").label()).isEqualTo("Thương hiệu");
    }

    @Test
    void anAttributeWithoutADescriptorKeepsTheDerivedLabel() throws IOException {
        indexCategories(1L, definitions(def("brand", "Thương hiệu")));
        indexProduct("sku-1", "prod-1");

        assertThat(facet(categoryFacets("root"), "color").label()).isEqualTo("Color");
    }

    @Test
    void aSnapshotWithoutFacetDefinitionsNeverWipesWhatANewerOneWrote() throws IOException {
        indexCategories(1L, definitions(def("brand", "Thương hiệu")));
        indexProduct("sku-1", "prod-1");
        indexCategories(2L, null);
        labels.evictAll();

        assertThat(facet(categoryFacets("root"), "brand").label()).isEqualTo("Thương hiệu");
    }

    @Test
    void anEmptyDescriptorListIsARealAnswerAndReplacesThePreviousOne() throws IOException {
        indexCategories(1L, definitions(def("brand", "Thương hiệu")));
        indexProduct("sku-1", "prod-1");
        indexCategories(2L, List.of());
        labels.evictAll();

        assertThat(facet(categoryFacets("root"), "brand").label()).isEqualTo("Brand");
    }

    @Test
    void aRenameReachesTheFacetOnceTheNewSnapshotIsApplied() throws IOException {
        indexCategories(1L, definitions(def("brand", "Thương hiệu")));
        indexProduct("sku-1", "prod-1");
        assertThat(facet(categoryFacets("root"), "brand").label()).isEqualTo("Thương hiệu");

        indexCategories(2L, definitions(def("brand", "Hãng sản xuất")));
        labels.evictAll();

        assertThat(facet(categoryFacets("root"), "brand").label()).isEqualTo("Hãng sản xuất");
    }

    private static CatalogPayloads.FacetDefinition def(String code, String label) {
        return new CatalogPayloads.FacetDefinition(code, label, "TEXT");
    }

    private static List<CatalogPayloads.FacetDefinition> definitions(CatalogPayloads.FacetDefinition... items) {
        return List.of(items);
    }

    private static SearchDtos.AttributeFacet facet(SearchDtos.FacetSet facets, String code) {
        return facets.filters().stream().filter(item -> item.code().equals(code)).findFirst().orElseThrow();
    }

    private SearchDtos.FacetSet categoryFacets(String categoryId) {
        ResponseEntity<SearchDtos.FacetSet> response = rest.getForEntity(
                "/api/v2/search/categories/{id}/facets", SearchDtos.FacetSet.class, categoryId);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return response.getBody();
    }

    private SearchDtos.SearchPage search(String queryString) {
        ResponseEntity<SearchDtos.SearchPage> response =
                rest.getForEntity("/api/v2/search/products" + queryString, SearchDtos.SearchPage.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return response.getBody();
    }

    /**
     * A root branch node with no mapping of its own and a leaf {@code phones} carrying the
     * descriptors, which is how Product publishes them: Products live only in leaf categories.
     * {@code leafDefinitions == null} models a snapshot from a producer older than ADR-019.
     */
    private void indexCategories(long revision, List<CatalogPayloads.FacetDefinition> leafDefinitions)
            throws IOException {
        EventBatch batch = new EventBatch(properties.aliases().categoriesWrite());
        batch.add(writer.categoryUpsert(batch.targetIndex(), "root",
                CatalogProjection.toCategoryFields(category("root", null, List.of("root"), false, List.of())),
                revision, Timestamps.nowIso(), "category-root-" + revision));
        batch.add(writer.categoryUpsert(batch.targetIndex(), "phones",
                CatalogProjection.toCategoryFields(
                        category("phones", "root", List.of("root", "phones"), true, leafDefinitions)),
                revision, Timestamps.nowIso(), "category-phones-" + revision));
        executor.execute("t.category.events", batch);
        refreshAll();
    }

    private static CatalogPayloads.CategorySnapshot category(String id, String parentId, List<String> path,
                                                             boolean leaf,
                                                             List<CatalogPayloads.FacetDefinition> definitions) {
        return new CatalogPayloads.CategorySnapshot(id, parentId, id, id, 1L,
                path.stream().map(segment -> new CatalogPayloads.CategoryPathSegment(segment, segment, segment))
                        .toList(),
                leaf, definitions, NOW);
    }

    private void indexProduct(String skuId, String productId) throws IOException {
        CategoryDocument phones = new CategoryDocument("phones", "phones", "phones", "root",
                List.of("root", "phones"), List.of("root", "phones"), 1, true, null,
                null, null, false, null, NOW, NOW, 1L, "evt");
        CatalogPayloads.ProductSnapshot product = new CatalogPayloads.ProductSnapshot(
                productId, "phones", "iPhone", productId + "-slug", "description",
                Map.of("brand", "Apple"), "ACTIVE", 1L, NOW);
        CatalogPayloads.SkuSnapshot sku = new CatalogPayloads.SkuSnapshot(
                skuId, "CODE-" + skuId, 2_000_000L, 1_000_000L, "ACTIVE",
                Map.of("color", "Black"), "media/" + skuId + ".jpg", NOW);

        EventBatch batch = new EventBatch(properties.aliases().productsWrite());
        batch.add(writer.catalogUpsert(batch.targetIndex(), skuId,
                CatalogProjection.toFields(product, sku, Optional.of(phones)),
                1L, Timestamps.nowIso(), "catalog-" + skuId));
        batch.add(writer.availabilityPatch(batch.targetIndex(), skuId, true, 1L,
                Timestamps.iso(NOW), Timestamps.nowIso(), "availability-" + skuId));
        executor.execute("t.product.events", batch);
        refreshAll();
    }
}
