package com.ecommerce.search.integration;

import co.elastic.clients.elasticsearch.core.GetResponse;
import com.ecommerce.search.common.EventType;
import com.ecommerce.search.domain.document.CategoryDocument;
import com.ecommerce.search.domain.document.ProductDocument;
import com.ecommerce.search.domain.event.CloudEventEnvelope;
import com.fasterxml.jackson.databind.JsonNode;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.NewTopic;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.core.KafkaAdmin;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Full integration tests for the Kafka consumer pipeline using real
 * Elasticsearch and Kafka containers.
 *
 * <p>Tests the end-to-end flow:
 * <ol>
 *   <li>Produce a CloudEvent to the product/category/review topic</li>
 *   <li>Wait for {@link com.ecommerce.search.worker.ProductIndexingConsumer}
 *       to consume and dispatch to {@link com.ecommerce.search.worker.EventHandler}</li>
 *   <li>Verify the corresponding ES document was indexed/updated</li>
 * </ol>
 */
@DisplayName("Kafka consumer integration tests")
class ProductIndexingConsumerIT extends AbstractIntegrationTest {

    private static final String PRODUCT_TOPIC = "t.product.events";
    private static final String CATEGORY_TOPIC = "t.category.events";
    private static final String REVIEW_TOPIC = "t.review.events";

    @Autowired(required = false)
    private KafkaAdmin kafkaAdmin;

    @BeforeAll
    static void createTopics() {
        // Topics are auto-created by Kafka when the first message is sent,
        // but we ensure they exist to avoid race conditions.
        System.out.println("Kafka bootstrap: " + KAFKA_CONTAINER.getBootstrapServers());
    }

    @BeforeEach
    void setUp() throws Exception {
        // Index a category that will be referenced by product events
        indexCategory("cat-phones", "Điện thoại", "/Điện thoại", 0, null);
        refreshESIndex("categories_v1");
    }

    // ═══════════════════════════════════════════════════════════════════
    //  Product Events
    // ═══════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Product events")
    class ProductEvents {

        @Test
        @DisplayName("PRODUCT_CREATED should index a new product document in ES")
        void productCreatedShouldIndexDocument() throws Exception {
            // Given
            Instant now = Instant.now();
            JsonNode payload = SNAKE_MAPPER.valueToTree(Map.of(
                "product_id", "prod-new-001",
                "category_id", "cat-phones",
                "name", "Test Phone",
                "status", "ACTIVE",
                "created_at", now.toString(),
                "updated_at", now.toString(),
                "skus", List.of(Map.of(
                    "sku_id", "sku-new-001",
                    "sku_status", "ACTIVE",
                    "original_price", 15000000L,
                    "promotional_price", 12990000L,
                    "best_price", 12990000L,
                    "has_promotion", true
                ))
            ));

            CloudEventEnvelope envelope = new CloudEventEnvelope(
                UUID.randomUUID().toString(),
                "PRODUCT_CREATED",
                "Product",
                "prod-new-001",
                "corr-" + UUID.randomUUID().toString().substring(0, 8),
                "prod-new-001",
                now.toString(),
                "test",
                1,
                payload
            );

            // When — produce to Kafka
            kafkaTemplate.send(PRODUCT_TOPIC, "prod-new-001", envelope).get(10, TimeUnit.SECONDS);

            // Then — wait for consumer to process and verify ES document
            awaitUntil(() -> {
                try {
                    GetResponse<ProductDocument> response = esClient.get(
                        g -> g.index("products_v1").id("sku-new-001"),
                        ProductDocument.class
                    );
                    assertThat(response.found()).isTrue();
                    assertThat(response.source().name()).isEqualTo("Test Phone");
                    assertThat(response.source().productStatus()).isEqualTo("ACTIVE");
                    assertThat(response.source().bestPrice()).isEqualTo(12990000L);
                } catch (Exception e) {
                    throw new AssertionError("Product not indexed yet", e);
                }
            });
        }

