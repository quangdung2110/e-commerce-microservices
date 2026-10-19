package com.ecommerce.inventory.store;

import com.ecommerce.inventory.command.CommandContext;
import com.ecommerce.inventory.common.Ids;
import com.ecommerce.inventory.domain.BucketDelta;
import com.ecommerce.inventory.domain.Buckets;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * {@code stock_position} and {@code stock_movement}. Positions are locked one by one in
 * Java {@code (warehouse_id, sku_id)} order so every transaction acquires them in the same
 * order (data.md §4, "Required lock order"); an ORDER BY under database collation is never
 * used for locking.
 */
@Repository
public class StockStore {

    private static final String POSITION_COLUMNS =
            "warehouse_id, sku_id, on_hand, available_to_sell, reserved, committed, quarantine, revision, updated_at";

    private static final RowMapper<Position> POSITION = (rs, i) -> new Position(
            new PositionKey(rs.getString("warehouse_id"), rs.getString("sku_id")),
            new Buckets(rs.getLong("on_hand"), rs.getLong("available_to_sell"), rs.getLong("reserved"),
                    rs.getLong("committed"), rs.getLong("quarantine")),
            rs.getLong("revision"),
            rs.getTimestamp("updated_at").toInstant());

    private final JdbcTemplate jdbc;

    public StockStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record PositionKey(String warehouseId, String skuId) implements Comparable<PositionKey> {
        @Override
        public int compareTo(PositionKey other) {
            int byWarehouse = warehouseId.compareTo(other.warehouseId);
            return byWarehouse != 0 ? byWarehouse : skuId.compareTo(other.skuId);
        }
    }

    public record Position(PositionKey key, Buckets buckets, long revision, Instant updatedAt) {
    }

    /** A movement to append for an applied delta. */
    public record MovementSpec(String sourceOperationType, String sourceOperationId,
                               String reversesMovementId, Integer reversalSequence) {
        public static MovementSpec of(String type, String sourceId) {
            return new MovementSpec(type, sourceId, null, null);
        }
    }

    /** Locks existing positions in stable order; absent keys are missing from the result. */
    public Map<PositionKey, Position> lockExisting(Collection<PositionKey> keys) {
        Map<PositionKey, Position> locked = new LinkedHashMap<>();
        for (PositionKey key : new TreeSet<>(keys)) {
            lock(key).ifPresent(position -> locked.put(key, position));
        }
        return locked;
    }

    public Optional<Position> lock(PositionKey key) {
        return jdbc.query("SELECT " + POSITION_COLUMNS + " FROM stock_position WHERE warehouse_id = ? AND sku_id = ? "
                + "FOR UPDATE", POSITION, key.warehouseId(), key.skuId()).stream().findFirst();
    }

    /** Creates an empty position when absent (receipts may stock a new SKU), then locks it. */
    public Position lockOrCreate(PositionKey key, Instant now) {
        jdbc.update("""
                INSERT INTO stock_position (warehouse_id, sku_id, on_hand, available_to_sell, reserved, committed,
                                            quarantine, revision, updated_at)
                VALUES (?, ?, 0, 0, 0, 0, 0, 1, ?)
                ON CONFLICT (warehouse_id, sku_id) DO NOTHING
                """, key.warehouseId(), key.skuId(), Timestamp.from(now));
        return lock(key).orElseThrow();
    }

    /**
     * Applies a delta to a locked position and appends its immutable movement in the same
     * transaction. The table constraint re-checks the bucket equation.
     *
     * @return the new movement ID
     */
    public String apply(Position position, BucketDelta delta, MovementSpec movement, CommandContext context) {
        Buckets next = position.buckets().apply(delta);
        jdbc.update("""
                UPDATE stock_position
                SET on_hand = ?, available_to_sell = ?, reserved = ?, committed = ?, quarantine = ?,
                    revision = revision + 1, updated_at = ?
                WHERE warehouse_id = ? AND sku_id = ? AND revision = ?
                """, next.onHand(), next.availableToSell(), next.reserved(), next.committed(), next.quarantine(),
                Timestamp.from(context.now()), position.key().warehouseId(), position.key().skuId(),
                position.revision());
        return appendMovement(position.key(), delta, movement, context);
    }

