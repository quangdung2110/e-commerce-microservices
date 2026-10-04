package com.ecommerce.inventory.store;

import com.ecommerce.inventory.command.CommandContext;
import com.ecommerce.inventory.common.Ids;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Array;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.TreeSet;

/**
 * Per-SKU availability work and state (data.md §5): dirty marks, warehouse fan-out jobs,
 * {@code SkuAvailabilityState}, Search readiness and readiness publication tasks.
 */
@Repository
public class AvailabilityStore {

    private final JdbcTemplate jdbc;

    public AvailabilityStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ── Dirty marks (command hot path) ────────────────────────────────────────

    /**
     * Durably marks SKUs for recomputation inside the command transaction. Coalescing keeps
     * {@code first_marked_at}; SKUs are marked in sorted order so concurrent commands never
     * deadlock on dirty rows.
     */
    public void markDirty(Collection<String> skuIds, CommandContext context) {
        for (String skuId : new TreeSet<>(skuIds)) {
            markDirty(skuId, context.now(), context.correlationId(), context.commandId());
        }
    }

    public void markDirty(String skuId, Instant markedAt, String correlationId, String causationId) {
        jdbc.update("""
                INSERT INTO sku_availability_dirty (sku_id, first_marked_at, mark_seq, last_correlation_id,
                                                    last_causation_id)
                VALUES (?, ?, 1, ?, ?)
                ON CONFLICT (sku_id) DO UPDATE
                SET mark_seq = sku_availability_dirty.mark_seq + 1,
                    first_marked_at = LEAST(sku_availability_dirty.first_marked_at, EXCLUDED.first_marked_at),
                    last_correlation_id = EXCLUDED.last_correlation_id,
                    last_causation_id = EXCLUDED.last_causation_id
                """, skuId, Timestamp.from(markedAt), correlationId, causationId);
    }

    // ── Dirty marks (projector) ───────────────────────────────────────────────

    public record DirtyClaim(String skuId, long claimedSeq, Instant claimStartedAt, String correlationId,
                             String causationId) {
    }

    /** Claims a batch of unclaimed or expired marks with a lease, oldest first. */
    public List<DirtyClaim> claimDirty(int limit, Instant now, Instant claimUntil) {
        return jdbc.query("""
                UPDATE sku_availability_dirty d
                SET claimed_seq = d.mark_seq, claimed_until = ?, claim_started_at = ?
                FROM (SELECT sku_id FROM sku_availability_dirty
                      WHERE claimed_until IS NULL OR claimed_until < ?
                      ORDER BY first_marked_at LIMIT ? FOR UPDATE SKIP LOCKED) c
                WHERE d.sku_id = c.sku_id
                RETURNING d.sku_id, d.claimed_seq, d.claim_started_at, d.last_correlation_id, d.last_causation_id
                """, (rs, i) -> new DirtyClaim(rs.getString("sku_id"), rs.getLong("claimed_seq"),
                rs.getTimestamp("claim_started_at").toInstant(), rs.getString("last_correlation_id"),
                rs.getString("last_causation_id")),
                Timestamp.from(claimUntil), Timestamp.from(now), Timestamp.from(now), limit);
    }

    /**
     * Clears marks covered by a completed recomputation. Marks that arrived after the claim
     * remain, aged no older than the claim start, so freshness stays conservative.
     */
    public void finishDirty(DirtyClaim claim) {
        int deleted = jdbc.update("DELETE FROM sku_availability_dirty WHERE sku_id = ? AND mark_seq = ?",
                claim.skuId(), claim.claimedSeq());
        if (deleted == 0) {
            jdbc.update("""
                    UPDATE sku_availability_dirty
                    SET first_marked_at = GREATEST(first_marked_at, ?), claimed_until = NULL, claimed_seq = NULL,
                        claim_started_at = NULL
                    WHERE sku_id = ?
                    """, Timestamp.from(claim.claimStartedAt()), claim.skuId());
        }
    }

    // ── State ─────────────────────────────────────────────────────────────────

    public record State(String skuId, boolean inStock, long revision, Instant evaluatedAt) {
    }

    private static final RowMapper<State> STATE = (rs, i) -> new State(rs.getString("sku_id"),
            rs.getBoolean("is_in_stock"), rs.getLong("availability_revision"),
            rs.getTimestamp("evaluated_at").toInstant());

    /** Serializes all availability work of one SKU across projector, fan-out and readiness workers. */
    public void lockSku(String skuId) {
        jdbc.queryForObject("SELECT pg_advisory_xact_lock(hashtextextended('sku-availability:' || ?, 0))",
                Object.class, skuId);
    }

    /** The warehouse-aware rule: an active, non-OUTAGE warehouse with sellable stock. */
    public boolean computeInStock(String skuId) {
        Boolean inStock = jdbc.queryForObject("""
                SELECT EXISTS (
                    SELECT 1 FROM stock_position p JOIN warehouse w ON w.warehouse_id = p.warehouse_id
                    WHERE p.sku_id = ? AND p.available_to_sell > 0
                      AND w.is_active AND w.operational_state <> 'OUTAGE')
                """, Boolean.class, skuId);
        return Boolean.TRUE.equals(inStock);
    }

