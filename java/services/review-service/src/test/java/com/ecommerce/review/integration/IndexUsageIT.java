package com.ecommerce.review.integration;

import com.ecommerce.review.domain.ModerationQueueStatus;
import com.ecommerce.review.repository.ReviewRepositoryQueries;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ADR-002: the read path has no cache, so it depends entirely on hitting the right index.
 *
 * <p>A filter added without its partial index is an incident rather than a slowdown, and
 * a sequential scan is the only symptom. {@code EXPLAIN} is the only thing that can catch
 * that before production does, so these tests run it against the statements the
 * repositories actually execute.
 *
 * <p>The fixture is deliberately large enough for the planner to prefer an index. On a
 * table of ten rows PostgreSQL correctly chooses a sequential scan, and a test built on
 * that would assert nothing.
 */
class IndexUsageIT extends AbstractReviewIT {

    private static final int REVIEWS = 4_000;

    @BeforeEach
    void seedSyntheticReviews() {
        // Synthetic only: review text and user identity are PII
        // (docs/review/development.md 3).
        jdbc.sql("""
                INSERT INTO review_eligibilities
                    (id, order_id, product_id, sku_id, variant_name_snapshot, owner_user_id,
                     status, completed_at, expires_at, claimed_at)
                SELECT gen_random_uuid(), 'order-' || g, 'p-' || (g %% 4),
                       'sku-' || (g %% 8), 'variant-' || (g %% 8), gen_random_uuid(),
                       'CLAIMED', now() - interval '10 days', now() + interval '20 days', now()
                  FROM generate_series(1, %d) AS g
                """.formatted(REVIEWS)).update();

        jdbc.sql("""
                INSERT INTO reviews
                    (eligibility_id, product_id, sku_id, author_display_name, rating, content,
                     status, has_media, active_report_count, first_reported_at, hidden_at, created_at)
                SELECT e.id, e.product_id, e.sku_id, 'Khách hàng #AAAAAAAAAAAA',
                       1 + (row_number() OVER (ORDER BY e.order_id) % 5),
                       'synthetic', 'APPROVED',
                       (row_number() OVER (ORDER BY e.order_id) % 3) = 0,
                       0, NULL, NULL,
                       now() - (row_number() OVER (ORDER BY e.order_id) || ' minutes')::interval
                  FROM review_eligibilities e
                """).update();

        // A small moderation backlog in both states, against a large approved table: this
        // is exactly the shape where a missing partial index degenerates into a full scan.
        jdbc.sql("""
                UPDATE reviews SET active_report_count = 2, first_reported_at = now() - interval '1 hour'
                 WHERE id IN (SELECT id FROM reviews ORDER BY created_at LIMIT 20)
                """).update();
        jdbc.sql("""
                UPDATE reviews
                   SET status = 'HIDDEN_BY_REPORT', hidden_at = now(),
                       active_report_count = 10, first_reported_at = now() - interval '2 hours'
                 WHERE id IN (SELECT id FROM reviews ORDER BY created_at DESC LIMIT 20)
                """).update();

        jdbc.sql("ANALYZE reviews").update();
        jdbc.sql("ANALYZE review_eligibilities").update();
    }

    @Test
    void thePublicKeysetQueryUsesTheProductRecentIndex() {
        String plan = explain(ReviewRepositoryQueries.listApprovedSql(false, false, false, false),
                List.of("p-1", 20));

        assertThat(plan).contains("ix_review_product_recent");
        assertThatReviewsAreNotScannedSequentially(plan);
    }

    @Test
    void theSkuFilterUsesTheProductSkuIndex() {
        String plan = explain(ReviewRepositoryQueries.listApprovedSql(true, false, false, false),
                List.of("p-1", "sku-1", 20));

        assertThat(plan).contains("ix_review_product_sku_recent");
        assertThatReviewsAreNotScannedSequentially(plan);
    }

    @Test
    void theRatingFilterUsesTheProductRatingIndex() {
        String plan = explain(ReviewRepositoryQueries.listApprovedSql(false, true, false, false),
                List.of("p-1", 5, 20));

        assertThat(plan).contains("ix_review_product_rating_recent");
        assertThatReviewsAreNotScannedSequentially(plan);
    }

    @Test
    void theMediaFilterUsesTheProductWithMediaIndex() {
        String plan = explain(ReviewRepositoryQueries.listApprovedSql(false, false, true, false),
                List.of("p-1", 20));

        assertThat(plan).contains("ix_review_product_with_media");
        assertThatReviewsAreNotScannedSequentially(plan);
    }

