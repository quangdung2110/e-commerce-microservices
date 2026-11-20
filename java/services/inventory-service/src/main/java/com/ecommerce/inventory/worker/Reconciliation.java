package com.ecommerce.inventory.worker;

import com.ecommerce.inventory.command.WriteGuard;
import com.ecommerce.inventory.config.InventoryProperties;
import com.ecommerce.inventory.store.OutboxStore;
import com.ecommerce.inventory.store.ReservationStore;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Detection-only reconciliation and backlog monitoring (operations.md §2–§4). Nothing here
 * corrects state: mismatches become metrics and error logs for the incident process.
 */
@Component
public class Reconciliation {

    private static final Logger log = LoggerFactory.getLogger(Reconciliation.class);

    private final JdbcTemplate jdbc;
    private final OutboxStore outbox;
    private final ReservationStore reservations;
    private final WriteGuard writeGuard;
    private final TransactionTemplate transactions;
    private final Clock clock;
    private final InventoryProperties properties;

    private final AtomicLong unsequenced = new AtomicLong();
    private final AtomicLong oldestUnsequencedSeconds = new AtomicLong();
    private final AtomicLong dirtyBacklog = new AtomicLong();
    private final AtomicLong oldestPendingSeconds = new AtomicLong();
    private final AtomicLong orphanReservations = new AtomicLong();
    private final AtomicLong bucketViolations = new AtomicLong();
    private final AtomicLong ledgerMismatches = new AtomicLong();
    private final AtomicLong availabilityMismatches = new AtomicLong();
    private final AtomicLong readinessSuppressed = new AtomicLong();
    private String availabilityAuditCursor = "";

    /** Result of one availability audit pass. */
    public record AvailabilityAudit(int checked, int skippedPending, List<String> mismatchedSkus,
                                    long readinessSuppressed) {
    }

    public Reconciliation(JdbcTemplate jdbc, OutboxStore outbox, ReservationStore reservations, WriteGuard writeGuard,
                          TransactionTemplate transactions, Clock clock, InventoryProperties properties,
                          MeterRegistry meters) {
        this.jdbc = jdbc;
        this.outbox = outbox;
        this.reservations = reservations;
        this.writeGuard = writeGuard;
        this.transactions = transactions;
        this.clock = clock;
        this.properties = properties;
        meters.gauge("inventory_outbox_unsequenced", unsequenced);
        meters.gauge("inventory_outbox_oldest_unsequenced_seconds", oldestUnsequencedSeconds);
        meters.gauge("inventory_availability_dirty_backlog", dirtyBacklog);
        meters.gauge("inventory_availability_oldest_pending_seconds", oldestPendingSeconds);
        meters.gauge("inventory_reservations_orphaned", orphanReservations);
        meters.gauge("inventory_integrity_bucket_violations", bucketViolations);
        meters.gauge("inventory_integrity_ledger_mismatches", ledgerMismatches);
        meters.gauge("inventory_integrity_availability_mismatches", availabilityMismatches);
        meters.gauge("inventory_availability_readiness_suppressed", readinessSuppressed);
    }

    /** Feeds outbox backpressure and the outbox/availability lag alerts. */
    public void refreshBacklog() {
        Instant now = Instant.now(clock);
        long count = outbox.unsequencedCount();
        unsequenced.set(count);
        writeGuard.updateUnsequencedOutbox(count);
        oldestUnsequencedSeconds.set(age(outbox.oldestUnsequencedAt(), now));
        Long dirty = jdbc.queryForObject("SELECT count(*) FROM sku_availability_dirty", Long.class);
        dirtyBacklog.set(dirty == null ? 0 : dirty);
        Timestamp oldestDirty = jdbc.queryForObject("SELECT min(first_marked_at) FROM sku_availability_dirty",
                Timestamp.class);
        Timestamp oldestFanout = jdbc.queryForObject(
                "SELECT min(created_at) FROM availability_fanout_job WHERE completed_at IS NULL", Timestamp.class);
        Instant oldest = earliest(oldestDirty, oldestFanout);
        oldestPendingSeconds.set(age(oldest, now));
    }

    /** {@code RESERVED} roots older than 60 minutes alert Order; Inventory never releases them. */
    public int scanOrphans() {
        Duration after = properties.reservation().orphanAlertAfter();
        List<ReservationStore.Root> orphans = reservations.reservedOlderThan(Instant.now(clock).minus(after), 500);
        orphanReservations.set(orphans.size());
        orphans.forEach(root -> log.warn("orphan reservation alert: order {} RESERVED since {} (correlation {})",
                root.orderId(), root.acceptedAt(), root.correlationId()));
        return orphans.size();
    }

    /** Hourly full bucket scan; a violation is SEV-1 and is never masked by an adjustment. */
    public long scanBuckets() {
        Long violations = jdbc.queryForObject("""
                SELECT count(*) FROM stock_position
                WHERE on_hand < 0 OR available_to_sell < 0 OR reserved < 0 OR committed < 0 OR quarantine < 0
                   OR available_to_sell + reserved + committed + quarantine <> on_hand
                """, Long.class);
        long n = violations == null ? 0 : violations;
        bucketViolations.set(n);
        if (n > 0) {
            log.error("SEV-1 integrity: {} stock positions violate the bucket invariant", n);
        }
        return n;
    }