    public Optional<State> findState(String skuId) {
        return jdbc.query("SELECT sku_id, is_in_stock, availability_revision, evaluated_at "
                + "FROM sku_availability_state WHERE sku_id = ?", STATE, skuId).stream().findFirst();
    }

    public void insertState(State state) {
        jdbc.update("INSERT INTO sku_availability_state (sku_id, is_in_stock, availability_revision, evaluated_at) "
                + "VALUES (?, ?, ?, ?)", state.skuId(), state.inStock(), state.revision(),
                Timestamp.from(state.evaluatedAt()));
    }

    public void updateState(State state) {
        jdbc.update("UPDATE sku_availability_state SET is_in_stock = ?, availability_revision = ?, evaluated_at = ? "
                + "WHERE sku_id = ?", state.inStock(), state.revision(), Timestamp.from(state.evaluatedAt()),
                state.skuId());
    }

    /** One row per requested SKU, with the age-defining pending work (ADR-025). */
    public record ReadRow(String skuId, Boolean inStock, Long revision, Instant evaluatedAt,
                          Instant oldestPendingAt) {
    }

    public List<ReadRow> readAvailability(Collection<String> skuIds) {
        return jdbc.query(con -> {
            var statement = con.prepareStatement("""
                    WITH requested AS (SELECT unnest(?::varchar[]) AS sku_id)
                    SELECT r.sku_id, s.is_in_stock, s.availability_revision, s.evaluated_at,
                           LEAST(
                               (SELECT d.first_marked_at FROM sku_availability_dirty d WHERE d.sku_id = r.sku_id),
                               (SELECT min(j.created_at)
                                FROM availability_fanout_job j
                                JOIN stock_position p ON p.warehouse_id = j.warehouse_id AND p.sku_id = r.sku_id
                                WHERE j.completed_at IS NULL
                                  AND (j.cursor_sku_id IS NULL OR r.sku_id > j.cursor_sku_id))
                           ) AS oldest_pending_at
                    FROM requested r
                    LEFT JOIN sku_availability_state s ON s.sku_id = r.sku_id
                    """);
            Array array = con.createArrayOf("varchar", skuIds.toArray());
            statement.setArray(1, array);
            return statement;
        }, (rs, i) -> new ReadRow(rs.getString("sku_id"),
                (Boolean) rs.getObject("is_in_stock"),
                (Long) rs.getObject("availability_revision"),
                instant(rs.getTimestamp("evaluated_at")),
                instant(rs.getTimestamp("oldest_pending_at"))));
    }

    // ── Warehouse fan-out ─────────────────────────────────────────────────────

    public record FanoutJob(String fanoutJobId, String warehouseId, long warehouseRevision, Instant createdAt,
                            String cursorSkuId, String correlationId, String causationId) {
    }

    private static final RowMapper<FanoutJob> FANOUT = (rs, i) -> new FanoutJob(rs.getString("fanout_job_id"),
            rs.getString("warehouse_id"), rs.getLong("warehouse_revision"), rs.getTimestamp("created_at").toInstant(),
            rs.getString("cursor_sku_id"), rs.getString("correlation_id"), rs.getString("causation_id"));

    public void createFanoutJob(String warehouseId, long warehouseRevision, CommandContext context) {
        jdbc.update("""
                INSERT INTO availability_fanout_job (fanout_job_id, warehouse_id, warehouse_revision, created_at,
                                                     correlation_id, causation_id)
                VALUES (?, ?, ?, ?, ?, ?)
                ON CONFLICT (warehouse_id, warehouse_revision) DO NOTHING
                """, Ids.newId(), warehouseId, warehouseRevision, Timestamp.from(context.now()),
                context.correlationId(), context.commandId());
    }

    public Optional<FanoutJob> claimFanoutJob() {
        return jdbc.query("""
                SELECT fanout_job_id, warehouse_id, warehouse_revision, created_at, cursor_sku_id, correlation_id,
                       causation_id
                FROM availability_fanout_job WHERE completed_at IS NULL
                ORDER BY created_at LIMIT 1 FOR UPDATE SKIP LOCKED
                """, FANOUT).stream().findFirst();
    }

    public List<String> skusAtWarehouse(String warehouseId, String afterSkuId, int limit) {
        if (afterSkuId == null) {
            return jdbc.queryForList("SELECT sku_id FROM stock_position WHERE warehouse_id = ? ORDER BY sku_id LIMIT ?",
                    String.class, warehouseId, limit);
        }
        return jdbc.queryForList("SELECT sku_id FROM stock_position WHERE warehouse_id = ? AND sku_id > ? "
                + "ORDER BY sku_id LIMIT ?", String.class, warehouseId, afterSkuId, limit);
    }

