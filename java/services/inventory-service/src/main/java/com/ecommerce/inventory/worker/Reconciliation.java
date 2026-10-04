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
