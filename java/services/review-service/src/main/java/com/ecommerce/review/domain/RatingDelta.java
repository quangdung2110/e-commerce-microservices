package com.ecommerce.review.domain;

/**
 * The change one lifecycle fact makes to a product rating aggregate.
 *
 * <p>A delta that is entirely zero is not applied at all: it would bump
 * {@code review_revision} and publish a snapshot that says nothing changed, which is
 * exactly what ADR-004 forbids. A content-only edit and a {@code REVIEW_REMOVED} whose
 * {@code was_counted} is false both produce one.
 */
public record RatingDelta(
        long star1,
        long star2,
        long star3,
        long star4,
        long star5,
        long withMedia) {

    public static final RatingDelta ZERO = new RatingDelta(0, 0, 0, 0, 0, 0);

    /** One review entering ({@code +1}) or leaving ({@code -1}) the aggregate. */
    public static RatingDelta ofReview(int rating, boolean hasMedia, int sign) {
        return new RatingDelta(
                rating == 1 ? sign : 0,
                rating == 2 ? sign : 0,
                rating == 3 ? sign : 0,
                rating == 4 ? sign : 0,
                rating == 5 ? sign : 0,
                hasMedia ? sign : 0);
    }

    public RatingDelta plus(RatingDelta other) {
        return new RatingDelta(
                star1 + other.star1,
                star2 + other.star2,
                star3 + other.star3,
                star4 + other.star4,
                star5 + other.star5,
                withMedia + other.withMedia);
    }

    public boolean isZero() {
        return star1 == 0 && star2 == 0 && star3 == 0 && star4 == 0 && star5 == 0 && withMedia == 0;
    }

    public long totalReviews() {
        return star1 + star2 + star3 + star4 + star5;
    }

    public long weightedRating() {
        return star1 + 2 * star2 + 3 * star3 + 4 * star4 + 5 * star5;
    }
}
