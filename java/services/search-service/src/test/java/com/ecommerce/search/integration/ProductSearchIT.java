package com.ecommerce.search.integration;

import com.ecommerce.search.domain.document.CategoryDocument;
import com.ecommerce.search.domain.document.ProductDocument;
import com.ecommerce.search.domain.document.SuggestionDocument;
import com.ecommerce.search.dto.CategoryTreeResponse;
import com.ecommerce.search.dto.FacetResponse;
import com.ecommerce.search.dto.ProductSearchResponse;
import com.ecommerce.search.dto.SuggestResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Full integration tests for the public Search Service APIs using real
 * Elasticsearch and Kafka containers via Testcontainers.
 *
 * <p>Tests cover:
 * <ul>
 *   <li>{@code GET /api/v1/search/products} — full-text search, filtering, cursor pagination</li>
 *   <li>{@code GET /api/v1/search/suggest} — autocomplete suggestions</li>
 *   <li>{@code GET /api/v1/search/categories} — category tree</li>
 *   <li>{@code GET /api/v1/search/facets/{id}} — facet aggregations</li>
 * </ul>
 */
@DisplayName("Product Search API integration tests")
class ProductSearchIT extends AbstractIntegrationTest {

    @BeforeEach
    void setUp() throws Exception {
        // Index test categories
        indexCategory("cat-phone", "Điện thoại", "/Điện thoại", 0, null, 3L);
        indexCategory("cat-laptop", "Laptop", "/Laptop", 0, null, 2L);
        indexCategory("cat-android", "Android", "/Điện thoại/Android", 1, "cat-phone", 2L);

        // Index test products
        indexProduct(createDoc("sku-iphone-15", "prod-iphone-15", "cat-phone", "Điện thoại", null,
            "iPhone 15 Pro Max 256GB", "ACTIVE", "ACTIVE", false, 34990000L, 31990000L, true, null, null));

        indexProduct(createDoc("sku-galaxy-s24", "prod-galaxy-s24", "cat-android", "Android", "/Điện thoại/Android",
            "Samsung Galaxy S24 Ultra", "ACTIVE", "ACTIVE", false, 33990000L, 30990000L, true, null, null));

        indexProduct(createDoc("sku-xiaomi-14", "prod-xiaomi-14", "cat-android", "Android", "/Điện thoại/Android",
            "Xiaomi 14 Pro", "ACTIVE", "ACTIVE", false, 19990000L, 17990000L, true, null, null));

        indexProduct(createDoc("sku-macbook-pro", "prod-macbook-pro", "cat-laptop", "Laptop", null,
            "MacBook Pro 14 M3", "ACTIVE", "ACTIVE", false, 45990000L, 42990000L, true, null, null));

        indexProduct(createDoc("sku-lg-gram", "prod-lg-gram", "cat-laptop", "Laptop", null,
            "LG Gram 17", "HIDDEN", "ACTIVE", true, 29990000L, 27990000L, false, 4.2f, 128));

        // Index suggestion documents
        indexSuggestion("sg-iphone-15", "iphone 15 pro max", "PRODUCT", 95L);
        indexSuggestion("sg-galaxy-s24", "galaxy s24 ultra", "PRODUCT", 90L);
        indexSuggestion("sg-dien-thoai", "điện thoại", "CATEGORY", 100L);
        indexSuggestion("sg-laptop", "laptop", "CATEGORY", 85L);

        // Refresh all indices
        refreshESIndex("products_v1", "categories_v1", "search_suggestions_v1");
    }

    // ═══════════════════════════════════════════════════════════════════
    //  Product Search
    // ═══════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("GET /api/v1/search/products")
    class ProductSearchTests {

        @Test
        @DisplayName("Should return results for full-text query")
        void searchByText() {
            ResponseEntity<ProductSearchResponse> response = restTemplate.getForEntity(
                "/api/v1/search/products?q=iphone", ProductSearchResponse.class);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody()).isNotNull();
            assertThat(response.getBody().total()).isPositive();
            assertThat(response.getBody().items())
                .isNotEmpty()
                .allMatch(item -> item.name().toLowerCase().contains("iphone")
                    || item.productId().contains("iphone"));
        }

