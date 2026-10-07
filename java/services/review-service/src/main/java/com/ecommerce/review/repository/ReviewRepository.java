package com.ecommerce.review.repository;

import com.ecommerce.review.domain.ModerationQueueStatus;
import com.ecommerce.review.domain.OwnedReview;
import com.ecommerce.review.domain.Review;
import com.ecommerce.review.domain.ReviewStatus;
import com.ecommerce.review.domain.ReviewWithVariant;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * {@code reviews}: the current public representation of a review.
 *
 * <p>Every read that serves a public surface carries {@code status = 'APPROVED'} in its
 * predicate so it uses one of the partial indexes; there is no cache in front of any of
 * this (ADR-002).
 */
@Repository
public class ReviewRepository {

    private static final String COLUMNS = ReviewRepositoryQueries.REVIEW_COLUMNS;

    private static final RowMapper<Review> MAPPER = (rs, row) -> new Review(
            Rows.uuid(rs, "id"),
            Rows.uuid(rs, "eligibility_id"),
            rs.getString("product_id"),
            rs.getString("sku_id"),
            rs.getString("author_display_name"),
            Rows.instant(rs, "author_anonymized_at"),
            rs.getInt("rating"),
            rs.getString("content"),
            rs.getInt("edit_count"),
            Rows.enumValue(rs, "status", ReviewStatus.class),
            rs.getInt("active_report_count"),
            rs.getBoolean("has_media"),
            Rows.instant(rs, "first_reported_at"),
            Rows.instant(rs, "hidden_at"),
            Rows.instant(rs, "created_at"),
            Rows.instant(rs, "updated_at"));

    private static final RowMapper<ReviewWithVariant> WITH_VARIANT =
            (rs, row) -> new ReviewWithVariant(MAPPER.mapRow(rs, row), rs.getString("variant_name_snapshot"));

    private static final RowMapper<OwnedReview> OWNED = (rs, row) -> new OwnedReview(
            MAPPER.mapRow(rs, row),
            rs.getString("variant_name_snapshot"),
            rs.getString("product_name_snapshot"),
            rs.getString("product_image_url_snapshot"));

    private final JdbcClient jdbc;

    public ReviewRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Inserts the review of a just-claimed entitlement.
     *
     * <p>{@code uq_review_eligibility} is the final race protection behind the
     * conditional claim, and the composite foreign key rejects any product/SKU that does
     * not match the entitlement (REVIEW-INV-002, REVIEW-INV-003).
     */
    public Review insert(UUID eligibilityId,
                         String productId,
                         String skuId,
                         String authorDisplayName,
                         int rating,
                         String content,
                         boolean hasMedia) {
        return jdbc.sql("""
                INSERT INTO reviews
                    (eligibility_id, product_id, sku_id, author_display_name, rating, content,
                     edit_count, status, has_media)
                VALUES (?, ?, ?, ?, ?, ?, 0, 'APPROVED', ?)
                RETURNING
                """ + COLUMNS)
                .param(eligibilityId)
                .param(productId)
                .param(skuId)
                .param(authorDisplayName)
                .param(rating)
                .param(content)
                .param(hasMedia)
                .query(MAPPER).single();
    }

