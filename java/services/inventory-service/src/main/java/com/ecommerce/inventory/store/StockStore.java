package com.ecommerce.inventory.store;

import com.ecommerce.inventory.command.CommandContext;
import com.ecommerce.inventory.common.Ids;
import com.ecommerce.inventory.domain.BucketDelta;
import com.ecommerce.inventory.domain.Buckets;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
        String movementId = Ids.newId();
        jdbc.update("""
                INSERT INTO stock_movement (movement_id, warehouse_id, sku_id, source_operation_type,
                    source_operation_id, reverses_movement_id, reversal_sequence, delta_on_hand, delta_available,
                    delta_reserved, delta_committed, delta_quarantine, command_id, recorded_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, movementId, position.key().warehouseId(), position.key().skuId(),
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