    /**
     * Applies a delta to an unlocked position in one guarded statement and takes the row lock
     * only now (ADR-033): the update happens only if every bucket stays non-negative. Under
     * {@code READ COMMITTED} PostgreSQL re-evaluates the guard on the newest row version after
     * waiting for a concurrent holder, so check and change are one atomic step. Callers apply
     * positions in {@link PositionKey} order, as the last stock statements of their transaction.
     *
     * @return {@code false} when the position is absent or a bucket would go negative; nothing changed
     */
    public boolean applyGuarded(PositionKey key, BucketDelta delta, Instant now) {
        return jdbc.update("""
                UPDATE stock_position
                SET on_hand = on_hand + ?, available_to_sell = available_to_sell + ?, reserved = reserved + ?,
                    committed = committed + ?, quarantine = quarantine + ?, revision = revision + 1, updated_at = ?
                WHERE warehouse_id = ? AND sku_id = ?
                  AND on_hand + ? >= 0 AND available_to_sell + ? >= 0 AND reserved + ? >= 0
                  AND committed + ? >= 0 AND quarantine + ? >= 0
                """, delta.onHand(), delta.availableToSell(), delta.reserved(), delta.committed(), delta.quarantine(),
                Timestamp.from(now), key.warehouseId(), key.skuId(),
                delta.onHand(), delta.availableToSell(), delta.reserved(), delta.committed(), delta.quarantine()) == 1;
    }

    /**
     * {@link #applyGuarded} plus, in the same statement and only when the update happened, the
     * availability dirty marks of {@code dirtySkuIds} with the coalescing of
     * {@link AvailabilityStore#markDirty(Collection, CommandContext)}. Saves one round trip while
     * the hot row is locked (ADR-033). Use it only for the <em>last</em> position a transaction
     * updates: dirty rows are then locked after every position row, in the order of
     * {@code dirtySkuIds} (pass them sorted, as {@code markDirty} sorts them), so the lock order
     * of data.md is unchanged.
     *
     * @return {@code false} when the position is absent or a bucket would go negative; nothing changed
     */
    public boolean applyGuardedMarkingDirty(PositionKey key, BucketDelta delta, List<String> dirtySkuIds,
                                            CommandContext context) {
        String sql = """
                WITH changed AS (
                    UPDATE stock_position
                    SET on_hand = on_hand + ?, available_to_sell = available_to_sell + ?, reserved = reserved + ?,
                        committed = committed + ?, quarantine = quarantine + ?, revision = revision + 1,
                        updated_at = ?
                    WHERE warehouse_id = ? AND sku_id = ?
                      AND on_hand + ? >= 0 AND available_to_sell + ? >= 0 AND reserved + ? >= 0
                      AND committed + ? >= 0 AND quarantine + ? >= 0
                    RETURNING 1
                ), marked AS (
                    INSERT INTO sku_availability_dirty (sku_id, first_marked_at, mark_seq, last_correlation_id,
                                                        last_causation_id)
                    SELECT u.sku_id, ?, 1, ?, ?
                    FROM unnest(?::varchar[]) WITH ORDINALITY AS u(sku_id, n)
                    WHERE EXISTS (SELECT 1 FROM changed)
                    ORDER BY u.n
                    ON CONFLICT (sku_id) DO UPDATE
                    SET mark_seq = sku_availability_dirty.mark_seq + 1,
                        first_marked_at = LEAST(sku_availability_dirty.first_marked_at, EXCLUDED.first_marked_at),
                        last_correlation_id = EXCLUDED.last_correlation_id,
                        last_causation_id = EXCLUDED.last_causation_id
                    RETURNING 1
                )
                SELECT (SELECT count(*) FROM changed) AS changed, (SELECT count(*) FROM marked) AS marked
                """;
        Timestamp now = Timestamp.from(context.now());
        Long changed = jdbc.query(connection -> {
            PreparedStatement statement = connection.prepareStatement(sql);
            int i = 0;
            statement.setLong(++i, delta.onHand());
            statement.setLong(++i, delta.availableToSell());
            statement.setLong(++i, delta.reserved());
            statement.setLong(++i, delta.committed());
            statement.setLong(++i, delta.quarantine());
            statement.setTimestamp(++i, now);
            statement.setString(++i, key.warehouseId());
            statement.setString(++i, key.skuId());
            statement.setLong(++i, delta.onHand());
            statement.setLong(++i, delta.availableToSell());
            statement.setLong(++i, delta.reserved());
            statement.setLong(++i, delta.committed());
            statement.setLong(++i, delta.quarantine());
            statement.setTimestamp(++i, now);
            statement.setString(++i, context.correlationId());
            statement.setString(++i, context.commandId());
            statement.setArray(++i, connection.createArrayOf("varchar", dirtySkuIds.toArray()));
            return statement;
        }, rs -> rs.next() ? rs.getLong("changed") : 0L);
        return changed != null && changed == 1;
    }

