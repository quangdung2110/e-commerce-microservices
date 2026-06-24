package com.ecommerce.search.worker.handler;

import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import co.elastic.clients.elasticsearch.core.UpdateByQueryResponse;
import com.ecommerce.search.common.ErrorCode;
import com.ecommerce.search.config.SearchProperties;
import com.ecommerce.search.domain.event.CloudEventEnvelope;
import com.ecommerce.search.domain.event.ReviewMetricsUpdatedEvent;
import com.ecommerce.search.domain.exception.SearchException;
import com.ecommerce.search.repository.es.UpdateByQueryExecutor;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Map;

/**
 * Handler for {@code REVIEW_METRICS_UPDATED} events consumed from
 * {@code t.review.events}.
 *
 * <p><strong>Phase 21:</strong> This handler does <em>not</em> use a script-based
 * guard ordering. It relies solely on Kafka partition ordering (keyed by
 * {@code product_id}) and Elasticsearch's internal {@code _seq_no} for
 * idempotency via {@code _update_by_query}.
 *
 * <p>The {@code total_with_media} field from upstream is intentionally ignored.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ReviewEventHandlers {

    private final UpdateByQueryExecutor updateByQueryExecutor;
    private final SearchProperties properties;
    private final ObjectMapper objectMapper;

    /**
     * Update {@code product_rating_avg} and {@code product_rating_count} on
     * all SKU documents of the product via {@code _update_by_query}.
     *
     * <p>No script guard — partition ordering ensures correctness.
     */
    public void handleReviewMetricsUpdated(CloudEventEnvelope envelope) {
        ReviewMetricsUpdatedEvent event = deserialize(envelope, ReviewMetricsUpdatedEvent.class);

        String scriptSource = """
            ctx._source.product_rating_avg = params.average_rating;
            ctx._source.product_rating_count = params.total_reviews;
            ctx._source.indexed_at = params.indexed_at;
            ctx._source.last_event_id = params.event_id;
            """;

        Map<String, Object> params = Map.of(
            "average_rating", event.averageRating(),
            "total_reviews", event.totalReviews(),
            "indexed_at", Instant.now().toString(),
            "event_id", envelope.eventId()
        );

        Query query = Query.of(q -> q.term(t -> t.field("product_id").value(event.productId())));

        try {
            UpdateByQueryResponse response = updateByQueryExecutor.updateByQuery(
                properties.getEs().getIndexAliases().getProducts(),
                query,
                scriptSource,
                params
            );

            log.debug("REVIEW_METRICS_UPDATED: product_id={}, updated={}, total={}",
                event.productId(), response.updated(), response.total());
        } catch (Exception e) {
            log.warn("REVIEW_METRICS_UPDATED: _update_by_query failed for product_id={}",
                event.productId(), e);
        }
    }

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