    /** Daily ledger replay: every position must equal the sum of its immutable movements. */
    public long replayLedger() {
        Long mismatches = transactions.execute(status -> {
            jdbc.execute("SET TRANSACTION ISOLATION LEVEL REPEATABLE READ");
            return jdbc.queryForObject("""
                    SELECT count(*) FROM stock_position p
                    LEFT JOIN (SELECT warehouse_id, sku_id, sum(delta_on_hand) AS on_hand, sum(delta_available) AS ats,
                                      sum(delta_reserved) AS reserved, sum(delta_committed) AS committed,
                                      sum(delta_quarantine) AS quarantine
                               FROM stock_movement GROUP BY warehouse_id, sku_id) m
                      ON m.warehouse_id = p.warehouse_id AND m.sku_id = p.sku_id
                    WHERE p.on_hand <> coalesce(m.on_hand, 0) OR p.available_to_sell <> coalesce(m.ats, 0)
                       OR p.reserved <> coalesce(m.reserved, 0) OR p.committed <> coalesce(m.committed, 0)
                       OR p.quarantine <> coalesce(m.quarantine, 0)
                    """, Long.class);
        });
        long n = mismatches == null ? 0 : mismatches;
        ledgerMismatches.set(n);
        if (n > 0) {
            log.error("SEV-1 integrity: {} stock positions differ from their movement ledger", n);
        }
        return n;
    }

    /**
     * Continuous availability audit (operations.md §3): recomputes the warehouse-aware rule for
     * the next batch of {@code SkuAvailabilityState} rows, in {@code sku_id} order and wrapping
     * around, and reports every row whose stored boolean differs from stock truth. A row with a
     * pending dirty mark, or covered by an unfinished warehouse fan-out, is expected to be stale
     * and is skipped. One statement reads state, stock, warehouses and pending work from a single
     * snapshot, so a mismatch is never produced by a concurrent command. Detection only.
     */
    public synchronized AvailabilityAudit auditAvailability() {
        int batch = properties.workers().availabilityAuditBatchSize();
        record Row(String skuId, boolean mismatch, boolean pending) {
        }
        List<Row> rows = jdbc.query("""
                WITH batch AS (
                    SELECT sku_id, is_in_stock FROM sku_availability_state
                    WHERE sku_id > ? ORDER BY sku_id LIMIT ?
                )
                SELECT b.sku_id,
                       b.is_in_stock <> EXISTS (
                           SELECT 1 FROM stock_position p JOIN warehouse w ON w.warehouse_id = p.warehouse_id
                           WHERE p.sku_id = b.sku_id AND p.available_to_sell > 0
                             AND w.is_active AND w.operational_state <> 'OUTAGE') AS mismatch,
                       (EXISTS (SELECT 1 FROM sku_availability_dirty d WHERE d.sku_id = b.sku_id)
                        OR EXISTS (SELECT 1 FROM availability_fanout_job j
                                   JOIN stock_position p ON p.warehouse_id = j.warehouse_id AND p.sku_id = b.sku_id
                                   WHERE j.completed_at IS NULL)) AS pending
                FROM batch b ORDER BY b.sku_id
                """, (rs, i) -> new Row(rs.getString("sku_id"), rs.getBoolean("mismatch"), rs.getBoolean("pending")),
                availabilityAuditCursor, batch);
        availabilityAuditCursor = rows.size() < batch ? "" : rows.get(rows.size() - 1).skuId();

        List<String> mismatched = rows.stream().filter(r -> r.mismatch() && !r.pending()).map(Row::skuId).toList();
        int pending = (int) rows.stream().filter(Row::pending).count();
        Long suppressed = jdbc.queryForObject("""
                SELECT count(*) FROM sku_availability_state s
                WHERE NOT EXISTS (SELECT 1 FROM search_projection_readiness r
                                  WHERE r.sku_id = s.sku_id AND r.revoked_at IS NULL)
                """, Long.class);
        readinessSuppressed.set(suppressed == null ? 0 : suppressed);
        availabilityMismatches.set(mismatched.size());
        if (!mismatched.isEmpty()) {
            log.error("availability integrity: {} SKU states differ from stock truth without pending work: {}",
                    mismatched.size(), mismatched.stream().limit(20).toList());
        }
        return new AvailabilityAudit(rows.size() - pending, pending, mismatched, readinessSuppressed.get());
    }

    /**
     * Retention: published facts older than the replay window, expired snapshot sessions and
     * finished fan-out jobs. Unsequenced outbox rows are never removed.
     */
    public void applyRetention() {
        Instant now = Instant.now(clock);
        Instant cutoff = now.minus(properties.outbox().retention());
        transactions.executeWithoutResult(status -> {
            int purged = outbox.purgePublishedBefore(cutoff);
            if (purged > 0) {
                log.info("retention removed {} published facts older than {}", purged, cutoff);
            }
            jdbc.update("DELETE FROM snapshot_session WHERE expires_at < ?", Timestamp.from(now));
            jdbc.update("DELETE FROM availability_fanout_job WHERE completed_at < ?", Timestamp.from(cutoff));
            jdbc.update("DELETE FROM readiness_publication_task WHERE completed_at < ?", Timestamp.from(cutoff));
        });
    }

    private static long age(Instant since, Instant now) {
        return since == null ? 0 : Math.max(0, Duration.between(since, now).toSeconds());
    }

    private static Instant earliest(Timestamp a, Timestamp b) {
        if (a == null) {
            return b == null ? null : b.toInstant();
        }
        if (b == null) {
            return a.toInstant();
        }
        return a.before(b) ? a.toInstant() : b.toInstant();
    }
}