    public void advanceFanout(String fanoutJobId, String cursorSkuId, Instant completedAt) {
        jdbc.update("UPDATE availability_fanout_job SET cursor_sku_id = ?, completed_at = ? WHERE fanout_job_id = ?",
                cursorSkuId, completedAt == null ? null : Timestamp.from(completedAt), fanoutJobId);
    }

    // ── Search readiness ──────────────────────────────────────────────────────

    public record Readiness(String skuId, long catalogRevision, Instant registeredAt, Instant revokedAt) {
        public boolean active() {
            return revokedAt == null;
        }
    }

    private static final RowMapper<Readiness> READINESS = (rs, i) -> new Readiness(rs.getString("sku_id"),
            rs.getLong("catalog_revision"), rs.getTimestamp("registered_at").toInstant(),
            instant(rs.getTimestamp("revoked_at")));

    public Optional<Readiness> findReadiness(String skuId) {
        return jdbc.query("SELECT sku_id, catalog_revision, registered_at, revoked_at "
                + "FROM search_projection_readiness WHERE sku_id = ?", READINESS, skuId).stream().findFirst();
    }

    public Optional<Readiness> lockReadiness(String skuId) {
        return jdbc.query("SELECT sku_id, catalog_revision, registered_at, revoked_at "
                + "FROM search_projection_readiness WHERE sku_id = ? FOR UPDATE", READINESS, skuId).stream().findFirst();
    }

    public boolean insertReadiness(String skuId, long catalogRevision, Instant now) {
        return jdbc.update("INSERT INTO search_projection_readiness (sku_id, catalog_revision, registered_at) "
                + "VALUES (?, ?, ?) ON CONFLICT (sku_id) DO NOTHING", skuId, catalogRevision, Timestamp.from(now)) == 1;
    }

    public void activateReadiness(String skuId, long catalogRevision, Instant now) {
        jdbc.update("UPDATE search_projection_readiness SET catalog_revision = ?, registered_at = ?, revoked_at = NULL "
                + "WHERE sku_id = ?", catalogRevision, Timestamp.from(now), skuId);
    }

    public void revokeReadiness(String skuId, long catalogRevision, Instant now) {
        jdbc.update("UPDATE search_projection_readiness SET catalog_revision = ?, revoked_at = COALESCE(revoked_at, ?) "
                + "WHERE sku_id = ?", catalogRevision, Timestamp.from(now), skuId);
    }

    public void createPublicationTask(String skuId, long catalogRevision, Instant now) {
        jdbc.update("INSERT INTO readiness_publication_task (sku_id, catalog_revision, created_at) VALUES (?, ?, ?) "
                + "ON CONFLICT (sku_id, catalog_revision) DO NOTHING", skuId, catalogRevision, Timestamp.from(now));
    }

    public record PublicationTask(String skuId, long catalogRevision) {
    }

    public List<PublicationTask> claimPublicationTasks(int limit, Instant now, Instant claimUntil) {
        return jdbc.query("""
                UPDATE readiness_publication_task t SET claimed_until = ?
                FROM (SELECT sku_id, catalog_revision FROM readiness_publication_task
                      WHERE completed_at IS NULL AND (claimed_until IS NULL OR claimed_until < ?)
                      ORDER BY created_at LIMIT ? FOR UPDATE SKIP LOCKED) c
                WHERE t.sku_id = c.sku_id AND t.catalog_revision = c.catalog_revision
                RETURNING t.sku_id, t.catalog_revision
                """, (rs, i) -> new PublicationTask(rs.getString("sku_id"), rs.getLong("catalog_revision")),
                Timestamp.from(claimUntil), Timestamp.from(now), limit);
    }

    public void completePublicationTask(PublicationTask task, Instant now) {
        jdbc.update("UPDATE readiness_publication_task SET completed_at = ? WHERE sku_id = ? AND catalog_revision = ?",
                Timestamp.from(now), task.skuId(), task.catalogRevision());
    }

    // ── Snapshot export ───────────────────────────────────────────────────────

    /** Current state of SKUs with active readiness, keyset-paged by SKU. */
    public List<State> registeredStates(String afterSkuId, int limit) {
        if (afterSkuId == null) {
            return jdbc.query("""
                    SELECT s.sku_id, s.is_in_stock, s.availability_revision, s.evaluated_at
                    FROM sku_availability_state s
                    JOIN search_projection_readiness r ON r.sku_id = s.sku_id AND r.revoked_at IS NULL
                    ORDER BY s.sku_id LIMIT ?""", STATE, limit);
        }
        return jdbc.query("""
                SELECT s.sku_id, s.is_in_stock, s.availability_revision, s.evaluated_at
                FROM sku_availability_state s
                JOIN search_projection_readiness r ON r.sku_id = s.sku_id AND r.revoked_at IS NULL
                WHERE s.sku_id > ? ORDER BY s.sku_id LIMIT ?""", STATE, afterSkuId, limit);
    }

    private static Instant instant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }
}
