package com.ecommerce.inventory.store;

import com.ecommerce.inventory.domain.ReservationStates.LineState;
import com.ecommerce.inventory.domain.ReservationStates.RootState;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** {@code order_reservation}, {@code reservation_line} and {@code physical_dispatch}. */
@Repository
public class ReservationStore {

    private static final String ROOT_COLUMNS = "order_id, allocation_plan_id, state, accepted_at, expires_at, "
            + "release_reason, committed_release_reason, payment_reference, recovery_payment_reference, revision, "
            + "correlation_id";

    private static final RowMapper<Root> ROOT = (rs, i) -> new Root(
            rs.getString("order_id"), rs.getString("allocation_plan_id"), RootState.valueOf(rs.getString("state")),
            rs.getTimestamp("accepted_at").toInstant(), rs.getTimestamp("expires_at").toInstant(),
            rs.getString("release_reason"), rs.getString("committed_release_reason"),
            rs.getString("payment_reference"), rs.getString("recovery_payment_reference"),
            rs.getLong("revision"), rs.getString("correlation_id"));

    private static final String LINE_COLUMNS = "l.order_id, l.order_line_id, l.line_no, l.sku_id, l.quantity, "
            + "l.warehouse_id, l.state, l.committed_at, d.handover_reference, d.handed_over_at";

    private static final RowMapper<Line> LINE = (rs, i) -> new Line(
            rs.getString("order_id"), rs.getString("order_line_id"), rs.getInt("line_no"), rs.getString("sku_id"),
            rs.getLong("quantity"), rs.getString("warehouse_id"), LineState.valueOf(rs.getString("state")),
            instant(rs.getTimestamp("committed_at")), rs.getString("handover_reference"),
            instant(rs.getTimestamp("handed_over_at")));

    private final JdbcTemplate jdbc;

    public ReservationStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record Root(String orderId, String allocationPlanId, RootState state, Instant acceptedAt,
                       Instant expiresAt, String releaseReason, String committedReleaseReason,
                       String paymentReference, String recoveryPaymentReference, long revision,
                       String correlationId) {
    }

    public record Line(String orderId, String orderLineId, int lineNo, String skuId, long quantity,
                       String warehouseId, LineState state, Instant committedAt, String handoverReference,
                       Instant handedOverAt) {
    }

    public void insertRoot(Root root, Instant now) {
        jdbc.update("INSERT INTO order_reservation (" + ROOT_COLUMNS + ", updated_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                root.orderId(), root.allocationPlanId(), root.state().name(), Timestamp.from(root.acceptedAt()),
                Timestamp.from(root.expiresAt()), root.releaseReason(), root.committedReleaseReason(),
                root.paymentReference(), root.recoveryPaymentReference(), root.revision(), root.correlationId(),
                Timestamp.from(now));
    }

    public void insertLine(Line line) {
        jdbc.update("""
                INSERT INTO reservation_line (order_id, order_line_id, line_no, sku_id, quantity, warehouse_id, state,
                                              committed_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """, line.orderId(), line.orderLineId(), line.lineNo(), line.skuId(), line.quantity(),
                line.warehouseId(), line.state().name(), ts(line.committedAt()));
    }

    /**
     * Serializes Reserve commands that carry different command IDs for the same order, so the
     * loser sees the winner's committed root ({@code RESERVATION_ALREADY_EXISTS}) instead of
     * evaluating the stock the winner already took and answering {@code REJECT_ALL}.
     */
    public void lockOrderKey(String orderId) {
        jdbc.queryForObject("SELECT pg_advisory_xact_lock(hashtextextended('order-reserve:' || ?, 0))",
                Object.class, orderId);
    }

    public Optional<Root> find(String orderId) {
        return jdbc.query("SELECT " + ROOT_COLUMNS + " FROM order_reservation WHERE order_id = ?", ROOT, orderId)
                .stream().findFirst();
    }

    public Optional<Root> lock(String orderId) {
        return jdbc.query("SELECT " + ROOT_COLUMNS + " FROM order_reservation WHERE order_id = ? FOR UPDATE", ROOT,
                orderId).stream().findFirst();
    }

