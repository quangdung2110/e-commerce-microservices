package com.ecommerce.review.repository;

import com.ecommerce.review.domain.ModerationQueueStatus;

/**
 * The review statements whose <em>query plan</em> is part of the design, not just their
 * result.
 *
 * <p>Review has no cache in front of PostgreSQL (ADR-002), so each of these depends on
 * reaching a specific partial index: a filter added without its index turns into a
 * sequential scan over the whole review table, and the only symptom is latency in
 * production. They live here so the index-usage test can run {@code EXPLAIN} on the exact
 * statement the repository executes, rather than on a copy that could drift away from it.
 */
public final class ReviewRepositoryQueries {

    /** Every column of {@code reviews}, in the order the row mapper reads them. */
    public static final String REVIEW_COLUMNS = """
            id, eligibility_id, product_id, sku_id, author_display_name, author_anonymized_at,
            rating, content, edit_count, status, active_report_count, has_media,
            first_reported_at, hidden_at, created_at, updated_at
            """;

    /**
     * The privacy anonymise.
     *
     * <p>It reaches the subject through {@code ix_eligibility_owner_erasure}.
     * {@code ix_eligibility_owner_pending} cannot serve it: that index is partial on
     * {@code PENDING}, while a review only exists once its entitlement is {@code CLAIMED} —
     * precisely the rows outside it (WF-REVIEW-010).
     */
    public static final String ANONYMIZE_AUTHOR_SQL = """
            UPDATE reviews r
               SET author_display_name = ?, author_anonymized_at = ?
              FROM review_eligibilities e
             WHERE e.id = r.eligibility_id
               AND e.owner_user_id = ?
               AND r.author_anonymized_at IS NULL
            """;

    /**
     * The buyer's own reviews, newest first, whatever their visibility.
     *
     * <p>Reviews carry no owner column, so the owner is reached through the entitlement
     * ({@code docs/review/data.md 3}) by {@code ix_eligibility_owner_erasure}. A buyer's
     * entitlements are bounded by the orders they placed, so the sort over that handful of
     * rows is cheap, and a {@code CLAIMED} entitlement is exactly one that has a review.
     */
    public static String listOwnedSql(boolean keyset) {
        return """
                SELECT r.id, r.eligibility_id, r.product_id, r.sku_id, r.author_display_name,
                       r.author_anonymized_at, r.rating, r.content, r.edit_count, r.status,
                       r.active_report_count, r.has_media, r.first_reported_at, r.hidden_at,
                       r.created_at, r.updated_at, e.variant_name_snapshot,
                       e.product_name_snapshot, e.product_image_url_snapshot
                  FROM review_eligibilities e
                  JOIN reviews r ON r.eligibility_id = e.id
                 WHERE e.owner_user_id = ?
                """
                + (keyset ? " AND (r.created_at, r.id) < (?, ?)" : "")
                + " ORDER BY r.created_at DESC, r.id DESC LIMIT ?";
    }

    private ReviewRepositoryQueries() {
    }

    /**
     * The public listing for one combination of filters.
     *
     * <p>Each filter selects a different partial index, and none of them relaxes the
     * {@code status = 'APPROVED'} predicate, so a hidden or removed review can never
     * appear on any of these paths ({@code docs/review/data.md 10}).
     */
    public static String listApprovedSql(boolean bySku, boolean byRating,
                                         boolean hasMediaOnly, boolean keyset) {
        StringBuilder sql = new StringBuilder("""
                SELECT r.id, r.eligibility_id, r.product_id, r.sku_id, r.author_display_name,
                       r.author_anonymized_at, r.rating, r.content, r.edit_count, r.status,
                       r.active_report_count, r.has_media, r.first_reported_at, r.hidden_at,
                       r.created_at, r.updated_at, e.variant_name_snapshot
                  FROM reviews r
                  JOIN review_eligibilities e ON e.id = r.eligibility_id
                 WHERE r.status = 'APPROVED' AND r.product_id = ?
                """);
        if (bySku) {
            sql.append(" AND r.sku_id = ?");
        }
        if (byRating) {
            sql.append(" AND r.rating = ?");
        }
        if (hasMediaOnly) {
            sql.append(" AND r.has_media = TRUE");
        }
        if (keyset) {
            sql.append(" AND (r.created_at, r.id) < (?, ?)");
        }
        return sql.append(" ORDER BY r.created_at DESC, r.id DESC LIMIT ?").toString();
    }

    /**
     * The moderation queue for one of its two states.
     *
     * <p>Without {@code ix_review_moderation_reported} and
     * {@code ix_review_moderation_hidden} this scans the whole review table: every other
     * review index is partial on {@code APPROVED} and carries no report predicate
     * (WF-REVIEW-007).
     */
    public static String moderationQueueSql(ModerationQueueStatus status, boolean keyset) {
        String predicate = status == ModerationQueueStatus.REPORTED
                ? "status = 'APPROVED' AND active_report_count > 0"
                : "status = 'HIDDEN_BY_REPORT'";
        return "SELECT " + REVIEW_COLUMNS + " FROM reviews WHERE " + predicate
                + (keyset ? " AND (first_reported_at, id) > (?, ?)" : "")
                + " ORDER BY first_reported_at, id LIMIT ?";
    }
}