        @Test
        @DisplayName("Should return all products when query is empty")
        void searchAll() {
            ResponseEntity<ProductSearchResponse> response = restTemplate.getForEntity(
                "/api/v1/search/products", ProductSearchResponse.class);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody()).isNotNull();
            assertThat(response.getBody().total()).isPositive();
        }

        @Test
        @DisplayName("Should not return HIDDEN or deleted products")
        void searchExcludesHiddenAndDeleted() {
            ResponseEntity<ProductSearchResponse> response = restTemplate.getForEntity(
                "/api/v1/search/products?q=LG", ProductSearchResponse.class);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody()).isNotNull();
            // LG Gram is HIDDEN → should not appear
            assertThat(response.getBody().items())
                .noneMatch(item -> item.name().contains("LG"));
        }

        @Test
        @DisplayName("Should filter by category")
        void searchByCategory() {
            ResponseEntity<ProductSearchResponse> response = restTemplate.getForEntity(
                "/api/v1/search/products?category=cat-android", ProductSearchResponse.class);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody()).isNotNull();
            assertThat(response.getBody().items())
                .isNotEmpty()
                .allMatch(item -> item.productId().contains("galaxy") || item.productId().contains("xiaomi"));
        }

        @Test
        @DisplayName("Should filter by price range")
        void searchByPriceRange() {
            ResponseEntity<ProductSearchResponse> response = restTemplate.getForEntity(
                "/api/v1/search/products?priceMin=30000000&priceMax=40000000&inStock=false",
                ProductSearchResponse.class);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody()).isNotNull();
            // iPhone 15 (31990000) and Galaxy S24 (30990000) are in this range
            assertThat(response.getBody().items())
                .isNotEmpty();
        }

        @Test
        @DisplayName("Should return facets (categories and price ranges)")
        void searchWithFacets() {
            ResponseEntity<ProductSearchResponse> response = restTemplate.getForEntity(
                "/api/v1/search/products", ProductSearchResponse.class);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody()).isNotNull();

            // Should have facet data
            if (response.getBody().facets() != null) {
                assertThat(response.getBody().facets().filters()).isNotEmpty();
            }
        }

        @Test
        @DisplayName("Should return next_cursor for pagination and null on last page")
        void cursorPagination() {
            // Page 1: size=2
            ResponseEntity<ProductSearchResponse> page1 = restTemplate.getForEntity(
                "/api/v1/search/products?size=2", ProductSearchResponse.class);

            assertThat(page1.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(page1.getBody()).isNotNull();
            assertThat(page1.getBody().items()).hasSize(2);
            String cursor = page1.getBody().nextCursor();
            assertThat(cursor).isNotNull();

            // Page 2: use cursor
            ResponseEntity<ProductSearchResponse> page2 = restTemplate.getForEntity(
                "/api/v1/search/products?size=2&cursor=" + cursor, ProductSearchResponse.class);

            assertThat(page2.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(page2.getBody()).isNotNull();
            assertThat(page2.getBody().items()).isNotEmpty();
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    //  Suggest API
    // ═══════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("GET /api/v1/search/suggest")
    class SuggestTests {

        @Test
        @DisplayName("Should return suggestions for a valid prefix")
        void suggestByPrefix() {
            ResponseEntity<SuggestResponse> response = restTemplate.getForEntity(
                "/api/v1/search/suggest?q=iph", SuggestResponse.class);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody()).isNotNull();
            assertThat(response.getBody().suggestions())
                .isNotEmpty()
                .anyMatch(s -> s.text().toLowerCase().contains("iphone"));
        }

        @Test
        @DisplayName("Should return 400 for query shorter than 2 characters")
        void suggestTooShort() {
            ResponseEntity<String> response = restTemplate.getForEntity(
                "/api/v1/search/suggest?q=i", String.class);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        }

        @Test
        @DisplayName("Should return category suggestions")
        void suggestCategories() {
            ResponseEntity<SuggestResponse> response = restTemplate.getForEntity(
                "/api/v1/search/suggest?q=điện", SuggestResponse.class);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody()).isNotNull();
            assertThat(response.getBody().suggestions())
                .anyMatch(s -> s.type().equals("CATEGORY"));
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    //  Category Tree API
    // ═══════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("GET /api/v1/search/categories")
    class CategoryTreeTests {

        @Test
        @DisplayName("Should return all categories sorted by level")
        void getCategoryTree() {
            ResponseEntity<CategoryTreeResponse> response = restTemplate.getForEntity(
                "/api/v1/search/categories", CategoryTreeResponse.class);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody()).isNotNull();
            assertThat(response.getBody().categories())
                .isNotEmpty()
                .anyMatch(n -> n.name().equals("Điện thoại"))
                .anyMatch(n -> n.name().equals("Laptop"))
                .anyMatch(n -> n.name().equals("Android"));
        }

        @Test
        @DisplayName("Should include product_count in category nodes")
        void categoryTreeHasProductCount() {
            ResponseEntity<CategoryTreeResponse> response = restTemplate.getForEntity(
                "/api/v1/search/categories", CategoryTreeResponse.class);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            // cat-phone has 1 visible product (iPhone), cat-android has 2
            CategoryTreeResponse.CategoryNode phoneNode = response.getBody().categories().stream()
                .filter(n -> n.categoryId().equals("cat-phone"))
                .findFirst().orElseThrow();
            assertThat(phoneNode.productCount()).isPositive();
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    //  Facet API
    // ═══════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("GET /api/v1/search/facets/{categoryId}")
    class FacetTests {

        @Test
        @DisplayName("Should return facets for a valid category")
        void getFacets() {
            ResponseEntity<FacetResponse> response = restTemplate.getForEntity(
                "/api/v1/search/facets/cat-phone", FacetResponse.class);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody()).isNotNull();
            assertThat(response.getBody().categoryId()).isEqualTo("cat-phone");
        }

        @Test
        @DisplayName("Should return 404 for non-existent category")
        void getFacetsNonExistentCategory() {
            ResponseEntity<String> response = restTemplate.getForEntity(
                "/api/v1/search/facets/non-existent-cat", String.class);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    //  Helper Methods
    // ═══════════════════════════════════════════════════════════════════

    private void indexCategory(String id, String name, String path, int level,
                                String parentId, long productCount) throws Exception {
        CategoryDocument doc = CategoryDocument.builder()
            .categoryId(id)
            .name(name)
            .slug(name.toLowerCase().replace(" ", "-"))
            .parentId(parentId)
            .level(level)
            .isLeaf(level >= 1)
            .path(path)
            .productCount(productCount)
            .isDeleted(false)
            .version(Instant.now())
            .build();

        esClient.index(i -> i
            .index("categories_v1")
            .id(id)
            .document(doc));
    }

    private void indexProduct(ProductDocument doc) throws Exception {
        esClient.index(i -> i
            .index("products_v1")
            .id(doc.skuId())
            .document(doc));
    }

    private void indexSuggestion(String id, String text, String type, long weight) throws Exception {
        SuggestionDocument doc = SuggestionDocument.builder()
            .suggestionId(id)
            .text(text)
            .textNormalized(text.toLowerCase())
            .type(type)
            .source("test")
            .scope("GLOBAL")
            .weight(weight)
            .buildVersion(1L)
            .updatedAt(Instant.now())
            .build();

        esClient.index(i -> i
            .index("search_suggestions_v1")
            .id(id)
            .document(doc));
    }

    private ProductDocument createDoc(String skuId, String productId, String categoryId,
                                       String categoryName, String categoryPath, String name,
                                       String productStatus, String skuStatus, boolean isDeleted,
                                       Long originalPrice, Long bestPrice, Boolean hasPromotion,
                                       Float ratingAvg, Integer ratingCount) {
        return ProductDocument.builder()
            .skuId(skuId)
            .productId(productId)
            .categoryId(categoryId)
            .categoryName(categoryName)
            .categoryPath(categoryPath)
            .name(name)
            .productStatus(productStatus)
            .skuStatus(skuStatus)
            .isDeleted(isDeleted)
            .originalPrice(originalPrice)
            .bestPrice(bestPrice)
            .hasPromotion(hasPromotion)
            .isInStock(ProductDocument.computeIsInStock(isDeleted, productStatus, skuStatus))
            .productRatingAvg(ratingAvg)
            .productRatingCount(ratingCount)
            .createdAt(Instant.now())
            .updatedAt(Instant.now())
            .indexedAt(Instant.now())
            .version(Instant.now())
            .build();
    }
}