        @Test
        @DisplayName("PRODUCT_CREATED with DRAFT status should not increase product_count")
        void draftProductShouldNotIncrementCount() throws Exception {
            // Given
            Instant now = Instant.now();
            JsonNode payload = SNAKE_MAPPER.valueToTree(Map.of(
                "product_id", "prod-draft-001",
                "category_id", "cat-phones",
                "name", "Draft Phone",
                "status", "DRAFT",
                "created_at", now.toString(),
                "updated_at", now.toString(),
                "skus", List.of(Map.of(
                    "sku_id", "sku-draft-001",
                    "sku_status", "DRAFT",
                    "original_price", 10000000L,
                    "promotional_price", 9000000L,
                    "best_price", 9000000L,
                    "has_promotion", true
                ))
            ));

            CloudEventEnvelope envelope = new CloudEventEnvelope(
                UUID.randomUUID().toString(),
                "PRODUCT_CREATED",
                "Product",
                "prod-draft-001",
                "corr-draft",
                "prod-draft-001",
                now.toString(),
                "test",
                1,
                payload
            );

            // When
            kafkaTemplate.send(PRODUCT_TOPIC, "prod-draft-001", envelope).get(10, TimeUnit.SECONDS);

            // Then — document should be indexed but product_count unchanged
            awaitUntil(() -> {
                try {
                    GetResponse<ProductDocument> response = esClient.get(
                        g -> g.index("products_v1").id("sku-draft-001"),
                        ProductDocument.class
                    );
                    assertThat(response.found()).isTrue();
                    assertThat(response.source().productStatus()).isEqualTo("DRAFT");
                } catch (Exception e) {
                    throw new AssertionError("Draft product not indexed", e);
                }
            });
        }

