package com.ecommerce.review.repository;

import com.ecommerce.review.domain.RatingAggregate;
import com.ecommerce.review.domain.RatingDelta;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Optional;

/**
 * {@code product_rating_aggregates}: the projection Search and the PDP read.
 *
 * <p>Both write paths derive {@code total_reviews} and {@code average_rating} from the
 * star buckets inside the same statement, which is what keeps
 * {@code ck_aggregate_total_matches_buckets} satisfiable and makes REVIEW-INV-005 a
 * property of the SQL rather than of the caller.
 */
@Repository
public class RatingAggregateRepository {

    private static final String COLUMNS = """
            product_id, total_1_star, total_2_star, total_3_star, total_4_star, total_5_star,
            total_with_media, total_reviews, average_rating, review_revision, updated_at
            """;

    private static final RowMapper<RatingAggregate> MAPPER = (rs, row) -> new RatingAggregate(
            rs.getString("product_id"),
            rs.getLong("total_1_star"),
            rs.getLong("total_2_star"),
            rs.getLong("total_3_star"),
            rs.getLong("total_4_star"),
            rs.getLong("total_5_star"),
            rs.getLong("total_with_media"),
            rs.getLong("total_reviews"),
            rs.getBigDecimal("average_rating"),
            rs.getLong("review_revision"),
            Rows.instant(rs, "updated_at"));

    private final JdbcClient jdbc;

