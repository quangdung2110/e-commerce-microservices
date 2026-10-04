package com.ecommerce.inventory.store;

import com.ecommerce.inventory.domain.RefundScopeStates.State;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Refund Scope, return receipt, inspection and quarantine disposition records. They are
 * locked after the reservation root and before stock positions (data.md §4).
 */
@Repository
public class RefundStore {

    private final JdbcTemplate jdbc;

    public RefundStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ── Scope ─────────────────────────────────────────────────────────────────

    public record Scope(String refundScopeId, String orderId, String refundRequestId, State state,
                        String closeReason, long revision, Instant registeredAt) {
    }

    public record ScopeLine(String refundScopeId, String orderLineId, int lineNo, String skuId, long expected,
                            long received, long passed, long failed) {
    }

    private static final String SCOPE_COLUMNS =
            "refund_scope_id, order_id, refund_request_id, state, close_reason, revision, registered_at";

    private static final RowMapper<Scope> SCOPE = (rs, i) -> new Scope(rs.getString("refund_scope_id"),
            rs.getString("order_id"), rs.getString("refund_request_id"), State.valueOf(rs.getString("state")),
            rs.getString("close_reason"), rs.getLong("revision"), rs.getTimestamp("registered_at").toInstant());

    private static final String SCOPE_LINE_COLUMNS = "refund_scope_id, order_line_id, line_no, sku_id, "
            + "expected_quantity, received_quantity, passed_quantity, failed_quantity";

    private static final RowMapper<ScopeLine> SCOPE_LINE = (rs, i) -> new ScopeLine(rs.getString("refund_scope_id"),
            rs.getString("order_line_id"), rs.getInt("line_no"), rs.getString("sku_id"),
            rs.getLong("expected_quantity"), rs.getLong("received_quantity"), rs.getLong("passed_quantity"),
            rs.getLong("failed_quantity"));

    public void insertScope(Scope scope, Instant now) {
        jdbc.update("INSERT INTO refund_scope (" + SCOPE_COLUMNS + ", updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                scope.refundScopeId(), scope.orderId(), scope.refundRequestId(), scope.state().name(),
                scope.closeReason(), scope.revision(), Timestamp.from(scope.registeredAt()), Timestamp.from(now));
    }

    public void insertScopeLine(ScopeLine line) {
        jdbc.update("INSERT INTO refund_scope_line (" + SCOPE_LINE_COLUMNS + ") VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                line.refundScopeId(), line.orderLineId(), line.lineNo(), line.skuId(), line.expected(),
                line.received(), line.passed(), line.failed());
    }

    public Optional<Scope> find(String refundScopeId) {
        return jdbc.query("SELECT " + SCOPE_COLUMNS + " FROM refund_scope WHERE refund_scope_id = ?", SCOPE,
                refundScopeId).stream().findFirst();
    }

    public Optional<Scope> findByOrder(String orderId) {
        return jdbc.query("SELECT " + SCOPE_COLUMNS + " FROM refund_scope WHERE order_id = ?", SCOPE, orderId)
                .stream().findFirst();
    }

    public Optional<Scope> findByRefundRequest(String refundRequestId) {
        return jdbc.query("SELECT " + SCOPE_COLUMNS + " FROM refund_scope WHERE refund_request_id = ?", SCOPE,
                refundRequestId).stream().findFirst();
    }

    public Optional<Scope> lock(String refundScopeId) {
        return jdbc.query("SELECT " + SCOPE_COLUMNS + " FROM refund_scope WHERE refund_scope_id = ? FOR UPDATE",
                SCOPE, refundScopeId).stream().findFirst();
    }

    public List<ScopeLine> lockLines(String refundScopeId) {
        return jdbc.query("SELECT " + SCOPE_LINE_COLUMNS + " FROM refund_scope_line WHERE refund_scope_id = ? "
                + "ORDER BY line_no FOR UPDATE", SCOPE_LINE, refundScopeId);
    }

    public List<ScopeLine> lines(String refundScopeId) {
        return jdbc.query("SELECT " + SCOPE_LINE_COLUMNS + " FROM refund_scope_line WHERE refund_scope_id = ? "
                + "ORDER BY line_no", SCOPE_LINE, refundScopeId);
    }

