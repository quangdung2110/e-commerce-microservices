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
import java.util.Optional;

/**
 * {@code WF-SEARCH-006} — projects the Category snapshot Product publishes.
 *
 * <p>Search materialises the path Product already derived; it does not rebuild the tree
 * and never fans a rename out to SKU documents with an {@code _update_by_query}. A tree
 * change reaches SKUs through the control-driven reindex instead (ADR-013).
 */
@Component
public class CategoryEventHandler {

    private static final Logger log = LoggerFactory.getLogger(CategoryEventHandler.class);

    private final ObjectMapper objectMapper;
    private final ProjectionWriter writer;
    private final SearchMetrics metrics;

    public CategoryEventHandler(ObjectMapper esObjectMapper, ProjectionWriter writer, SearchMetrics metrics) {
        this.objectMapper = esObjectMapper;
        this.writer = writer;
        this.metrics = metrics;
    }

    public void handle(String topic, CloudEventEnvelope event, CatalogEventType type, EventBatch batch) {
        switch (type) {
            case CATEGORY_UPSERTED -> handleUpsert(topic, event, batch);
            case CATEGORY_DELETED -> handleDeleted(topic, event, batch);
            default -> metrics.eventIgnored(topic, event.type());
        }
    }

    private void handleUpsert(String topic, CloudEventEnvelope event, EventBatch batch) {
        CatalogPayloads.CategoryUpserted payload = bind(topic, event, CatalogPayloads.CategoryUpserted.class);
        if (payload == null) {
            return;
        }
        CatalogPayloads.CategorySnapshot category = payload.category();
        if (category == null || category.categoryId() == null) {
            invalid(topic, event, "category-upserted carries no category");
            return;
        }
        if (!CatalogProjection.hasValidPath(category)) {
            invalid(topic, event, "category path is truncated, cyclic or does not end at the node itself");
            return;
        }
        Long revision = Optional.ofNullable(category.categoryRevision()).orElse(event.sequence());
        if (revision == null) {
            invalid(topic, event, "category-upserted carries no category_revision");
            return;
        }
        batch.add(writer.categoryUpsert(batch.targetIndex(), category.categoryId(),
                CatalogProjection.toCategoryFields(category), revision, Timestamps.nowIso(), event.id()));
        recordApplied(topic, event);
    }

    private void handleDeleted(String topic, CloudEventEnvelope event, EventBatch batch) {
        CatalogPayloads.CategoryDeleted payload = bind(topic, event, CatalogPayloads.CategoryDeleted.class);
        if (payload == null) {
            return;
        }
        Long revision = Optional.ofNullable(payload.categoryRevision()).orElse(event.sequence());
        if (payload.categoryId() == null || revision == null) {
            invalid(topic, event, "category-deleted is missing category_id or category_revision");
            return;
        }
        Instant deletedAt = payload.deletedAt() != null ? payload.deletedAt() : event.time();
        batch.add(writer.categoryTombstone(batch.targetIndex(), payload.categoryId(), revision,
                Timestamps.iso(deletedAt), Timestamps.nowIso(), event.id()));
        recordApplied(topic, event);
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
        log.error("contract drift on {} event {} ({}): {}", topic, event.id(), event.type(), reason);
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
