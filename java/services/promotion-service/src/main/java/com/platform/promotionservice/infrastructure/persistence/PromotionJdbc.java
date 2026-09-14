package com.platform.promotionservice.infrastructure.persistence;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Guarded update, advisory lock, sequence và DB clock bằng SQL tường minh. Các
 * transition ở đây là conditional UPDATE để concurrency được PostgreSQL chốt
 * (docs/promotion/data.md §5), không dựa vào Redis counter.
 */
@Component
@RequiredArgsConstructor
public class PromotionJdbc {

    private static final String PRODUCT_LOCK_NAMESPACE = "promotion:product:";

    private final JdbcTemplate jdbc;

    /** Clock của PostgreSQL trong transaction hiện tại (phase/eligibility dùng DB time). */
    public Instant dbNow() {
        Timestamp now = jdbc.queryForObject("SELECT now()", Timestamp.class);
        return now == null ? Instant.now() : now.toInstant();
    }

    /**
     * Advisory transaction lock theo product; dùng chung cho admin markdown mutation,
     * scheduler snapshot và Product tombstone consumer (PROMO-INV-018).
     */
    public void lockProduct(UUID productId) {
        // pg_advisory_xact_lock trả void; chỉ cần thực thi, không đọc kết quả.
        jdbc.query("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))",
                ps -> ps.setString(1, PRODUCT_LOCK_NAMESPACE + productId), rs -> { });
    }

    public long nextSourceRevision() {
        Long next = jdbc.queryForObject("SELECT nextval('markdown_source_revision_seq')", Long.class);
        return next == null ? 0 : next;
    }

    // ---------------------------------------------------------------- claims

    public int insertAvailableClaims(long promotionId, int count, Instant createdAt) {
        return jdbc.update("""
                INSERT INTO coupon_claims (promotion_id, status, created_at)
                SELECT ?, 'AVAILABLE', ? FROM generate_series(1, ?)
                """, promotionId, Timestamp.from(createdAt), count);
    }

    /** AVAILABLE + owner null → CLAIMED; trả 0 nếu suất đã bị lấy (PROMO-INV-004). */
    public int claimGuarded(long claimId, long promotionId, UUID ownerId, Instant claimedAt, long campaignRevision) {
        return jdbc.update("""
                UPDATE coupon_claims
                SET status = 'CLAIMED', owner_id = ?, claimed_at = ?, claimed_campaign_revision = ?
                WHERE id = ? AND promotion_id = ? AND status = 'AVAILABLE' AND owner_id IS NULL
                """, ownerId, Timestamp.from(claimedAt), campaignRevision, claimId, promotionId);
    }

    // ---------------------------------------------------------------- budget

    /** claimed_quantity + 1 nếu chưa chạm total_claim_quantity hữu hạn. */
    public int incrementClaimed(long promotionId, Instant now) {
        return jdbc.update("""
                UPDATE promotion_budgets SET claimed_quantity = claimed_quantity + 1, updated_at = ?
                WHERE promotion_id = ? AND (total_claim_quantity IS NULL OR claimed_quantity < total_claim_quantity)
                """, Timestamp.from(now), promotionId);
    }

    /** Durable used counter khi CONFIRM; guard total redemption/budget hữu hạn. */
    public int incrementUsed(long promotionId, long amountVnd, Instant now) {
        return jdbc.update("""
                UPDATE promotion_budgets
                SET used_quantity = used_quantity + 1, used_budget_vnd = used_budget_vnd + ?, updated_at = ?
                WHERE promotion_id = ?
                  AND (total_redemption_quantity IS NULL OR used_quantity < total_redemption_quantity)
                  AND (total_budget_vnd IS NULL OR used_budget_vnd + ? <= total_budget_vnd)
                """, amountVnd, Timestamp.from(now), promotionId, amountVnd);
    }

    /** Giảm durable used counter khi CANCEL_CONFIRMED; underflow → 0 row, không clamp. */
    public int decrementUsed(long promotionId, long amountVnd, Instant now) {
        return jdbc.update("""
                UPDATE promotion_budgets
                SET used_quantity = used_quantity - 1, used_budget_vnd = used_budget_vnd - ?, updated_at = ?
                WHERE promotion_id = ? AND used_quantity >= 1 AND used_budget_vnd >= ?
                """, amountVnd, Timestamp.from(now), promotionId, amountVnd);
    }

    /** Expected used từ wallet USED (reconciliation §8): [count, sum_vnd]. */
    public long[] usedTotals(long promotionId) {
        return jdbc.queryForObject("""
                SELECT count(*), coalesce(sum(discount_amount_vnd), 0)
                FROM user_coupon_wallets WHERE promotion_id = ? AND status = 'USED'
                """, (rs, rowNum) -> new long[] {rs.getLong(1), rs.getLong(2)}, promotionId);
    }

    // ---------------------------------------------------------------- wallet

    /** Lazy expiry: SAVED đã qua expired_at → EXPIRED (WF-PROMO-004). */
    public int lazyExpireWallets(UUID userId, Instant now) {
        return jdbc.update("""
                UPDATE user_coupon_wallets SET status = 'EXPIRED', updated_at = ?
                WHERE user_id = ? AND status = 'SAVED' AND expired_at <= ?
                """, Timestamp.from(now), userId, Timestamp.from(now));
    }

    // -------------------------------------------------------------- receipts

    /** Chỉ set sau khi Redis đã hội tụ; duplicate là no-op (ADR-016). */
    public int markReceiptFinalized(UUID receiptId, Instant now) {
        return jdbc.update("""
                UPDATE promotion_command_receipts SET redis_finalized_at = ?
                WHERE id = ? AND redis_finalized_at IS NULL
                """, Timestamp.from(now), receiptId);
    }

    public int trimReceipts(Instant now) {
        return jdbc.update("""
                DELETE FROM promotion_command_receipts
                WHERE retain_until < ? AND redis_finalized_at IS NOT NULL
                """, Timestamp.from(now));
    }

    // ---------------------------------------------------------------- outbox

    /** Causal dedup: cùng dedup_key → no-op, không thể phát hai snapshot cho một cause. */
    public int insertOutboxIfAbsent(UUID eventId, String eventType, String aggregateType, String aggregateId,
                                    String partitionKey, long sourceRevision, String dedupKey,
                                    String payload, Instant createdAt) {
        return jdbc.update("""
                INSERT INTO promotion_outbox_events
                    (event_id, event_type, aggregate_type, aggregate_id, partition_key, source_revision,
                     dedup_key, payload, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?)
                ON CONFLICT (dedup_key) DO NOTHING
                """, eventId, eventType, aggregateType, aggregateId, partitionKey, sourceRevision,
                dedupKey, payload, Timestamp.from(createdAt));
    }

    public int trimOutbox(Instant before) {
        return jdbc.update("DELETE FROM promotion_outbox_events WHERE created_at < ?", Timestamp.from(before));
    }

    // -------------------------------------------------------- reconciliation

    /** Promotion có wallet USED/receipt thay đổi gần đây (recent window của reconciliation). */
    public List<Long> promotionsWithRecentActivity(Instant since) {
        return jdbc.queryForList("""
                SELECT DISTINCT promotion_id FROM (
                    SELECT promotion_id FROM user_coupon_wallets WHERE updated_at >= ?
                    UNION ALL
                    SELECT promotion_id FROM promotion_command_receipts WHERE completed_at >= ?
                ) recent
                """, Long.class, Timestamp.from(since), Timestamp.from(since));
    }
}
