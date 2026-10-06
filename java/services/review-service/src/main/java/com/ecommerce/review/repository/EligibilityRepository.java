package com.ecommerce.review.repository;

import com.ecommerce.review.domain.Eligibility;
import com.ecommerce.review.domain.EligibilityStatus;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** {@code review_eligibilities}: the entitlement to write one review for one bought SKU. */
@Repository
public class EligibilityRepository {

    private static final String COLUMNS = """
            id, order_id, product_id, sku_id, variant_name_snapshot,
            product_name_snapshot, product_image_url_snapshot, owner_user_id,
            owner_erased_at, status, completed_at, expires_at, claimed_at, created_at, updated_at
            """;

    private static final RowMapper<Eligibility> MAPPER = (rs, row) -> new Eligibility(
            Rows.uuid(rs, "id"),
            rs.getString("order_id"),
            rs.getString("product_id"),
            rs.getString("sku_id"),
            rs.getString("variant_name_snapshot"),
            rs.getString("product_name_snapshot"),
            rs.getString("product_image_url_snapshot"),
            Rows.uuid(rs, "owner_user_id"),
            Rows.instant(rs, "owner_erased_at"),
            Rows.enumValue(rs, "status", EligibilityStatus.class),
            Rows.instant(rs, "completed_at"),
            Rows.instant(rs, "expires_at"),
            Rows.instant(rs, "claimed_at"),
            Rows.instant(rs, "created_at"),
            Rows.instant(rs, "updated_at"));

    private final JdbcClient jdbc;

    public EligibilityRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Inserts one entitlement per business key, never overwriting an existing row.
     *
     * <p>{@code ON CONFLICT DO NOTHING} on {@code (order_id, product_id, sku_id)} is what
     * makes a replayed {@code ORDER_COMPLETED} a no-op rather than a second entitlement
     * or a rewritten snapshot (REVIEW-BR-001).
     *
     * @return true when this call created the row
     */
    public boolean insertIfAbsent(String orderId,
                                  String productId,
                                  String skuId,
                                  String variantNameSnapshot,
                                  String productNameSnapshot,
                                  String productImageUrlSnapshot,
                                  UUID ownerUserId,
                                  EligibilityStatus status,
                                  Instant completedAt,
                                  Instant expiresAt) {
        return jdbc.sql("""
                INSERT INTO review_eligibilities
                    (order_id, product_id, sku_id, variant_name_snapshot, product_name_snapshot,
                     product_image_url_snapshot, owner_user_id, status, completed_at, expires_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?::eligibility_status, ?, ?)
                ON CONFLICT (order_id, product_id, sku_id) DO NOTHING
                """)
                .param(orderId)
                .param(productId)
                .param(skuId)
                .param(variantNameSnapshot)
                .param(productNameSnapshot)
                .param(productImageUrlSnapshot)
                .param(ownerUserId)
                .param(status.name())
                .param(OffsetDateTime.ofInstant(completedAt, ZoneOffset.UTC))
                .param(OffsetDateTime.ofInstant(expiresAt, ZoneOffset.UTC))
                .update() == 1;
    }