    /** Positions that exist, read without a lock; rows are never deleted, so absence is final. */
    public Set<PositionKey> existing(Collection<PositionKey> keys) {
        Set<PositionKey> found = new TreeSet<>();
        for (PositionKey key : new TreeSet<>(keys)) {
            Integer n = jdbc.queryForObject("SELECT count(*) FROM stock_position WHERE warehouse_id = ? AND sku_id = ?",
                    Integer.class, key.warehouseId(), key.skuId());
            if (n != null && n > 0) {
                found.add(key);
            }
        }
        return found;
    }

    /**
     * Appends the immutable movement of a delta. {@code stock_movement} has no foreign key to
     * {@code stock_position}, so this touches no position row and can run before the guarded update.
     *
     * @return the new movement ID
     */
    public String appendMovement(PositionKey key, BucketDelta delta, MovementSpec movement, CommandContext context) {
        String movementId = Ids.newId();
        jdbc.update("""
                INSERT INTO stock_movement (movement_id, warehouse_id, sku_id, source_operation_type,
                    source_operation_id, reverses_movement_id, reversal_sequence, delta_on_hand, delta_available,
                    delta_reserved, delta_committed, delta_quarantine, command_id, recorded_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, movementId, key.warehouseId(), key.skuId(),
                movement.sourceOperationType(), movement.sourceOperationId(), movement.reversesMovementId(),
                movement.reversalSequence(), delta.onHand(), delta.availableToSell(), delta.reserved(),
                delta.committed(), delta.quarantine(), context.commandId(), Timestamp.from(context.now()));
        return movementId;
    }

    // ── Movements ─────────────────────────────────────────────────────────────

    public record Movement(String movementId, String warehouseId, String skuId, String sourceOperationType,
                           String sourceOperationId, String reversesMovementId, BucketDelta delta,
                           Instant recordedAt) {
    }

    private static final RowMapper<Movement> MOVEMENT = (rs, i) -> new Movement(
            rs.getString("movement_id"), rs.getString("warehouse_id"), rs.getString("sku_id"),
            rs.getString("source_operation_type"), rs.getString("source_operation_id"),
            rs.getString("reverses_movement_id"),
            new BucketDelta(rs.getLong("delta_on_hand"), rs.getLong("delta_available"), rs.getLong("delta_reserved"),
                    rs.getLong("delta_committed"), rs.getLong("delta_quarantine")),
            rs.getTimestamp("recorded_at").toInstant());

    private static final String MOVEMENT_COLUMNS = "movement_id, warehouse_id, sku_id, source_operation_type, "
            + "source_operation_id, reverses_movement_id, delta_on_hand, delta_available, delta_reserved, "
            + "delta_committed, delta_quarantine, recorded_at";

    public Optional<Movement> findMovement(String movementId) {
        return jdbc.query("SELECT " + MOVEMENT_COLUMNS + " FROM stock_movement WHERE movement_id = ?",
                MOVEMENT, movementId).stream().findFirst();
    }

    /** Serializes reversals of one original movement; the row itself is never updated. */
    public Optional<Movement> lockMovement(String movementId) {
        return jdbc.query("SELECT " + MOVEMENT_COLUMNS + " FROM stock_movement WHERE movement_id = ? FOR UPDATE",
                MOVEMENT, movementId).stream().findFirst();
    }

    public record ReversalTotals(int count, long quantity) {
    }

    /** Accepted reversals of a sellable movement and their cumulative quantity. */
    public ReversalTotals reversalsOf(String movementId) {
        return jdbc.queryForObject("""
                SELECT count(*) AS n, coalesce(sum(abs(delta_available)), 0) AS q
                FROM stock_movement WHERE reverses_movement_id = ?
                """, (rs, i) -> new ReversalTotals(rs.getInt("n"), rs.getLong("q")), movementId);
    }

    public Optional<Position> findPosition(String warehouseId, String skuId) {
        return jdbc.query("SELECT " + POSITION_COLUMNS + " FROM stock_position WHERE warehouse_id = ? AND sku_id = ?",
                POSITION, warehouseId, skuId).stream().findFirst();
    }

    /** Admin query: positions, optionally narrowed by warehouse and/or SKU, keyset-paged on (warehouse_id, sku_id). */
    public List<Position> listPositions(String warehouseId, String skuId, PositionKey after, int limit) {
        StringBuilder sql = new StringBuilder("SELECT " + POSITION_COLUMNS + " FROM stock_position WHERE true");
        List<Object> args = new java.util.ArrayList<>();
        if (warehouseId != null) {
            sql.append(" AND warehouse_id = ?");
            args.add(warehouseId);
        }
        if (skuId != null) {
            sql.append(" AND sku_id = ?");
            args.add(skuId);
        }
        if (after != null) {
            sql.append(" AND (warehouse_id, sku_id) > (?, ?)");
            args.add(after.warehouseId());
            args.add(after.skuId());
        }
        sql.append(" ORDER BY warehouse_id, sku_id LIMIT ?");
        args.add(limit);
        return jdbc.query(sql.toString(), POSITION, args.toArray());
    }

    /** Admin query: movements newest first, optionally filtered, keyset-paged on (recorded_at, movement_id). */
    public List<Movement> listMovements(String warehouseId, String skuId, String sourceOperationType,
                                        Instant from, Instant to, Instant afterRecordedAt,
                                        String afterMovementId, int limit) {
        StringBuilder sql = new StringBuilder("SELECT " + MOVEMENT_COLUMNS + " FROM stock_movement WHERE true");
        List<Object> args = new java.util.ArrayList<>();
        if (warehouseId != null) {
            sql.append(" AND warehouse_id = ?");
            args.add(warehouseId);
        }
        if (skuId != null) {
            sql.append(" AND sku_id = ?");
            args.add(skuId);
        }
        if (sourceOperationType != null) {
            sql.append(" AND source_operation_type = ?");
            args.add(sourceOperationType);
        }
        if (from != null) {
            sql.append(" AND recorded_at >= ?");
            args.add(Timestamp.from(from));
        }
        if (to != null) {
            sql.append(" AND recorded_at < ?");
            args.add(Timestamp.from(to));
        }
        if (afterRecordedAt != null) {
            sql.append(" AND (recorded_at, movement_id) < (?, ?)");
            args.add(Timestamp.from(afterRecordedAt));
            args.add(afterMovementId);
        }
        sql.append(" ORDER BY recorded_at DESC, movement_id DESC LIMIT ?");
        args.add(limit);
        return jdbc.query(sql.toString(), MOVEMENT, args.toArray());
    }
}
