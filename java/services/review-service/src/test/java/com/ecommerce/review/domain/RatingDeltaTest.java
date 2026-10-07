package com.ecommerce.review.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** ADR-004: only a real aggregate change may move the revision. */
class RatingDeltaTest {

    @Test
    void aCreatedReviewAddsOneToItsBucket() {
        RatingDelta delta = RatingDelta.ofReview(4, true, 1);

        assertThat(delta.star4()).isEqualTo(1);
        assertThat(delta.withMedia()).isEqualTo(1);
        assertThat(delta.totalReviews()).isEqualTo(1);
        assertThat(delta.weightedRating()).isEqualTo(4);
        assertThat(delta.isZero()).isFalse();
    }

    @Test
    void hidingSubtractsExactlyWhatCreatingAdded() {
        RatingDelta added = RatingDelta.ofReview(3, false, 1);
        RatingDelta removed = RatingDelta.ofReview(3, false, -1);

        assertThat(added.plus(removed).isZero()).isTrue();
    }

    @Test
    void aRatingChangeMovesOneReviewBetweenBuckets() {
        RatingDelta delta = RatingDelta.ofReview(2, false, -1)
                .plus(RatingDelta.ofReview(5, false, 1));

        assertThat(delta.star2()).isEqualTo(-1);
        assertThat(delta.star5()).isEqualTo(1);
        // The review count does not move: it is the same review with a new rating.
        assertThat(delta.totalReviews()).isZero();
        assertThat(delta.isZero()).isFalse();
    }

    /**
     * A media replacement that keeps {@code has_media} true is not an aggregate change,
     * and neither is a content-only edit. Both produce a zero delta, which the aggregator
     * applies as nothing at all rather than as a revision bump.
     */
    @Test
    void anEditThatChangesNeitherRatingNorMediaPresenceIsZero() {
        RatingDelta delta = RatingDelta.ofReview(4, true, -1)
                .plus(RatingDelta.ofReview(4, true, 1));

        assertThat(delta.isZero()).isTrue();
    }

    @Test
    void addingMediaToAnExistingReviewMovesOnlyTheMediaCounter() {
        RatingDelta delta = RatingDelta.ofReview(5, false, -1)
                .plus(RatingDelta.ofReview(5, true, 1));

        assertThat(delta.withMedia()).isEqualTo(1);
        assertThat(delta.star5()).isZero();
        assertThat(delta.totalReviews()).isZero();
        assertThat(delta.isZero()).isFalse();
    }
}
