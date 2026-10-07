package com.ecommerce.review.service;

import com.ecommerce.review.domain.RatingAggregate;
import com.ecommerce.review.domain.RatingDelta;
import com.ecommerce.review.metrics.ReviewMetrics;
import com.ecommerce.review.outbox.ReviewEventData;
import com.ecommerce.review.outbox.ReviewEventFactory;
import com.ecommerce.review.outbox.ReviewEventType;
import com.ecommerce.review.repository.EventReceiptRepository;
import com.ecommerce.review.repository.OutboxRepository;
import com.ecommerce.review.repository.RatingAggregateRepository;
import com.ecommerce.review.repository.ReviewRepository;
import com.ecommerce.review.repository.TransactionClock;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.UUID;

/**
 * WF-REVIEW-008: the idempotent rating aggregator, and the reconciliation repair.
 *
 * <p>The receipt is claimed first, in the same transaction as the effect. A redelivered
 * event conflicts on the receipt and commits as a no-op, which is what turns crash-after-
 * commit and consumer rebalance from a double count into nothing at all (ADR-005).
 */
@Service
public class RatingAggregateService {

    private static final Logger log = LoggerFactory.getLogger(RatingAggregateService.class);

    private final RatingAggregateRepository aggregates;
    private final ReviewRepository reviews;
    private final EventReceiptRepository receipts;
    private final OutboxRepository outbox;
    private final ReviewEventFactory events;
    private final TransactionClock clock;
    private final ObjectMapper eventMapper;
    private final ReviewMetrics metrics;
    private final TransactionTemplate transactions;

    public RatingAggregateService(RatingAggregateRepository aggregates,
                                  ReviewRepository reviews,
                                  EventReceiptRepository receipts,
                                  OutboxRepository outbox,
                                  ReviewEventFactory events,
                                  TransactionClock clock,
                                  @Qualifier("eventObjectMapper") ObjectMapper eventMapper,
                                  ReviewMetrics metrics,
                                  TransactionTemplate transactions) {
        this.aggregates = aggregates;
        this.reviews = reviews;
        this.receipts = receipts;
        this.outbox = outbox;
        this.events = events;
        this.clock = clock;
        this.eventMapper = eventMapper;
        this.metrics = metrics;
        this.transactions = transactions;
    }

    /**
     * Applies one lifecycle fact.
     *
     * <p>Only the five aggregate-affecting types reach here; everything else on the topic
     * is acknowledged without a durable effect, {@code REVIEW_METRICS_UPDATED} included,
     * because consuming its own snapshot would be a loop.
     */
    @Transactional
    public void apply(UUID eventId, ReviewEventType type, JsonNode data,
                      String topic, int partition, long offset) {
        if (!receipts.claim(EventReceiptRepository.RATING_AGGREGATOR, eventId, topic, partition, offset)) {
            metrics.consumerDuplicate(EventReceiptRepository.RATING_AGGREGATOR);
            return;
        }

        String productId = data.path("product_id").asText(null);
        RatingDelta delta = deltaOf(type, data);
        if (productId == null || delta.isZero()) {
            // A content-only edit and a REVIEW_REMOVED that was not counted change
            // nothing the aggregate tracks. Bumping the revision for them would publish a
            // snapshot that says nothing changed (ADR-004).
            return;
        }

        Instant now = clock.now();
        RatingAggregate updated = aggregates.applyDelta(productId, delta);
        writeMetricsSnapshot(updated, now);
    }

    private RatingDelta deltaOf(ReviewEventType type, JsonNode data) {
        return switch (type) {
            case REVIEW_CREATED, REVIEW_APPROVED -> {
                ReviewEventData.Lifecycle payload = read(data, ReviewEventData.Lifecycle.class);
                yield RatingDelta.ofReview(payload.rating(), payload.hasMedia(), 1);
            }
            case REVIEW_HIDDEN -> {
                ReviewEventData.Lifecycle payload = read(data, ReviewEventData.Lifecycle.class);
                yield RatingDelta.ofReview(payload.rating(), payload.hasMedia(), -1);
            }
            case REVIEW_REMOVED -> {
                ReviewEventData.Removed payload = read(data, ReviewEventData.Removed.class);
                yield payload.wasCounted()
                        ? RatingDelta.ofReview(payload.rating(), payload.hasMedia(), -1)
                        : RatingDelta.ZERO;
            }
            case REVIEW_UPDATED -> {
                ReviewEventData.Updated payload = read(data, ReviewEventData.Updated.class);
                yield payload.affectsAggregate()
                        ? RatingDelta.ofReview(payload.oldRating(), payload.oldHasMedia(), -1)
                                .plus(RatingDelta.ofReview(payload.newRating(), payload.newHasMedia(), 1))
                        : RatingDelta.ZERO;
            }
            default -> RatingDelta.ZERO;
        };
    }

    private <T> T read(JsonNode data, Class<T> type) {
        try {
            return eventMapper.treeToValue(data, type);
        } catch (Exception e) {
            // A malformed delta must not silently produce a wrong rating: the transaction
            // rolls back, receipt included, and the record retries or dead-letters.
            throw new IllegalStateException("Review lifecycle payload does not bind to " + type.getSimpleName(), e);
        }
    }

    /**
     * WF-REVIEW-011: recounts every product and repairs real drift.
     *
     * <p>A repair increments the revision and publishes a snapshot; without the bump,
     * Search would discard the corrected values as stale. Duplicate deliveries no longer
     * cause drift, so anything found here is corruption, a lost event or a bug.
     *
     * @return how many products needed repair
     */
    public int reconcile() {
        int repaired = 0;
        for (String productId : transactions.execute(status -> reviews.productIdsForReconciliation())) {
            if (Boolean.TRUE.equals(transactions.execute(status -> reconcileOne(productId)))) {
                repaired++;
            }
        }
        return repaired;
    }

    private boolean reconcileOne(String productId) {
        ReviewRepository.ApprovedBuckets actual = reviews.recountApproved(productId);
        RatingAggregate current = aggregates.find(productId).orElse(null);
        boolean matches = current != null
                && current.total1Star() == actual.star1()
                && current.total2Star() == actual.star2()
                && current.total3Star() == actual.star3()
                && current.total4Star() == actual.star4()
                && current.total5Star() == actual.star5()
                && current.totalWithMedia() == actual.withMedia()
                && current.totalReviews() == actual.total();
        if (matches) {
            return false;
        }
        metrics.aggregateDrift();
        log.warn("rating aggregate drift repaired for one product; recounted {} approved reviews",
                actual.total());
        RatingAggregate repaired = aggregates.reconcile(productId, actual.star1(), actual.star2(),
                actual.star3(), actual.star4(), actual.star5(), actual.withMedia());
        writeMetricsSnapshot(repaired, clock.now());
        return true;
    }

    /**
     * Writes the Search-facing snapshot in the same transaction as the mutation that
     * produced it (REVIEW-INV-006). {@code sequence} mirrors {@code review_revision}.
     */
    private void writeMetricsSnapshot(RatingAggregate aggregate, Instant now) {
        outbox.append(events.metrics(new ReviewEventData.Metrics(
                aggregate.productId(),
                aggregate.averageRating(),
                aggregate.totalReviews(),
                aggregate.totalWithMedia(),
                aggregate.reviewRevision(),
                aggregate.updatedAt() == null ? now : aggregate.updatedAt()),
                now));
    }
}
