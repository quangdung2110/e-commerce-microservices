package com.ecommerce.search.worker.handler;

import com.ecommerce.search.common.SearchMetrics;
import com.ecommerce.search.common.Timestamps;
import com.ecommerce.search.domain.event.CatalogEventType;
import com.ecommerce.search.domain.event.CatalogPayloads;
import com.ecommerce.search.domain.event.CloudEventEnvelope;
import com.ecommerce.search.repository.es.ProjectionWriter;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;

/**
 * {@code WF-SEARCH-005} — projects the Product catalog onto SKU documents.
 *
 * <p>Only the three catalog types are handled; anything else on the topic is ignored and
 * acknowledged. A payload that violates the contract is logged, counted and skipped: there
 * is no DLQ, and a systematic producer problem is recovered by a full reindex (ADR-005).
 *
 * <p>Every write also carries the readiness call it owes Inventory — a registration for an
 * upsert, a revocation for a tombstone. {@link BatchExecutor} makes it after the write
 * lands and before the offset is committed, which is what guarantees Inventory never
 * publishes availability for a SKU Search has not indexed yet (ADR-019).
 */
@Component
public class CatalogEventHandler {

    private static final Logger log = LoggerFactory.getLogger(CatalogEventHandler.class);

    private final ObjectMapper objectMapper;
    private final ProjectionWriter writer;
    private final CategoryEnrichment enrichment;
    private final SearchMetrics metrics;

    public CatalogEventHandler(ObjectMapper esObjectMapper,
                               ProjectionWriter writer,
                               CategoryEnrichment enrichment,
                               SearchMetrics metrics) {
        this.objectMapper = esObjectMapper;
        this.writer = writer;
        this.enrichment = enrichment;
        this.metrics = metrics;
    }

    public void handle(String topic, CloudEventEnvelope event, CatalogEventType type,
                       EventBatch batch, CategoryEnrichment.Cache cache) {
        switch (type) {
            case SKU_UPSERTED -> handleUpsert(topic, event, batch, cache);
            case SKU_DELETED -> handleSkuDeleted(topic, event, batch);
            case PRODUCT_DELETED -> handleProductDeleted(topic, event, batch);
            default -> metrics.eventIgnored(topic, event.type());
        }
    }

    private void handleUpsert(String topic, CloudEventEnvelope event, EventBatch batch,
                              CategoryEnrichment.Cache cache) {
        CatalogPayloads.SkuUpserted payload = bind(topic, event, CatalogPayloads.SkuUpserted.class);
        if (payload == null) {
            return;
        }
        CatalogPayloads.ProductSnapshot product = payload.product();
        CatalogPayloads.SkuSnapshot sku = payload.sku();
        if (product == null || sku == null || sku.skuId() == null || product.productId() == null) {
            invalid(topic, event, "sku-upserted is missing product or sku");
            return;
        }
        Long revision = revisionOf(event, product.catalogRevision());
        if (revision == null) {
            invalid(topic, event, "sku-upserted carries no catalog_revision");
            return;
        }

        var category = cache.lookup(product.categoryId());
        if (category.isEmpty()) {
            metrics.categoryEnrichmentMiss(topic, event.type());
        }

        Map<String, Object> fields = CatalogProjection.toFields(product, sku, category);
        batch.addCatalogWrite(
                writer.catalogUpsert(batch.targetIndex(), sku.skuId(), fields,
                        revision, Timestamps.nowIso(), event.id()),
                EventBatch.ReadinessIntent.register(sku.skuId(), revision));
        recordApplied(topic, event);
    }

    private void handleSkuDeleted(String topic, CloudEventEnvelope event, EventBatch batch) {
        CatalogPayloads.SkuDeleted payload = bind(topic, event, CatalogPayloads.SkuDeleted.class);
        if (payload == null) {
            return;
        }
        Long revision = revisionOf(event, payload.catalogRevision());
        if (payload.skuId() == null || revision == null) {
            invalid(topic, event, "sku-deleted is missing sku_id or catalog_revision");
            return;
        }
        batch.addCatalogWrite(
                writer.catalogTombstone(batch.targetIndex(), payload.skuId(), revision,
                        Timestamps.iso(deletedAt(payload.deletedAt(), event)), Timestamps.nowIso(), event.id()),
                EventBatch.ReadinessIntent.revoke(payload.skuId(), revision));
        recordApplied(topic, event);
    }

    /** A product tombstone fans out to one write per SKU listed in the payload. */
    private void handleProductDeleted(String topic, CloudEventEnvelope event, EventBatch batch) {
        CatalogPayloads.ProductDeleted payload = bind(topic, event, CatalogPayloads.ProductDeleted.class);
        if (payload == null) {
            return;
        }
        Long revision = revisionOf(event, payload.catalogRevision());
        if (payload.skuIds() == null || payload.skuIds().isEmpty() || revision == null) {
            invalid(topic, event, "product-deleted is missing sku_ids or catalog_revision");
            return;
        }
        String deletedAt = Timestamps.iso(deletedAt(payload.deletedAt(), event));
        String indexedAt = Timestamps.nowIso();
        payload.skuIds().forEach(skuId -> batch.addCatalogWrite(
                writer.catalogTombstone(batch.targetIndex(), skuId, revision, deletedAt, indexedAt, event.id()),
                EventBatch.ReadinessIntent.revoke(skuId, revision)));
        recordApplied(topic, event);
    }

    /**
     * The revision guard. The contract puts it both in the CloudEvents {@code sequence}
     * and in the payload; they must agree, and either one is enough to guard with.
     */
    private static Long revisionOf(CloudEventEnvelope event, Long payloadRevision) {
        return Optional.ofNullable(payloadRevision).orElse(event.sequence());
    }

    private static Instant deletedAt(Instant payloadDeletedAt, CloudEventEnvelope event) {
        return payloadDeletedAt != null ? payloadDeletedAt : event.time();
    }

    private <T> T bind(String topic, CloudEventEnvelope event, Class<T> type) {
        try {
            return objectMapper.treeToValue(event.data(), type);
        } catch (Exception e) {
            invalid(topic, event, "payload does not bind to " + type.getSimpleName() + ": " + e.getMessage());
            return null;
        }
    }

    private void invalid(String topic, CloudEventEnvelope event, String reason) {
        log.error("contract violation on {} event {} ({}): {}", topic, event.id(), event.type(), reason);
        metrics.payloadInvalid(topic, event.type());
        metrics.indexing(topic, event.type(), "invalid");
    }

    private void recordApplied(String topic, CloudEventEnvelope event) {
        metrics.indexing(topic, event.type(), "applied");
        if (event.time() != null) {
            metrics.indexingLag(topic, java.time.Duration.between(event.time(), Instant.now()));
        }
    }
}
