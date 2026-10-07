package com.ecommerce.review.integration;

import com.ecommerce.review.domain.RatingAggregate;
import com.ecommerce.review.outbox.ReviewEventType;
import com.ecommerce.review.repository.RatingAggregateRepository;
import com.ecommerce.review.service.RatingAggregateService;
import com.ecommerce.review.support.ContractSchemas;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** WF-REVIEW-008 and ADR-005: the aggregate moves once per event, never twice. */
class RatingAggregateIT extends AbstractReviewIT {

    private static final String PRODUCT = "p-agg";

    @Autowired
    private RatingAggregateService ratingAggregates;

    @Autowired
    private RatingAggregateRepository aggregates;

    private final ObjectMapper json = new ObjectMapper();

    private void applyCreated(UUID eventId, int rating, boolean hasMedia) {
        ratingAggregates.apply(eventId, ReviewEventType.REVIEW_CREATED,
                lifecycleData(rating, hasMedia), properties.topics().review(), 0, nextOffset());
    }

    private JsonNode lifecycleData(int rating, boolean hasMedia) {
        return json.createObjectNode()
                .put("review_id", UUID.randomUUID().toString())
                .put("product_id", PRODUCT)
                .put("sku_id", "sku-1")
                .put("rating", rating)
                .put("has_media", hasMedia);
    }

    @Test
    void createdReviewsBuildTheBucketsTotalAndAverage() {
        applyCreated(UUID.randomUUID(), 5, true);
        applyCreated(UUID.randomUUID(), 4, false);
        applyCreated(UUID.randomUUID(), 3, true);

        RatingAggregate aggregate = aggregates.find(PRODUCT).orElseThrow();

        assertThat(aggregate.totalReviews()).isEqualTo(3);
        assertThat(aggregate.total5Star()).isEqualTo(1);
        assertThat(aggregate.total4Star()).isEqualTo(1);
        assertThat(aggregate.total3Star()).isEqualTo(1);
        assertThat(aggregate.totalWithMedia()).isEqualTo(2);
        assertThat(aggregate.averageRating()).isEqualByComparingTo(new BigDecimal("4.00"));
        // One revision per mutation, starting at 1.
        assertThat(aggregate.reviewRevision()).isEqualTo(3);
        assertThat(outboxCount(ReviewEventType.REVIEW_METRICS_UPDATED.type())).isEqualTo(3);
    }

    /** REVIEW-INV-008: a redelivered event is a receipt conflict and a no-op. */
    @Test
    void aDuplicateEventDoesNotCountTwice() {
        UUID eventId = UUID.randomUUID();
        JsonNode data = lifecycleData(5, false);
        // The same log position twice, which is what a redelivery looks like.
        long offset = nextOffset();

        ratingAggregates.apply(eventId, ReviewEventType.REVIEW_CREATED, data,
                properties.topics().review(), 0, offset);
        ratingAggregates.apply(eventId, ReviewEventType.REVIEW_CREATED, data,
                properties.topics().review(), 0, offset);

        RatingAggregate aggregate = aggregates.find(PRODUCT).orElseThrow();
        assertThat(aggregate.totalReviews()).isEqualTo(1);
        assertThat(aggregate.reviewRevision()).isEqualTo(1);
        assertThat(outboxCount(ReviewEventType.REVIEW_METRICS_UPDATED.type())).isEqualTo(1);
    }

    @Test
    void hidingSubtractsAndRestoringAddsBack() {
        applyCreated(UUID.randomUUID(), 5, true);

        ratingAggregates.apply(UUID.randomUUID(), ReviewEventType.REVIEW_HIDDEN,
                lifecycleData(5, true), properties.topics().review(), 0, nextOffset());
        assertThat(aggregates.find(PRODUCT).orElseThrow().totalReviews()).isZero();

        ratingAggregates.apply(UUID.randomUUID(), ReviewEventType.REVIEW_APPROVED,
                lifecycleData(5, true), properties.topics().review(), 0, nextOffset());
        assertThat(aggregates.find(PRODUCT).orElseThrow().totalReviews()).isEqualTo(1);
    }

    @Test
    void anEditMovesOneReviewBetweenBuckets() {
        applyCreated(UUID.randomUUID(), 2, false);

        ratingAggregates.apply(UUID.randomUUID(), ReviewEventType.REVIEW_UPDATED,
                json.createObjectNode()
                        .put("review_id", UUID.randomUUID().toString())
                        .put("product_id", PRODUCT)
                        .put("sku_id", "sku-1")
                        .put("old_rating", 2)
                        .put("new_rating", 5)
                        .put("old_has_media", false)
                        .put("new_has_media", false)
                        .put("affects_aggregate", true),
                properties.topics().review(), 0, nextOffset());

        RatingAggregate aggregate = aggregates.find(PRODUCT).orElseThrow();
        assertThat(aggregate.total2Star()).isZero();
        assertThat(aggregate.total5Star()).isEqualTo(1);
        assertThat(aggregate.totalReviews()).isEqualTo(1);
        assertThat(aggregate.averageRating()).isEqualByComparingTo(new BigDecimal("5.00"));
    }