    public Optional<Review> findById(UUID id) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM reviews WHERE id = ?")
                .param(id).query(MAPPER).optional();
    }

    /** Locks the review row for edit, report, dismiss, reply and takedown. */
    public Optional<Review> findByIdForUpdate(UUID id) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM reviews WHERE id = ? FOR UPDATE")
                .param(id).query(MAPPER).optional();
    }

    public Optional<UUID> findIdByEligibilityId(UUID eligibilityId) {
        return jdbc.sql("SELECT id FROM reviews WHERE eligibility_id = ?")
                .param(eligibilityId).query(UUID.class).optional();
    }

    /** One review with the entitlement snapshots, for the owner surface. */
    public Optional<OwnedReview> findOwned(UUID id) {
        return jdbc.sql("""
                SELECT r.id, r.eligibility_id, r.product_id, r.sku_id, r.author_display_name,
                       r.author_anonymized_at, r.rating, r.content, r.edit_count, r.status,
                       r.active_report_count, r.has_media, r.first_reported_at, r.hidden_at,
                       r.created_at, r.updated_at, e.variant_name_snapshot,
                       e.product_name_snapshot, e.product_image_url_snapshot
                  FROM reviews r
                  JOIN review_eligibilities e ON e.id = r.eligibility_id
                 WHERE r.id = ?
                """).param(id).query(OWNED).optional();
    }

    /**
     * The buyer's own reviews, newest first, keyset on {@code (created_at, id)}.
     *
     * <p>Every visibility is included: the owner keeps seeing a hidden or removed review
     * as theirs, with no signed media URL and no moderation detail.
     */
    public List<OwnedReview> listOwned(UUID ownerUserId, Instant afterCreatedAt, UUID afterId, int limit) {
        var spec = jdbc.sql(ReviewRepositoryQueries.listOwnedSql(afterCreatedAt != null)).param(ownerUserId);
        if (afterCreatedAt != null) {
            spec = spec.param(OffsetDateTime.ofInstant(afterCreatedAt, ZoneOffset.UTC)).param(afterId);
        }
        return spec.param(limit).query(OWNED).list();
    }

    /**
     * The public product listing: only {@code APPROVED}, newest first, keyset on
     * {@code (created_at, id)}.
     *
     * <p>Each optional filter selects a different partial index
     * ({@code docs/review/data.md 10}); none of them changes the {@code APPROVED}
     * predicate, so a hidden or removed review can never appear.
     */
    public List<ReviewWithVariant> listApproved(String productId,
                                                String skuId,
                                                Integer rating,
                                                boolean hasMediaOnly,
                                                Instant afterCreatedAt,
                                                UUID afterId,
                                                int limit) {
        List<Object> params = new ArrayList<>();
        params.add(productId);
        if (skuId != null) {
            params.add(skuId);
        }
        if (rating != null) {
            params.add(rating);
        }
        if (afterCreatedAt != null) {
            params.add(OffsetDateTime.ofInstant(afterCreatedAt, ZoneOffset.UTC));
            params.add(afterId);
        }
        params.add(limit);
        String sql = ReviewRepositoryQueries.listApprovedSql(
                skuId != null, rating != null, hasMediaOnly, afterCreatedAt != null);
        return jdbc.sql(sql).params(params).query(WITH_VARIANT).list();
    }


    /** The bounded preview the PDP summary returns; same predicate as the public list. */
    public List<ReviewWithVariant> previewApproved(String productId, int limit) {
        return listApproved(productId, null, null, false, null, null, limit);
    }

    /**
     * The one admin moderation queue.
     *
     * <p>Both states share the ascending {@code (first_reported_at, id)} keyset, so a
     * page means the same thing before and after the threshold hides a review and the
     * longest-open case comes first. {@code REPORTED} reads
     * {@code ix_review_moderation_reported} and {@code HIDDEN_BY_REPORT} reads
     * {@code ix_review_moderation_hidden}; every other review index is partial on
     * {@code APPROVED} with no report predicate, so without these two this query would
     * scan the table (WF-REVIEW-007).
     */
    public List<Review> listModerationQueue(ModerationQueueStatus status,
                                            Instant afterFirstReportedAt,
                                            UUID afterId,
                                            int limit) {
        var spec = jdbc.sql(ReviewRepositoryQueries.moderationQueueSql(status, afterFirstReportedAt != null));
        if (afterFirstReportedAt != null) {
            spec = spec.param(OffsetDateTime.ofInstant(afterFirstReportedAt, ZoneOffset.UTC)).param(afterId);
        }
        return spec.param(limit).query(MAPPER).list();
    }


    /**
     * Applies the single permitted edit.
     *
     * <p>{@code edit_count = 0} is part of the predicate, so a duplicated request cannot
     * spend a second edit even if the first response was lost (REVIEW-BR-006).
     */
    public Optional<Review> applyEdit(UUID reviewId, int rating, String content, boolean hasMedia) {
        return jdbc.sql("""
                UPDATE reviews
                   SET rating = ?, content = ?, has_media = ?, edit_count = 1
                 WHERE id = ? AND status = 'APPROVED' AND edit_count = 0
                RETURNING
                """ + COLUMNS)
                .param(rating)
                .param(content)
                .param(hasMedia)
                .param(reviewId)
                .query(MAPPER).optional();
    }

    /**
     * Records a new active report and, when the count leaves zero, opens a moderation
     * cycle by stamping {@code first_reported_at}.
     */
    public Review recordReport(UUID reviewId, Instant now) {
        return jdbc.sql("""
                UPDATE reviews
                   SET active_report_count = active_report_count + 1,
                       first_reported_at = CASE WHEN active_report_count = 0 THEN ?
                                                ELSE first_reported_at END
                 WHERE id = ?
                RETURNING
                """ + COLUMNS)
                .param(OffsetDateTime.ofInstant(now, ZoneOffset.UTC))
                .param(reviewId)
                .query(MAPPER).single();
    }

    /** The threshold hide. {@code hidden_at} records the most recent time it fired. */
    public Review hideByReport(UUID reviewId, Instant now) {
        return jdbc.sql("""
                UPDATE reviews
                   SET status = 'HIDDEN_BY_REPORT', hidden_at = ?
                 WHERE id = ? AND status = 'APPROVED'
                RETURNING
                """ + COLUMNS)
                .param(OffsetDateTime.ofInstant(now, ZoneOffset.UTC))
                .param(reviewId)
                .query(MAPPER).single();
    }

    /**
     * Sets the recomputed active report count, and restores a hidden review when the
     * dismissal emptied it.
     *
     * <p>{@code first_reported_at} and {@code hidden_at} are deliberately left in place:
     * a repeatedly brigaded review keeps its history and every index over those columns
     * is partial, so the retained values are inert (WF-REVIEW-007).
     */
    public Review applyDismissal(UUID reviewId, int activeReportCount, boolean restoreApproved) {
        String statusClause = restoreApproved ? ", status = 'APPROVED'" : "";
        return jdbc.sql("UPDATE reviews SET active_report_count = ?" + statusClause
                        + " WHERE id = ? RETURNING " + COLUMNS)
                .param(activeReportCount)
                .param(reviewId)
                .query(MAPPER).single();
    }

    /** The legal takedown. {@code REMOVED} is terminal and no dismissal reverses it. */
    public Review markRemoved(UUID reviewId) {
        return jdbc.sql("UPDATE reviews SET status = 'REMOVED' WHERE id = ? RETURNING " + COLUMNS)
                .param(reviewId).query(MAPPER).single();
    }

    /**
     * Replaces the pseudonym of every review of one subject with the anonymous display
     * name.
     *
     * <p>It touches neither rating nor aggregate and emits no Review event: every
     * PII-owning service receives {@code USER_DELETED} straight from Auth
     * (WF-REVIEW-010). The join goes through {@code ix_eligibility_owner_erasure}, which
     * {@code ix_eligibility_owner_pending} cannot serve because that index is partial on
     * {@code PENDING} while a review only exists once its entitlement is {@code CLAIMED}.
     */
    public int anonymizeAuthorOfSubject(UUID subjectUserId, String anonymizedName, Instant now) {
        return jdbc.sql(ReviewRepositoryQueries.ANONYMIZE_AUTHOR_SQL)
                .param(anonymizedName)
                .param(OffsetDateTime.ofInstant(now, ZoneOffset.UTC))
                .param(subjectUserId)
                .update();
    }

    /** One recomputed bucket of the daily aggregate reconciliation. */
    public record ApprovedBuckets(long star1, long star2, long star3, long star4, long star5,
                                  long withMedia, long total) {
    }

    /**
     * Recounts a product from {@code reviews} for reconciliation.
     *
     * <p>Duplicate deliveries no longer cause drift, so a difference here means real
     * corruption, a missing event or a bug, not routine at-least-once noise (ADR-005).
     */
    public ApprovedBuckets recountApproved(String productId) {
        return jdbc.sql("""
                SELECT count(*) FILTER (WHERE rating = 1) AS star1,
                       count(*) FILTER (WHERE rating = 2) AS star2,
                       count(*) FILTER (WHERE rating = 3) AS star3,
                       count(*) FILTER (WHERE rating = 4) AS star4,
                       count(*) FILTER (WHERE rating = 5) AS star5,
                       count(*) FILTER (WHERE has_media) AS with_media,
                       count(*) AS total
                  FROM reviews
                 WHERE product_id = ? AND status = 'APPROVED'
                """)
                .param(productId)
                .query((rs, row) -> new ApprovedBuckets(
                        rs.getLong("star1"), rs.getLong("star2"), rs.getLong("star3"),
                        rs.getLong("star4"), rs.getLong("star5"),
                        rs.getLong("with_media"), rs.getLong("total")))
                .single();
    }

    /** Every product that either has an aggregate row or an approved review. */
    public List<String> productIdsForReconciliation() {
        return jdbc.sql("""
                SELECT product_id FROM product_rating_aggregates
                UNION
                SELECT DISTINCT product_id FROM reviews WHERE status = 'APPROVED'
                """).query(String.class).list();
    }
}