    /** Lines in line order; locked when the root is locked by the caller's transaction. */
    public List<Line> lockLines(String orderId) {
        return jdbc.query("SELECT " + LINE_COLUMNS + " FROM reservation_line l "
                + "LEFT JOIN physical_dispatch d ON d.order_id = l.order_id AND d.order_line_id = l.order_line_id "
                + "WHERE l.order_id = ? ORDER BY l.line_no FOR UPDATE OF l", LINE, orderId);
    }

    public List<Line> lines(String orderId) {
        return jdbc.query("SELECT " + LINE_COLUMNS + " FROM reservation_line l "
                + "LEFT JOIN physical_dispatch d ON d.order_id = l.order_id AND d.order_line_id = l.order_line_id "
                + "WHERE l.order_id = ? ORDER BY l.line_no", LINE, orderId);
    }

    public void updateLine(String orderId, String orderLineId, LineState state, Instant committedAt) {
        jdbc.update("UPDATE reservation_line SET state = ?, committed_at = ? WHERE order_id = ? AND order_line_id = ?",
                state.name(), ts(committedAt), orderId, orderLineId);
    }

    public void updateRoot(Root root, Instant now) {
        jdbc.update("""
                UPDATE order_reservation
                SET state = ?, release_reason = ?, committed_release_reason = ?, payment_reference = ?,
                    recovery_payment_reference = ?, revision = ?, updated_at = ?
                WHERE order_id = ?
                """, root.state().name(), root.releaseReason(), root.committedReleaseReason(),
                root.paymentReference(), root.recoveryPaymentReference(), root.revision(), Timestamp.from(now),
                root.orderId());
    }

    public boolean anyDispatch(String orderId) {
        Integer n = jdbc.queryForObject("SELECT count(*) FROM physical_dispatch WHERE order_id = ?", Integer.class,
                orderId);
        return n != null && n > 0;
    }

    public record Dispatch(String warehouseId, String dispatchId, String dispatchLineId, String orderId,
                           String orderLineId, String skuId, long quantity, String handoverReference,
                           Instant handedOverAt, String evidenceReference, String movementId, String actorId) {
    }

    public void insertDispatch(Dispatch dispatch, Instant now) {
        jdbc.update("""
                INSERT INTO physical_dispatch (warehouse_id, dispatch_id, dispatch_line_id, order_id, order_line_id,
                    sku_id, quantity, handover_reference, handed_over_at, evidence_reference, movement_id, actor_id,
                    recorded_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, dispatch.warehouseId(), dispatch.dispatchId(), dispatch.dispatchLineId(), dispatch.orderId(),
                dispatch.orderLineId(), dispatch.skuId(), dispatch.quantity(), dispatch.handoverReference(),
                Timestamp.from(dispatch.handedOverAt()), dispatch.evidenceReference(), dispatch.movementId(),
                dispatch.actorId(), Timestamp.from(now));
    }

    /** Admin dispatch queue: committed, undispatched lines at a warehouse, oldest commit first. */
    public List<Line> dispatchQueue(String warehouseId, Instant afterCommittedAt, String afterOrderId,
                                    String afterOrderLineId, int limit) {
        StringBuilder sql = new StringBuilder("SELECT " + LINE_COLUMNS + " FROM reservation_line l "
                + "LEFT JOIN physical_dispatch d ON d.order_id = l.order_id AND d.order_line_id = l.order_line_id "
                + "WHERE l.warehouse_id = ? AND l.state = 'COMMITTED'");
        List<Object> args = new ArrayList<>(List.of(warehouseId));
        if (afterCommittedAt != null) {
            sql.append(" AND (l.committed_at, l.order_id, l.order_line_id) > (?, ?, ?)");
            args.add(Timestamp.from(afterCommittedAt));
            args.add(afterOrderId);
            args.add(afterOrderLineId);
        }
        sql.append(" ORDER BY l.committed_at, l.order_id, l.order_line_id LIMIT ?");
        args.add(limit);
        return jdbc.query(sql.toString(), LINE, args.toArray());
    }

    public List<Root> reservedOlderThan(Instant acceptedBefore, int limit) {
        return jdbc.query("SELECT " + ROOT_COLUMNS + " FROM order_reservation WHERE state = 'RESERVED' "
                + "AND accepted_at < ? ORDER BY accepted_at LIMIT ?", ROOT, Timestamp.from(acceptedBefore), limit);
    }

    private static Timestamp ts(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }

    private static Instant instant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }
}