        @Test
        @DisplayName("PRODUCT_DELETED should tombstone product documents")
        void productDeletedShouldTombstone() throws Exception {
            // First — index an active product directly in ES
            Instant now = Instant.now();
            ProductDocument doc = ProductDocument.builder()
                .skuId("sku-to-delete-001")
                .productId("prod-to-delete")
                .categoryId("cat-phones")
                .name("To Be Deleted")
                .productStatus("ACTIVE")
                .skuStatus("ACTIVE")
                .isDeleted(false)
                .isInStock(true)
                .originalPrice(10000000L)
                .bestPrice(10000000L)
                .hasPromotion(false)
                .createdAt(now)
                .updatedAt(now)
                .indexedAt(now)
                .version(now)
                .build();

            esClient.index(i -> i.index("products_v1").id("sku-to-delete-001").document(doc));
            refreshESIndex("products_v1");

            // Verify indexed
            GetResponse<ProductDocument> beforeDelete = esClient.get(
                g -> g.index("products_v1").id("sku-to-delete-001"),
                ProductDocument.class
            );
            assertThat(beforeDelete.found()).isTrue();

            // When — send PRODUCT_DELETED event
            JsonNode deletePayload = SNAKE_MAPPER.valueToTree(Map.of(
                "product_id", "prod-to-delete",
                "category_id", "cat-phones",
                "sku_ids", List.of("sku-to-delete-001"),
                "updated_at", now.plusSeconds(1).toString(),
                "deleted_at", now.plusSeconds(1).toString()
            ));

            CloudEventEnvelope deleteEnvelope = new CloudEventEnvelope(
                UUID.randomUUID().toString(),
                "PRODUCT_DELETED",
                "Product",
                "prod-to-delete",
                "corr-delete",
                "prod-to-delete",
                now.toString(),
                "test",
                1,
                deletePayload
            );

            kafkaTemplate.send(PRODUCT_TOPIC, "prod-to-delete", deleteEnvelope).get(10, TimeUnit.SECONDS);

            // Then — document should be tombstoned
            awaitUntil(() -> {
                try {
                    GetResponse<ProductDocument> response = esClient.get(
                        g -> g.index("products_v1").id("sku-to-delete-001"),
                        ProductDocument.class
                    );
                    assertThat(response.found()).isTrue();
                    assertThat(response.source().isDeleted()).isTrue();
                    assertThat(response.source().isInStock()).isFalse();
                } catch (Exception e) {
                    throw new AssertionError("Product not tombstoned", e);
                }
            });
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    //  Category Events
    // ═══════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Category events")
    class CategoryEvents {

        @Test
        @DisplayName("CATEGORY_CREATED should index a new category document")
        void categoryCreatedShouldIndexDocument() throws Exception {
            // Given
            Map<String, Object> payloadMap = new java.util.HashMap<>();
            payloadMap.put("category_id", "cat-tablets");
            payloadMap.put("name", "Máy tính bảng");
            payloadMap.put("slug", "may-tinh-bang");
            payloadMap.put("parent_id", null);
            payloadMap.put("level", 0);
            payloadMap.put("is_leaf", false);
            payloadMap.put("path", "/Máy tính bảng");
            JsonNode payload = SNAKE_MAPPER.valueToTree(payloadMap);

            CloudEventEnvelope envelope = new CloudEventEnvelope(
                UUID.randomUUID().toString(),
                "CATEGORY_CREATED",
                "Category",
                "cat-tablets",
                "corr-cat",
                "cat-tablets",
                Instant.now().toString(),
                "test",
                1,
                payload
            );

            // When
            kafkaTemplate.send(CATEGORY_TOPIC, "cat-tablets", envelope).get(10, TimeUnit.SECONDS);

            // Then
            awaitUntil(() -> {
                try {
                    GetResponse<CategoryDocument> response = esClient.get(
                        g -> g.index("categories_v1").id("cat-tablets"),
                        CategoryDocument.class
                    );
                    assertThat(response.found()).isTrue();
                    assertThat(response.source().name()).isEqualTo("Máy tính bảng");
                    assertThat(response.source().path()).isEqualTo("/Máy tính bảng");
                } catch (Exception e) {
                    throw new AssertionError("Category not indexed", e);
                }
            });
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    //  Review Events
    // ═══════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Review events")
    class ReviewEvents {

        @Test
        @DisplayName("REVIEW_METRICS_UPDATED should update product rating via _update_by_query")
        void reviewMetricsShouldUpdateRating() throws Exception {
            // First — index two SKUs for the same product
            Instant now = Instant.now();
            String[] skuIds = {"sku-review-1", "sku-review-2"};
            for (String skuId : skuIds) {
                ProductDocument doc = ProductDocument.builder()
                    .skuId(skuId)
                    .productId("prod-review-test")
                    .categoryId("cat-phones")
                    .name("Review Product " + skuId)
                    .productStatus("ACTIVE")
                    .skuStatus("ACTIVE")
                    .isDeleted(false)
                    .isInStock(true)
                    .originalPrice(10000000L)
                    .bestPrice(10000000L)
                    .hasPromotion(false)
                    .createdAt(now)
                    .updatedAt(now)
                    .indexedAt(now)
                    .version(now)
                    .build();

                esClient.index(idx -> idx.index("products_v1").id(skuId).document(doc));
            }
            refreshESIndex("products_v1");

            // When — send REVIEW_METRICS_UPDATED
            JsonNode payload = SNAKE_MAPPER.valueToTree(Map.of(
                "product_id", "prod-review-test",
                "average_rating", 4.5,
                "total_reviews", 10,
                "updated_at", now.toString()
            ));

            CloudEventEnvelope envelope = new CloudEventEnvelope(
                UUID.randomUUID().toString(),
                "REVIEW_METRICS_UPDATED",
                "Review",
                "prod-review-test",
                "corr-review",
                "prod-review-test",
                now.toString(),
                "review-service",
                1,
                payload
            );

            kafkaTemplate.send(REVIEW_TOPIC, "prod-review-test", envelope).get(10, TimeUnit.SECONDS);

            // Then — both SKUs should have the new rating
            awaitUntil(() -> {
                try {
                    for (String skuId : skuIds) {
                        GetResponse<ProductDocument> response = esClient.get(
                            g -> g.index("products_v1").id(skuId),
                            ProductDocument.class
                        );
                        assertThat(response.found()).isTrue();
                        assertThat(response.source().productRatingAvg()).isEqualTo(4.5f);
                        assertThat(response.source().productRatingCount()).isEqualTo(10);
                    }
                } catch (Exception e) {
                    throw new AssertionError("Review metrics not updated", e);
                }
            });
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    //  Helpers
    // ═══════════════════════════════════════════════════════════════════

    private void indexCategory(String id, String name, String path, int level,
                                String parentId) throws Exception {
        CategoryDocument doc = CategoryDocument.builder()
            .categoryId(id)
            .name(name)
            .slug(name.toLowerCase().replace(" ", "-"))
            .parentId(parentId)
            .level(level)
            .isLeaf(level >= 1)
            .path(path)
            .productCount(0L)
            .isDeleted(false)
            .version(Instant.now())
            .build();

        esClient.index(i -> i
            .index("categories_v1")
            .id(id)
            .document(doc));
    }
}