    public RatingAggregateRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<RatingAggregate> find(String productId) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM product_rating_aggregates WHERE product_id = ?")
                .param(productId).query(MAPPER).optional();
    }

    /**
     * Applies one bucket delta and returns the aggregate that resulted.
     *
     * <p>The whole mutation is one statement: buckets move, {@code total_reviews} and
     * {@code average_rating} are re-derived from them, and {@code review_revision} rises
     * by one. The caller writes the metrics outbox row from the returned snapshot in the
     * same transaction, which is REVIEW-INV-006.
     *
     * <p>A negative delta on a product with no aggregate row inserts a row that violates
     * {@code ck_aggregate_buckets_non_negative}. That is intentional: it means the
     * projection and the event stream really disagree, and the message must retry or go
     * to the dead-letter topic rather than commit a wrong rating (WF-REVIEW-008).
     */
    public RatingAggregate applyDelta(String productId, RatingDelta delta) {
        // The update has to come first, and it cannot be folded into the insert.
        // PostgreSQL evaluates CHECK constraints on the row an INSERT proposes before it
        // resolves ON CONFLICT, so seeding the VALUES with a negative delta would trip
        // ck_aggregate_buckets_non_negative even when the DO UPDATE branch was the one
        // about to run.
        Optional<RatingAggregate> updated = jdbc.sql(APPLY_DELTA_UPDATE)
                .param("productId", productId)
                .param("d1", delta.star1())
                .param("d2", delta.star2())
                .param("d3", delta.star3())
                .param("d4", delta.star4())
                .param("d5", delta.star5())
                .param("dMedia", delta.withMedia())
                .query(MAPPER).optional();
        if (updated.isPresent()) {
            return updated.get();
        }

        long total = delta.totalReviews();
        BigDecimal average = total <= 0
                ? BigDecimal.ZERO
                : BigDecimal.valueOf(delta.weightedRating())
                        .divide(BigDecimal.valueOf(total), 2, RoundingMode.HALF_UP);

        // No row yet, so the delta is the whole aggregate. A negative delta here means
        // the projection and the event stream really disagree, and the CHECK is exactly
        // what turns that into a retry or a dead letter instead of a wrong rating.
        return jdbc.sql("""
                INSERT INTO product_rating_aggregates AS a
                    (product_id, total_1_star, total_2_star, total_3_star, total_4_star,
                     total_5_star, total_with_media, total_reviews, average_rating, review_revision)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 1)
                ON CONFLICT (product_id) DO UPDATE SET
                    total_1_star = a.total_1_star + EXCLUDED.total_1_star,
                    total_2_star = a.total_2_star + EXCLUDED.total_2_star,
                    total_3_star = a.total_3_star + EXCLUDED.total_3_star,
                    total_4_star = a.total_4_star + EXCLUDED.total_4_star,
                    total_5_star = a.total_5_star + EXCLUDED.total_5_star,
                    total_with_media = a.total_with_media + EXCLUDED.total_with_media,
                    total_reviews = a.total_reviews + EXCLUDED.total_reviews,
                    review_revision = a.review_revision + 1,
                    updated_at = now()
                RETURNING
                """ + COLUMNS)
                .param(productId)
                .param(delta.star1())
                .param(delta.star2())
                .param(delta.star3())
                .param(delta.star4())
                .param(delta.star5())
                .param(delta.withMedia())
                .param(total)
                .param(average)
                .query(MAPPER).single();
    }

    /**
     * The delta update, with {@code total_reviews} and {@code average_rating} re-derived
     * from the star buckets in the same statement (REVIEW-INV-005).
     *
     * <p>Every right-hand side reads the pre-update column values, so the derived columns
     * see exactly the buckets this statement is writing.
     */
    private static final String APPLY_DELTA_UPDATE = """
            UPDATE product_rating_aggregates AS a SET
                total_1_star = a.total_1_star + :d1,
                total_2_star = a.total_2_star + :d2,
                total_3_star = a.total_3_star + :d3,
                total_4_star = a.total_4_star + :d4,
                total_5_star = a.total_5_star + :d5,
                total_with_media = a.total_with_media + :dMedia,
                total_reviews = (a.total_1_star + :d1) + (a.total_2_star + :d2)
                              + (a.total_3_star + :d3) + (a.total_4_star + :d4)
                              + (a.total_5_star + :d5),
                average_rating = CASE
                    WHEN ((a.total_1_star + :d1) + (a.total_2_star + :d2)
                        + (a.total_3_star + :d3) + (a.total_4_star + :d4)
                        + (a.total_5_star + :d5)) <= 0 THEN 0
                    ELSE round((
                             (a.total_1_star + :d1) * 1
                           + (a.total_2_star + :d2) * 2
                           + (a.total_3_star + :d3) * 3
                           + (a.total_4_star + :d4) * 4
                           + (a.total_5_star + :d5) * 5
                         )::numeric / (
                             (a.total_1_star + :d1) + (a.total_2_star + :d2)
                           + (a.total_3_star + :d3) + (a.total_4_star + :d4)
                           + (a.total_5_star + :d5)
                         ), 2)
                END,
                review_revision = a.review_revision + 1,
                updated_at = now()
            WHERE a.product_id = :productId
            RETURNING product_id, total_1_star, total_2_star, total_3_star, total_4_star,
                      total_5_star, total_with_media, total_reviews, average_rating,
                      review_revision, updated_at
            """;

    /**
     * The reconciliation repair: absolute bucket values plus a revision bump.
     *
     * <p>A repair must move the revision forward, otherwise Search would discard the
     * corrected snapshot as stale (WF-REVIEW-011).
     */
    public RatingAggregate reconcile(String productId,
                                     long star1, long star2, long star3, long star4, long star5,
                                     long withMedia) {
        long total = star1 + star2 + star3 + star4 + star5;
        BigDecimal average = total <= 0
                ? BigDecimal.ZERO
                : BigDecimal.valueOf(star1 + 2 * star2 + 3 * star3 + 4 * star4 + 5 * star5)
                        .divide(BigDecimal.valueOf(total), 2, RoundingMode.HALF_UP);

        return jdbc.sql("""
                INSERT INTO product_rating_aggregates AS a
                    (product_id, total_1_star, total_2_star, total_3_star, total_4_star,
                     total_5_star, total_with_media, total_reviews, average_rating, review_revision)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 1)
                ON CONFLICT (product_id) DO UPDATE SET
                    total_1_star = EXCLUDED.total_1_star,
                    total_2_star = EXCLUDED.total_2_star,
                    total_3_star = EXCLUDED.total_3_star,
                    total_4_star = EXCLUDED.total_4_star,
                    total_5_star = EXCLUDED.total_5_star,
                    total_with_media = EXCLUDED.total_with_media,
                    total_reviews = EXCLUDED.total_reviews,
                    average_rating = EXCLUDED.average_rating,
                    review_revision = a.review_revision + 1,
                    updated_at = now()
                RETURNING
                """ + COLUMNS)
                .param(productId)
                .param(star1).param(star2).param(star3).param(star4).param(star5)
                .param(withMedia).param(total).param(average)
                .query(MAPPER).single();
    }

    /**
     * One keyset page of an export session, ordered by {@code product_id}.
     *
     * <p>The scan is short and holds no transaction across HTTP pages: the session's
     * frozen Kafka offsets plus the {@code review_revision} guard are what make a
     * mutation seen mid-scan safe (ADR-006).
     */
    public List<RatingAggregate> exportPage(String afterProductId, int limit) {
        String sql = "SELECT " + COLUMNS + " FROM product_rating_aggregates"
                + (afterProductId == null ? "" : " WHERE product_id > ?")
                + " ORDER BY product_id LIMIT ?";
        var spec = jdbc.sql(sql);
        if (afterProductId != null) {
            spec = spec.param(afterProductId);
        }
        return spec.param(limit).query(MAPPER).list();
    }
}
