package com.ecommerce.review.repository;

import com.ecommerce.review.domain.Takedown;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

/**
 * {@code review_takedowns}: the immutable legal audit record.
 *
 * <p>{@code review_id UNIQUE} makes a second takedown of the same review impossible,
 * which is the database half of "{@code REMOVED} is terminal" (ADR-010).
 */
@Repository
public class TakedownRepository {

    private static final String COLUMNS =
            "id, review_id, legal_case_id, taken_down_by_admin_id, reason, created_at";

    private static final RowMapper<Takedown> MAPPER = (rs, row) -> new Takedown(
            Rows.uuid(rs, "id"),
            Rows.uuid(rs, "review_id"),
            rs.getString("legal_case_id"),
            Rows.uuid(rs, "taken_down_by_admin_id"),
            rs.getString("reason"),
            Rows.instant(rs, "created_at"));

    private final JdbcClient jdbc;

    public TakedownRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * @param takenDownByAdminId the acting administrator, read from trusted Gateway
     *                           identity; the request body carries no actor field
     */
    public Takedown insert(UUID reviewId, String legalCaseId, UUID takenDownByAdminId, String reason) {
        return jdbc.sql("""
                INSERT INTO review_takedowns
                    (review_id, legal_case_id, taken_down_by_admin_id, reason)
                VALUES (?, ?, ?, ?)
                RETURNING
                """ + COLUMNS)
                .param(reviewId).param(legalCaseId).param(takenDownByAdminId).param(reason)
                .query(MAPPER).single();
    }

    public Optional<Takedown> findByReviewId(UUID reviewId) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM review_takedowns WHERE review_id = ?")
                .param(reviewId).query(MAPPER).optional();
    }
}
