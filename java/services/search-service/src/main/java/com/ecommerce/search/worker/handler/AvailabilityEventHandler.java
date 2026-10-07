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

/**
 * {@code WF-SEARCH-007} — copies the Inventory-owned per-SKU availability boolean.
 *
 * <p>Inventory reads every warehouse position it owns and publishes one aggregate per
 * {@code sku_id}; Search stores no warehouse or position fact and never recomputes the
 * boolean itself (ADR-019). The write never upserts: only Product may create a document
 * ({@code SEARCH-INV-005}). Because readiness was registered before Inventory was allowed
 * to publish, a missing target is a contract violation rather than a race — hence
 * {@link EventBatch#addAvailabilityWrite}, which stops the offset from being committed.
 *
 * <p>{@code availability_revision} is the only ordering guard. The global export
 * {@code sequence} orders the recovery feed and must not stand in for it, so an event
 * without a revision is a contract violation rather than something to fall back on.
 */
@Component
public class AvailabilityEventHandler {

    private static final Logger log = LoggerFactory.getLogger(AvailabilityEventHandler.class);

    private final ObjectMapper objectMapper;
    private final ProjectionWriter writer;
    private final SearchMetrics metrics;

    public AvailabilityEventHandler(ObjectMapper esObjectMapper, ProjectionWriter writer, SearchMetrics metrics) {
        this.objectMapper = esObjectMapper;
        this.writer = writer;
        this.metrics = metrics;
    }

    public void handle(String topic, CloudEventEnvelope event, EventBatch batch) {
        CatalogPayloads.SkuAvailability payload;
        try {
            payload = objectMapper.treeToValue(event.data(), CatalogPayloads.SkuAvailability.class);
        } catch (Exception e) {
            invalid(topic, event, "payload does not bind to a sku availability: " + e.getMessage());
            return;
        }
        if (payload.skuId() == null || payload.skuId().isBlank()) {
            invalid(topic, event, "sku availability is missing sku_id");
            return;
        }
        if (payload.isInStock() == null || payload.availabilityRevision() == null) {
            invalid(topic, event, "sku availability is missing is_in_stock or availability_revision");
            return;
        }
        Instant observedAt = payload.observedAt() != null ? payload.observedAt() : event.time();

        batch.addAvailabilityWrite(writer.availabilityPatch(batch.targetIndex(), payload.skuId(),
                payload.isInStock(), payload.availabilityRevision(),
                Timestamps.iso(observedAt), Timestamps.nowIso(), event.id()));

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