    public Optional<Eligibility> findById(UUID id) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM review_eligibilities WHERE id = ?")
                .param(id).query(MAPPER).optional();
    }

    /**
     * The entitlement behind a review.
     *
     * <p>Reviews deliberately carry no owner column, so this join is how edit, reply and
     * takedown resolve ownership and the notification recipient
     * ({@code docs/review/data.md 3}).
     */
    public Optional<Eligibility> findByReviewId(UUID reviewId) {
        return jdbc.sql("""
                SELECT e.id, e.order_id, e.product_id, e.sku_id, e.variant_name_snapshot,
                       e.product_name_snapshot, e.product_image_url_snapshot,
                       e.owner_user_id, e.owner_erased_at, e.status, e.completed_at,
                       e.expires_at, e.claimed_at, e.created_at, e.updated_at
                  FROM review_eligibilities e
                  JOIN reviews r ON r.eligibility_id = e.id
                 WHERE r.id = ?
                """).param(reviewId).query(MAPPER).optional();
    }

    /**
     * The entitlement behind a review, locked with the review row.
     *
     * <p>Admin reply and takedown read the owner before they decide whether to write a
     * notification request, and the row must not change under them while the guard is
     * re-checked (WF-REVIEW-006).
     */
    public Optional<Eligibility> findByReviewIdForUpdate(UUID reviewId) {
        return jdbc.sql("""
                SELECT e.id, e.order_id, e.product_id, e.sku_id, e.variant_name_snapshot,
                       e.product_name_snapshot, e.product_image_url_snapshot,
                       e.owner_user_id, e.owner_erased_at, e.status, e.completed_at,
                       e.expires_at, e.claimed_at, e.created_at, e.updated_at
                  FROM review_eligibilities e
                  JOIN reviews r ON r.eligibility_id = e.id
                 WHERE r.id = ?
                 FOR UPDATE OF e
                """).param(reviewId).query(MAPPER).optional();
    }

    /**
     * Claims a {@code PENDING} entitlement inside the create transaction.
     *
     * <p>The conditional update is simultaneously the ownership check, the expiry check
     * and the row lock; a zero-row result is then classified by
     * {@link #findById(UUID)} into 404, 403, 410 or 409 (WF-REVIEW-003).
     */
    public Optional<Eligibility> claim(UUID eligibilityId, UUID callerId, Instant now) {
        return jdbc.sql("""
                UPDATE review_eligibilities
                   SET status = 'CLAIMED', claimed_at = ?
                 WHERE id = ?
                   AND owner_user_id = ?
                   AND status = 'PENDING'
                   AND expires_at > ?
                RETURNING
                """ + COLUMNS)
                .param(OffsetDateTime.ofInstant(now, ZoneOffset.UTC))
                .param(eligibilityId)
                .param(callerId)
                .param(OffsetDateTime.ofInstant(now, ZoneOffset.UTC))
                .query(MAPPER).optional();
    }

    /**
     * The buyer's pending entitlements, soonest expiry first.
     *
     * <p>Keyset on {@code (expires_at, id)} rather than {@code OFFSET}, served by
     * {@code ix_eligibility_owner_pending} (ADR-002).
     */
    public List<Eligibility> listPending(UUID ownerUserId, Instant afterExpiresAt, UUID afterId, int limit) {
        String sql = "SELECT " + COLUMNS
                + " FROM review_eligibilities"
                + " WHERE owner_user_id = ? AND status = 'PENDING'"
                + (afterExpiresAt == null ? "" : " AND (expires_at, id) > (?, ?)")
                + " ORDER BY expires_at, id LIMIT ?";
        var spec = jdbc.sql(sql).param(ownerUserId);
        if (afterExpiresAt != null) {
            spec = spec.param(OffsetDateTime.ofInstant(afterExpiresAt, ZoneOffset.UTC)).param(afterId);
        }
        return spec.param(limit).query(MAPPER).list();
    }

    /**
     * WF-REVIEW-011: closes the window of entitlements nobody used.
     *
     * @return how many entitlements this sweep expired
     */
    public int expirePending(Instant now, int batchSize) {
        return jdbc.sql("""
                UPDATE review_eligibilities
                   SET status = 'EXPIRED'
                 WHERE id IN (
                     SELECT id FROM review_eligibilities
                      WHERE status = 'PENDING' AND expires_at <= ?
                      ORDER BY expires_at
                      LIMIT ?
                      FOR UPDATE SKIP LOCKED)
                """)
                .param(OffsetDateTime.ofInstant(now, ZoneOffset.UTC))
                .param(batchSize)
                .update();
    }

    /**
     * The privacy hard purge. Both columns of the pair are written in one statement:
     * {@code ck_eligibility_owner_erasure_consistent} rejects clearing the identifier
     * alone, and that failure is deterministic, so a retry would repeat it forever while
     * the legal deadline passes (WF-REVIEW-010).
     */
    public int purgeOwner(UUID subjectUserId, Instant now) {
        return jdbc.sql("""
                UPDATE review_eligibilities
                   SET owner_user_id = NULL, owner_erased_at = ?
                 WHERE owner_user_id = ?
                """)
                .param(OffsetDateTime.ofInstant(now, ZoneOffset.UTC))
                .param(subjectUserId)
                .update();
    }
}