    /** ADR-004: a content-only edit is acknowledged without a delta or a revision bump. */
    @Test
    void aContentOnlyEditLeavesTheAggregateAndRevisionAlone() {
        applyCreated(UUID.randomUUID(), 4, false);
        long revisionBefore = aggregates.find(PRODUCT).orElseThrow().reviewRevision();

        ratingAggregates.apply(UUID.randomUUID(), ReviewEventType.REVIEW_UPDATED,
                json.createObjectNode()
                        .put("review_id", UUID.randomUUID().toString())
                        .put("product_id", PRODUCT)
                        .put("sku_id", "sku-1")
                        .put("old_rating", 4)
                        .put("new_rating", 4)
                        .put("old_has_media", false)
                        .put("new_has_media", false)
                        .put("affects_aggregate", false),
                properties.topics().review(), 0, nextOffset());

        assertThat(aggregates.find(PRODUCT).orElseThrow().reviewRevision()).isEqualTo(revisionBefore);
        assertThat(outboxCount(ReviewEventType.REVIEW_METRICS_UPDATED.type())).isEqualTo(1);
    }

    /** was_counted decides whether a removal subtracts anything at all. */
    @Test
    void removingAReviewThatWasNotCountedChangesNothing() {
        applyCreated(UUID.randomUUID(), 4, false);
        long revisionBefore = aggregates.find(PRODUCT).orElseThrow().reviewRevision();

        ratingAggregates.apply(UUID.randomUUID(), ReviewEventType.REVIEW_REMOVED,
                json.createObjectNode()
                        .put("review_id", UUID.randomUUID().toString())
                        .put("product_id", PRODUCT)
                        .put("sku_id", "sku-1")
                        .put("rating", 4)
                        .put("has_media", false)
                        .put("was_counted", false),
                properties.topics().review(), 0, nextOffset());

        assertThat(aggregates.find(PRODUCT).orElseThrow().totalReviews()).isEqualTo(1);
        assertThat(aggregates.find(PRODUCT).orElseThrow().reviewRevision()).isEqualTo(revisionBefore);
    }

    /** The snapshot Search consumes mirrors review_revision into sequence. */
    @Test
    void theMetricsSnapshotIsSchemaValidAndCarriesTheRevisionAsSequence() {
        applyCreated(UUID.randomUUID(), 5, true);

        JsonNode envelope = outboxPayloads(ReviewEventType.REVIEW_METRICS_UPDATED.type()).get(0);

        assertThat(ContractSchemas.validate(
                ReviewEventType.REVIEW_METRICS_UPDATED.schemaFileName(), envelope)).isEmpty();
        assertThat(envelope.path("sequence").asLong())
                .isEqualTo(envelope.path("data").path("review_revision").asLong());
        assertThat(envelope.path("subject").asText())
                .isEqualTo("product-rating-aggregates/" + PRODUCT);
    }

    /** REVIEW_METRICS_UPDATED must never feed the aggregator back into itself. */
    @Test
    void theAggregatorIgnoresItsOwnSnapshot() {
        assertThat(ReviewEventType.REVIEW_METRICS_UPDATED.affectsRatingAggregate()).isFalse();
        assertThat(ReviewEventType.REVIEW_REPLY_NOTIFICATION_REQUESTED.affectsRatingAggregate()).isFalse();
        assertThat(ReviewEventType.REVIEW_REMOVAL_NOTIFICATION_REQUESTED.affectsRatingAggregate()).isFalse();
    }

    /** Reconciliation repairs real drift and moves the revision forward. */
    @Test
    void reconciliationRepairsDriftWithARevisionBump() {
        applyCreated(UUID.randomUUID(), 5, false);
        jdbc.sql("""
                UPDATE product_rating_aggregates
                   SET total_5_star = 9, total_reviews = 9, average_rating = 5.00
                 WHERE product_id = ?
                """).param(PRODUCT).update();
        long revisionBefore = aggregates.find(PRODUCT).orElseThrow().reviewRevision();

        int repaired = ratingAggregates.reconcile();

        assertThat(repaired).isEqualTo(1);
        RatingAggregate aggregate = aggregates.find(PRODUCT).orElseThrow();
        // The recount reads `reviews`, and this suite created none, so the repair is zero.
        assertThat(aggregate.totalReviews()).isZero();
        assertThat(aggregate.reviewRevision()).isGreaterThan(revisionBefore);
    }
}
