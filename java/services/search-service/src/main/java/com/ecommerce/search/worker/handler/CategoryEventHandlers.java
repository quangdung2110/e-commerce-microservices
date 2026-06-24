package com.ecommerce.search.worker.handler;

import com.ecommerce.search.common.ErrorCode;
import com.ecommerce.search.domain.document.CategoryDocument;
import com.ecommerce.search.domain.event.CategoryCreatedEvent;
import com.ecommerce.search.domain.event.CategoryDeletedEvent;
import com.ecommerce.search.domain.event.CategoryUpdatedEvent;
import com.ecommerce.search.domain.event.CloudEventEnvelope;
import com.ecommerce.search.domain.exception.SearchException;
import com.ecommerce.search.repository.CategoryRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Map;

/**
 * Handlers for category-related events consumed from {@code t.category.events}.
 *
 * <p>Each method maps to one event type. Category updates also propagate
 * changes to product documents via {@code _update_by_query}.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CategoryEventHandlers {

    private final CategoryRepository categoryRepository;
    private final ObjectMapper objectMapper;

    // ─────────────────────────────────────────────────────────────────
    //  CATEGORY_CREATED
    // ─────────────────────────────────────────────────────────────────

    /**
     * Index a new category document. The {@code path} field is required;
     * events without it are silently skipped.
     */
    public void handleCategoryCreated(CloudEventEnvelope envelope) {
        CategoryCreatedEvent event = deserialize(envelope, CategoryCreatedEvent.class);

        if (event.path() == null || event.path().isBlank()) {
            log.warn("CATEGORY_CREATED missing required field 'path', skipping: category_id={}",
                event.categoryId());
            // TODO: increment search_payload_invalid_total{topic=category.events,event_type=CATEGORY_CREATED}
            return;
        }

        CategoryDocument doc = CategoryDocument.builder()
            .categoryId(event.categoryId())
            .name(event.name())
            .slug(event.slug())
            .parentId(event.parentId())
            .level(event.level())
            .isLeaf(event.isLeaf())
            .path(event.path())
            .productCount(0L)
            .isDeleted(false)
            .version(Instant.now())
            .build();

        categoryRepository.index(doc);
        log.debug("CATEGORY_CREATED indexed: category_id={}, path={}", event.categoryId(), event.path());
    }

    // ─────────────────────────────────────────────────────────────────
    //  CATEGORY_UPDATED
    // ─────────────────────────────────────────────────────────────────

    /**
     * Update category name and path, then propagate changes to all SKU
     * documents belonging to this category via {@code _update_by_query}.
     *
     * <p>Structural tree fields (slug, parent_id, level, is_leaf) are
     * considered immutable after creation per design contract.
     */
    public void handleCategoryUpdated(CloudEventEnvelope envelope) {
        CategoryUpdatedEvent event = deserialize(envelope, CategoryUpdatedEvent.class);

        // === 1. Update the category document ===
        String scriptSource = """
            if (ctx._source.version != null && params.updated_at <= ctx._source.version) {
              ctx.op = 'none';
              return;
            }
            ctx._source.name = params.new_name;
            ctx._source.path = params.new_path;
            ctx._source.version = params.updated_at;
            ctx._source.indexed_at = params.indexed_at;
            ctx._source.last_event_id = params.event_id;
            """;
        Instant now = Instant.now();
        Map<String, Object> params = Map.of(
            "new_name", event.newName(),
            "new_path", event.newPath(),
            "updated_at", now.toString(),
            "indexed_at", now.toString(),
            "event_id", envelope.eventId()
        );

        try {
            categoryRepository.updateByScript(event.categoryId(), scriptSource, params);
        } catch (SearchException e) {
            log.warn("CATEGORY_UPDATED: document missing for category_id={}, skipping update",
                event.categoryId(), e);
        }

        // === 2. TODO: _update_by_query on products_write to sync category_name/path ===
        // This will be implemented when the ES query infrastructure is fully wired.
        // For now, product documents will retain stale category_name/path until a
        // Full Reindex is triggered.
        log.debug("CATEGORY_UPDATED: category_id={}, new_name={} — product propagation pending full implementation",
            event.categoryId(), event.newName());
    }

    // ─────────────────────────────────────────────────────────────────
    //  CATEGORY_DELETED
    // ─────────────────────────────────────────────────────────────────

    /**
     * Tombstone the category document. Product documents in the deleted
     * category subtree should have their category fields nullified and
     * product_status set to HIDDEN (via a future _update_by_query).
     */
    public void handleCategoryDeleted(CloudEventEnvelope envelope) {
        CategoryDeletedEvent event = deserialize(envelope, CategoryDeletedEvent.class);

        // === 1. Tombstone the category document ===
        String scriptSource = """
            ctx._source.is_deleted = true;
            ctx._source.deleted_at = params.deleted_at;
            ctx._source.version = params.updated_at;
            ctx._source.indexed_at = params.indexed_at;
            ctx._source.last_event_id = params.event_id;
            """;
        Instant now = Instant.now();
        Map<String, Object> params = Map.of(
            "deleted_at", event.deletedAt() != null ? event.deletedAt().toString() : now.toString(),
            "updated_at", event.updatedAt() != null ? event.updatedAt().toString() : now.toString(),
            "indexed_at", now.toString(),
            "event_id", envelope.eventId()
        );

        try {
            categoryRepository.updateByScript(event.categoryId(), scriptSource, params);
        } catch (SearchException e) {
            log.warn("CATEGORY_DELETED: document missing for category_id={}, skipping tombstone",
                event.categoryId(), e);
        }

        // === 2. TODO: _update_by_query on products_write to nullify category_* ===
        // and set product_status = 'HIDDEN' for all SKUs in the deleted category subtree.
        // This will be implemented when the ES query infrastructure is fully wired.
        log.debug("CATEGORY_DELETED: category_id={} — product nullification pending full implementation",
            event.categoryId());
    }

    // ─────────────────────────────────────────────────────────────────
    //  Internal helpers
    // ─────────────────────────────────────────────────────────────────

    /**
     * Deserialize the envelope payload into the specified event type.
     */
    private <T> T deserialize(CloudEventEnvelope envelope, Class<T> type) {
        try {
            return objectMapper.convertValue(envelope.payload(), type);
        } catch (Exception e) {
            log.error("Failed to deserialize event payload: type={}, eventId={}",
                envelope.eventType(), envelope.eventId(), e);
            throw new SearchException(ErrorCode.INTERNAL_ERROR,
                "Failed to deserialize " + type.getSimpleName(), e);
        }
    }
}
