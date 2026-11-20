package com.ecommerce.search.integration;

import com.ecommerce.search.common.Timestamps;
import com.ecommerce.search.domain.document.ProductDocument;
import com.ecommerce.search.domain.event.CatalogPayloads;
import com.ecommerce.search.repository.es.ProjectionWriter;
import com.ecommerce.search.worker.handler.BatchExecutor;
import com.ecommerce.search.worker.handler.CatalogProjection;
import com.ecommerce.search.worker.handler.EventBatch;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.core.KafkaTemplate;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The listener path itself, over a real Kafka: what happens to a batch the indexing worker
 * decides not to acknowledge.
 *
 * <p>ADR-005 and ADR-019 promise that such a batch is redelivered ("không commit offset để
 * Kafka gửi lại"). Every other test calls {@code BatchExecutor} directly and checks only the
 * boolean it returns, so none of them can tell whether Kafka really delivers the batch again.
 */
class ConsumerRedeliveryIT extends AbstractSearchIntegrationTest {

    private static final Instant NOW = Instant.parse("2026-10-10T00:00:00.000Z");

    @Autowired
    private KafkaTemplate<String, String> kafka;

    @Autowired
    private ProjectionWriter writer;

    @Autowired
    private BatchExecutor executor;

    @Test
    void anUnacknowledgedAvailabilityBatchIsDeliveredAgainOnceTheDocumentExists() throws Exception {
        String skuId = "sku-" + UUID.randomUUID();

        // The document is absent: ADR-019 calls this a readiness contract violation, so the
        // worker must not acknowledge the batch.
        kafka.send(properties.topics().stock(), skuId, availabilityEvent(skuId, 1L)).get();
        Thread.sleep(8_000);

        // Product catches up; had Kafka redelivered, the next attempt would now succeed.
        createDocument(skuId);

        ProductDocument document = awaitInStock(skuId, Duration.ofSeconds(45));

        assertThat(document).as("availability event was redelivered and applied").isNotNull();
        assertThat(document.inStock()).isTrue();
        assertThat(document.availabilityRevision()).isEqualTo(1L);
    }

    @Autowired
    private io.micrometer.core.instrument.MeterRegistry meters;

    @Test
    void consumerLagComesFromTheStandardKafkaClientMetrics() throws Exception {
        // docs/search/operations.md §4: lag is read from the Kafka client metrics the consumer
        // factory binds, not from a metric of Search's own.
        java.util.List<String> names = meters.getMeters().stream()
                .map(meter -> meter.getId().getName())
                .filter(name -> name.startsWith("kafka.consumer"))
                .distinct().sorted().toList();
        System.out.println("KAFKA-METERS " + names);

        assertThat(names).anyMatch(name -> name.contains("records.lag"));
    }

    private ProductDocument awaitInStock(String skuId, Duration timeout) throws Exception {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            Optional<ProductDocument> found = read(skuId);
            if (found.isPresent() && found.get().inStock()) {
                return found.get();
            }
            Thread.sleep(500);
        }
        return null;
    }

    private void createDocument(String skuId) throws IOException {
        CatalogPayloads.ProductSnapshot product = new CatalogPayloads.ProductSnapshot(
                "prod-" + skuId, "cat-1", "iPhone 15", "iphone-15", "A phone",
                Map.of("brand", "Apple"), "ACTIVE", 1L, NOW);
        CatalogPayloads.SkuSnapshot sku = new CatalogPayloads.SkuSnapshot(
                skuId, "SKU-" + skuId, 34_990_000L, 28_000_000L, "ACTIVE",
                Map.of("color", "Black"), null, NOW);
        EventBatch batch = new EventBatch(properties.aliases().productsWrite());
        batch.add(writer.catalogUpsert(batch.targetIndex(), skuId,
                CatalogProjection.toFields(product, sku, Optional.empty()),
                1L, Timestamps.nowIso(), "catalog-" + skuId));
        executor.execute(properties.topics().product(), batch);
        refreshAll();
    }

    private Optional<ProductDocument> read(String skuId) throws IOException {
        refreshAll();
        var response = client.get(builder -> builder
                .index(properties.aliases().products()).id(skuId), ProductDocument.class);
        return response.found() ? Optional.ofNullable(response.source()) : Optional.empty();
    }

    private static String availabilityEvent(String skuId, long revision) {
        return """
                {"specversion":"1.0","id":"evt-%s","type":"com.ecommerce.inventory.sku-availability.changed.v1",
                 "source":"/services/inventory","subject":"sku-availability/%s",
                 "time":"2026-10-10T00:00:00Z","datacontenttype":"application/json",
                 "dataschema":"/contracts/events/inventory/v1/schemas/sku-availability-changed.schema.json",
                 "sequence":%d,
                 "data":{"sku_id":"%s","is_in_stock":true,"availability_revision":%d,
                         "observed_at":"2026-10-10T00:00:00Z"}}
                """.formatted(UUID.randomUUID(), skuId, revision, skuId, revision);
    }
}
