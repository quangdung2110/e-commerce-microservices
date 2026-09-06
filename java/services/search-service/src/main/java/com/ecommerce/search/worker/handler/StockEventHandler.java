package com.ecommerce.search.worker.handler;

import com.ecommerce.search.common.SearchMetrics;
import com.ecommerce.search.common.Timestamps;
import com.ecommerce.search.domain.event.CatalogPayloads;
import com.ecommerce.search.domain.event.CloudEventEnvelope;
import com.ecommerce.search.repository.es.ProjectionWriter;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Optional;

/**
 * {@code WF-SEARCH-007} — projects the Inventory stock snapshot.
 *
 * <p>The write never upserts. A SKU that is not indexed yet, or has just been tombstoned,
 * makes the update return {@code document_missing_exception}; that is a normal state, so
 * it is counted and acknowledged rather than treated as a failure
 * ({@code SEARCH-INV-005}).
 */
@Component
public class StockEventHandler {

    private static final Logger log = LoggerFactory.getLogger(StockEventHandler.class);

    private final ObjectMapper objectMapper;
    private final ProjectionWriter writer;
    private final SearchMetrics metrics;

    public StockEventHandler(ObjectMapper esObjectMapper, ProjectionWriter writer, SearchMetrics metrics) {
        this.objectMapper = esObjectMapper;
        this.writer = writer;
        this.metrics = metrics;
    }

    public void handle(String topic, CloudEventEnvelope event, EventBatch batch) {
        CatalogPayloads.StockSnapshot payload;
        try {
            payload = objectMapper.treeToValue(event.data(), CatalogPayloads.StockSnapshot.class);
        } catch (Exception e) {
            invalid(topic, event, "payload does not bind to a stock snapshot: " + e.getMessage());
            return;
        }
        // Both identifiers must be present: the pair is what lets an operator spot drift
        // between the Inventory and catalog projections.
        if (payload.skuId() == null || payload.skuId().isBlank()
                || payload.productId() == null || payload.productId().isBlank()) {
            invalid(topic, event, "stock snapshot is missing product_id or sku_id");
            return;
        }
        Long revision = Optional.ofNullable(payload.stockRevision()).orElse(event.sequence());
        if (revision == null || payload.availableQuantity() == null) {
            invalid(topic, event, "stock snapshot is missing stock_revision or available_quantity");
            return;
        }
        Instant observedAt = payload.observedAt() != null ? payload.observedAt() : event.time();

        batch.add(writer.stockPatch(batch.targetIndex(), payload.skuId(), payload.availableQuantity(),
                revision, Timestamps.iso(observedAt), Timestamps.nowIso(), event.id()));

        metrics.indexing(topic, event.type(), "applied");
        if (event.time() != null) {
            metrics.indexingLag(topic, java.time.Duration.between(event.time(), Instant.now()));
        }
    }

    private void invalid(String topic, CloudEventEnvelope event, String reason) {
        log.error("contract violation on {} event {}: {}", topic, event.id(), reason);
        metrics.payloadInvalid(topic, event.type());
        metrics.indexing(topic, event.type(), "invalid");
    }
}