    @Test
    void theKeysetContinuationStillUsesTheSameIndex() {
        String plan = explain(ReviewRepositoryQueries.listApprovedSql(false, false, false, true),
                List.of("p-1", java.time.OffsetDateTime.now(), UUID.randomUUID(), 20));

        assertThat(plan).contains("ix_review_product_recent");
        assertThatReviewsAreNotScannedSequentially(plan);
    }

    /**
     * Both moderation states, and the reason they need their own indexes: every other
     * review index is partial on {@code APPROVED} with no report predicate.
     */
    @Test
    void theReportedQueueUsesItsOwnPartialIndex() {
        String plan = explain(
                ReviewRepositoryQueries.moderationQueueSql(ModerationQueueStatus.REPORTED, false),
                List.of(50));

        assertThat(plan).contains("ix_review_moderation_reported");
        assertThatReviewsAreNotScannedSequentially(plan);
    }

    @Test
    void theHiddenQueueUsesItsOwnPartialIndex() {
        String plan = explain(
                ReviewRepositoryQueries.moderationQueueSql(ModerationQueueStatus.HIDDEN_BY_REPORT, false),
                List.of(50));

        assertThat(plan).contains("ix_review_moderation_hidden");
        assertThatReviewsAreNotScannedSequentially(plan);
    }

    /**
     * The privacy lookups are deadline-bound, so neither may scan a table.
     * {@code ix_eligibility_owner_pending} cannot serve the anonymise: it is partial on
     * {@code PENDING}, while a review only exists once its entitlement is {@code CLAIMED}.
     */
    @Test
    void theAnonymiseLookupUsesTheEligibilityErasureIndex() {
        String plan = explain(ReviewRepositoryQueries.ANONYMIZE_AUTHOR_SQL,
                List.of("Khách hàng ẩn danh", java.time.OffsetDateTime.now(), UUID.randomUUID()));

        assertThat(plan).contains("ix_eligibility_owner_erasure");
        assertThat(plan).doesNotContain("Seq Scan on review_eligibilities");
    }

    @Test
    void theMediaAssetPurgeUsesItsErasureIndex() {
        seedOwnedRows("review_media_assets");
        String plan = explain("""
                UPDATE review_media_assets
                   SET owner_user_id = NULL, owner_erased_at = ?
                 WHERE owner_user_id = ?
                """, List.of(java.time.OffsetDateTime.now(), UUID.randomUUID()));

        assertThat(plan).contains("ix_media_asset_owner_erasure");
        assertThat(plan).doesNotContain("Seq Scan on review_media_assets");
    }

    @Test
    void theReportPurgeUsesItsErasureIndex() {
        seedReports();
        String plan = explain("""
                UPDATE review_reports
                   SET reporter_user_id = NULL, reporter_erased_at = ?
                 WHERE reporter_user_id = ?
                """, List.of(java.time.OffsetDateTime.now(), UUID.randomUUID()));

        assertThat(plan).contains("ix_report_reporter_erasure");
        assertThat(plan).doesNotContain("Seq Scan on review_reports");
    }

    private void seedOwnedRows(String table) {
        jdbc.sql("""
                INSERT INTO review_media_assets
                    (owner_user_id, object_key, media_type, upload_state, content_type, byte_size, expires_at)
                SELECT gen_random_uuid(), 'review-media/image/' || g, 'IMAGE', 'ISSUED',
                       'image/jpeg', 1024, now() + interval '15 minutes'
                  FROM generate_series(1, 4000) AS g
                """).update();
        jdbc.sql("ANALYZE " + table).update();
    }

    private void seedReports() {
        jdbc.sql("""
                INSERT INTO review_reports (review_id, reporter_user_id, reason, status)
                SELECT r.id, gen_random_uuid(), 'SPAM', 'PENDING'
                  FROM reviews r
                """).update();
        jdbc.sql("ANALYZE review_reports").update();
    }

    /**
     * A parallel plan prints "Parallel Seq Scan"; both spellings are the failure this
     * asserts against.
     */
    private static void assertThatReviewsAreNotScannedSequentially(String plan) {
        assertThat(plan).doesNotContain("Seq Scan on reviews");
    }

    private String explain(String sql, List<Object> params) {
        return String.join("\n", jdbc.sql("EXPLAIN " + sql).params(params).query(String.class).list());
    }
}