    public void updateScope(String refundScopeId, State state, String closeReason, long revision, Instant now) {
        jdbc.update("UPDATE refund_scope SET state = ?, close_reason = ?, revision = ?, updated_at = ? "
                + "WHERE refund_scope_id = ?", state.name(), closeReason, revision, Timestamp.from(now), refundScopeId);
    }

    public void updateScopeLine(ScopeLine line) {
        jdbc.update("UPDATE refund_scope_line SET received_quantity = ?, passed_quantity = ?, failed_quantity = ? "
                        + "WHERE refund_scope_id = ? AND order_line_id = ?", line.received(), line.passed(),
                line.failed(), line.refundScopeId(), line.orderLineId());
    }

    // ── Return receipt lines ──────────────────────────────────────────────────

    public record ReceiptLine(String returnReceiptId, String returnReceiptLineId, String refundScopeId,
                              String orderLineId, String skuId, String warehouseId, long quantity,
                              long inspectedQuantity, Instant receivedAt) {
    }

    private static final String RECEIPT_COLUMNS = "return_receipt_id, return_receipt_line_id, refund_scope_id, "
            + "order_line_id, sku_id, warehouse_id, quantity, inspected_quantity, received_at";

    private static final RowMapper<ReceiptLine> RECEIPT = (rs, i) -> new ReceiptLine(
            rs.getString("return_receipt_id"), rs.getString("return_receipt_line_id"),
            rs.getString("refund_scope_id"), rs.getString("order_line_id"), rs.getString("sku_id"),
            rs.getString("warehouse_id"), rs.getLong("quantity"), rs.getLong("inspected_quantity"),
            rs.getTimestamp("received_at").toInstant());

    public void insertReceiptLine(ReceiptLine line, String packageReference, String evidenceReference,
                                  String movementId, Instant now) {
        jdbc.update("""
                INSERT INTO return_receipt_line (return_receipt_id, return_receipt_line_id, refund_scope_id,
                    order_line_id, sku_id, warehouse_id, quantity, inspected_quantity, package_reference, received_at,
                    evidence_reference, movement_id, recorded_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, 0, ?, ?, ?, ?, ?)
                """, line.returnReceiptId(), line.returnReceiptLineId(), line.refundScopeId(), line.orderLineId(),
                line.skuId(), line.warehouseId(), line.quantity(), packageReference,
                Timestamp.from(line.receivedAt()), evidenceReference, movementId, Timestamp.from(now));
    }

    public Optional<ReceiptLine> findReceiptLine(String returnReceiptId, String returnReceiptLineId) {
        return jdbc.query("SELECT " + RECEIPT_COLUMNS + " FROM return_receipt_line "
                        + "WHERE return_receipt_id = ? AND return_receipt_line_id = ?", RECEIPT, returnReceiptId,
                returnReceiptLineId).stream().findFirst();
    }

    public Optional<ReceiptLine> lockReceiptLine(String returnReceiptId, String returnReceiptLineId) {
        return jdbc.query("SELECT " + RECEIPT_COLUMNS + " FROM return_receipt_line "
                        + "WHERE return_receipt_id = ? AND return_receipt_line_id = ? FOR UPDATE", RECEIPT,
                returnReceiptId, returnReceiptLineId).stream().findFirst();
    }

    public void addInspected(String returnReceiptId, String returnReceiptLineId, long quantity) {
        jdbc.update("UPDATE return_receipt_line SET inspected_quantity = inspected_quantity + ? "
                + "WHERE return_receipt_id = ? AND return_receipt_line_id = ?", quantity, returnReceiptId,
                returnReceiptLineId);
    }

    public record InspectionQueueItem(String refundScopeId, String orderId, String orderLineId,
                                      String returnReceiptId, String returnReceiptLineId, String skuId,
                                      String warehouseId, long receivedQuantity, long uninspectedQuantity,
                                      Instant receivedAt) {
    }

