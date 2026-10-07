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

import java.io.IOException;
import java.time.Instant;
import java.util.Optional;

/**
 * {@code WF-SEARCH-008} — projects the Review rating aggregate.
 *
 * <p>Rating is owned per product but stored on every SKU of that product, so this is the
 * one write path that uses {@code _update_by_query}. It runs synchronously per event
 * rather than through the batch, because the operation is a query, not a bulk item.
 *
 * <p>Matching no document is not a failure: the product may have no indexed SKU yet.
 */
@Component
public class ReviewEventHandler {

    private static final Logger log = LoggerFactory.getLogger(ReviewEventHandler.class);

    private final ObjectMapper objectMapper;
    private final ProjectionWriter writer;
    private final SearchMetrics metrics;

    public ReviewEventHandler(ObjectMapper esObjectMapper, ProjectionWriter writer, SearchMetrics metrics) {
        this.objectMapper = esObjectMapper;
        this.writer = writer;
        this.metrics = metrics;
    }

    /**
     * @return false when the write hit a transient failure and the offset must not be
     *         committed
     */
    public boolean handle(String topic, CloudEventEnvelope event, String targetIndex) {
        CatalogPayloads.RatingAggregate payload;
        try {
            payload = objectMapper.treeToValue(event.data(), CatalogPayloads.RatingAggregate.class);
        } catch (Exception e) {
            invalid(topic, event, "payload does not bind to a rating aggregate: " + e.getMessage());
            return true;
        }
        Long revision = Optional.ofNullable(payload.reviewRevision()).orElse(event.sequence());
        if (payload.productId() == null || payload.productId().isBlank() || revision == null) {
            invalid(topic, event, "rating aggregate is missing product_id or review_revision");
            return true;
        }

        double ratingAvg = payload.averageRating() == null ? 0.0 : payload.averageRating();
        long ratingCount = payload.totalReviews() == null ? 0L : payload.totalReviews();

        try {
            var response = writer.reviewMetricsPatch(targetIndex, payload.productId(), ratingAvg, ratingCount,
                    revision, Timestamps.nowIso(), event.id());
            if (response.updated() != null && response.updated() == 0) {
                metrics.documentMissing(topic, event.type());
            }
            metrics.indexing(topic, event.type(), "applied");
            if (event.time() != null) {
                metrics.indexingLag(topic, java.time.Duration.between(event.time(), Instant.now()));
            }
            return true;
        } catch (IOException e) {
            log.warn("rating projection for product {} failed transiently: {}", payload.productId(), e.getMessage());
            metrics.indexing(topic, event.type(), "retry");
            return false;
        }
    }

    private void invalid(String topic, CloudEventEnvelope event, String reason) {
        log.error("contract violation on {} event {}: {}", topic, event.id(), reason);
        metrics.payloadInvalid(topic, event.type());
        metrics.indexing(topic, event.type(), "invalid");
    }
}