    public List<InspectionQueueItem> inspectionQueue(String warehouseId, Instant afterReceivedAt, String afterReceiptId,
                                                     String afterReceiptLineId, int limit) {
        StringBuilder sql = new StringBuilder("""
                SELECT r.refund_scope_id, s.order_id, r.order_line_id, r.return_receipt_id, r.return_receipt_line_id,
                       r.sku_id, r.warehouse_id, r.quantity, r.quantity - r.inspected_quantity AS uninspected,
                       r.received_at
                FROM return_receipt_line r JOIN refund_scope s ON s.refund_scope_id = r.refund_scope_id
                WHERE r.warehouse_id = ? AND r.inspected_quantity < r.quantity""");
        List<Object> args = new ArrayList<>(List.of(warehouseId));
        if (afterReceivedAt != null) {
            sql.append(" AND (r.received_at, r.return_receipt_id, r.return_receipt_line_id) > (?, ?, ?)");
            args.add(Timestamp.from(afterReceivedAt));
            args.add(afterReceiptId);
            args.add(afterReceiptLineId);
        }
        sql.append(" ORDER BY r.received_at, r.return_receipt_id, r.return_receipt_line_id LIMIT ?");
        args.add(limit);
        return jdbc.query(sql.toString(), (rs, i) -> new InspectionQueueItem(rs.getString("refund_scope_id"),
                rs.getString("order_id"), rs.getString("order_line_id"), rs.getString("return_receipt_id"),
                rs.getString("return_receipt_line_id"), rs.getString("sku_id"), rs.getString("warehouse_id"),
                rs.getLong("quantity"), rs.getLong("uninspected"), rs.getTimestamp("received_at").toInstant()),
                args.toArray());
    }

    // ── Inspection results ────────────────────────────────────────────────────

    public record Inspection(String inspectionId, String returnReceiptId, String returnReceiptLineId,
                             String refundScopeId, String orderLineId, String warehouseId, String skuId,
                             long quantity, String result, String failReason, long disposedQuantity,
                             String latestHoldReason, Instant inspectedAt) {

        public long disposable() {
            return "FAIL".equals(result) ? quantity - disposedQuantity : 0;
        }
    }

    private static final String INSPECTION_COLUMNS = "inspection_id, return_receipt_id, return_receipt_line_id, "
            + "refund_scope_id, order_line_id, warehouse_id, sku_id, quantity, result, fail_reason, disposed_quantity, "
            + "latest_hold_reason, inspected_at";

    private static final RowMapper<Inspection> INSPECTION = (rs, i) -> new Inspection(
            rs.getString("inspection_id"), rs.getString("return_receipt_id"), rs.getString("return_receipt_line_id"),
            rs.getString("refund_scope_id"), rs.getString("order_line_id"), rs.getString("warehouse_id"),
            rs.getString("sku_id"), rs.getLong("quantity"), rs.getString("result"), rs.getString("fail_reason"),
            rs.getLong("disposed_quantity"), rs.getString("latest_hold_reason"),
            rs.getTimestamp("inspected_at").toInstant());

    public void insertInspection(Inspection inspection, String checklistReference, String evidenceReference,
                                 String movementId) {
        jdbc.update("INSERT INTO inspection_result (" + INSPECTION_COLUMNS + ", checklist_reference, "
                        + "evidence_reference, movement_id) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                inspection.inspectionId(), inspection.returnReceiptId(), inspection.returnReceiptLineId(),
                inspection.refundScopeId(), inspection.orderLineId(), inspection.warehouseId(), inspection.skuId(),
                inspection.quantity(), inspection.result(), inspection.failReason(), inspection.disposedQuantity(),
                inspection.latestHoldReason(), Timestamp.from(inspection.inspectedAt()), checklistReference,
                evidenceReference, movementId);
    }

    public boolean inspectionExists(String inspectionId) {
        Integer n = jdbc.queryForObject("SELECT count(*) FROM inspection_result WHERE inspection_id = ?",
                Integer.class, inspectionId);
        return n != null && n > 0;
    }

    public Optional<Inspection> findInspection(String inspectionId) {
        return jdbc.query("SELECT " + INSPECTION_COLUMNS + " FROM inspection_result WHERE inspection_id = ?",
                INSPECTION, inspectionId).stream().findFirst();
    }

    public Optional<Inspection> lockInspection(String inspectionId) {
        return jdbc.query("SELECT " + INSPECTION_COLUMNS + " FROM inspection_result WHERE inspection_id = ? "
                + "FOR UPDATE", INSPECTION, inspectionId).stream().findFirst();
    }

    public void updateDisposal(String inspectionId, long disposedQuantity, String latestHoldReason) {
        jdbc.update("UPDATE inspection_result SET disposed_quantity = ?, latest_hold_reason = ? WHERE inspection_id = ?",
                disposedQuantity, latestHoldReason, inspectionId);
    }

    public List<Inspection> quarantineItems(String warehouseId, Instant afterInspectedAt, String afterInspectionId,
                                            int limit) {
        StringBuilder sql = new StringBuilder("SELECT " + INSPECTION_COLUMNS + " FROM inspection_result "
                + "WHERE warehouse_id = ? AND result = 'FAIL' AND disposed_quantity < quantity");
        List<Object> args = new ArrayList<>(List.of(warehouseId));
        if (afterInspectedAt != null) {
            sql.append(" AND (inspected_at, inspection_id) > (?, ?)");
            args.add(Timestamp.from(afterInspectedAt));
            args.add(afterInspectionId);
        }
        sql.append(" ORDER BY inspected_at, inspection_id LIMIT ?");
        args.add(limit);
        return jdbc.query(sql.toString(), INSPECTION, args.toArray());
    }

    // ── Quarantine dispositions ───────────────────────────────────────────────

    public record Disposition(String dispositionId, String dispositionLineId, String inspectionId,
                              String warehouseId, String skuId, long quantity, String disposition, String reason,
                              String movementId) {
    }

    private static final String DISPOSITION_COLUMNS = "disposition_id, disposition_line_id, inspection_id, "
            + "warehouse_id, sku_id, quantity, disposition, reason, movement_id";

    private static final RowMapper<Disposition> DISPOSITION = (rs, i) -> new Disposition(
            rs.getString("disposition_id"), rs.getString("disposition_line_id"), rs.getString("inspection_id"),
            rs.getString("warehouse_id"), rs.getString("sku_id"), rs.getLong("quantity"),
            rs.getString("disposition"), rs.getString("reason"), rs.getString("movement_id"));

    public void insertDisposition(Disposition disposition, String departureReference, Instant departedAt,
                                  String evidenceReference, String actorId, Instant now) {
        jdbc.update("""
                INSERT INTO quarantine_disposition (disposition_id, disposition_line_id, inspection_id, warehouse_id,
                    sku_id, quantity, disposition, reason, physical_departure_reference, departed_at,
                    evidence_reference, movement_id, actor_id, recorded_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, disposition.dispositionId(), disposition.dispositionLineId(), disposition.inspectionId(),
                disposition.warehouseId(), disposition.skuId(), disposition.quantity(), disposition.disposition(),
                disposition.reason(), departureReference, departedAt == null ? null : Timestamp.from(departedAt),
                evidenceReference, disposition.movementId(), actorId, Timestamp.from(now));
    }

    public boolean dispositionExists(String dispositionId, String dispositionLineId) {
        Integer n = jdbc.queryForObject("SELECT count(*) FROM quarantine_disposition "
                + "WHERE disposition_id = ? AND disposition_line_id = ?", Integer.class, dispositionId, dispositionLineId);
        return n != null && n > 0;
    }

    public Optional<Disposition> lockDisposition(String dispositionId, String dispositionLineId) {
        return jdbc.query("SELECT " + DISPOSITION_COLUMNS + " FROM quarantine_disposition "
                        + "WHERE disposition_id = ? AND disposition_line_id = ? FOR UPDATE", DISPOSITION, dispositionId,
                dispositionLineId).stream().findFirst();
    }

    public record DispositionReversalTotals(int count, long quantity) {
    }

    public DispositionReversalTotals reversalsOf(String dispositionId, String dispositionLineId) {
        return jdbc.queryForObject("SELECT count(*) AS n, coalesce(sum(quantity), 0) AS q FROM disposition_reversal "
                        + "WHERE disposition_id = ? AND disposition_line_id = ?",
                (rs, i) -> new DispositionReversalTotals(rs.getInt("n"), rs.getLong("q")), dispositionId,
                dispositionLineId);
    }

    public void insertDispositionReversal(String dispositionId, String dispositionLineId, int sequence, long quantity,
                                          String reason, String reasonNote, Instant returnedAt, String returnReference,
                                          String evidenceReference, String movementId, String actorId, Instant now) {
        jdbc.update("""
                INSERT INTO disposition_reversal (disposition_id, disposition_line_id, reversal_sequence, quantity,
                    reason, reason_note, physically_returned_at, physical_return_reference, evidence_reference,
                    movement_id, actor_id, recorded_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, dispositionId, dispositionLineId, sequence, quantity, reason, reasonNote,
                Timestamp.from(returnedAt), returnReference, evidenceReference, movementId, actorId,
                Timestamp.from(now));
    }
}
