package com.ecommerce.inventory.service;

import com.ecommerce.inventory.command.CommandStore;
import com.ecommerce.inventory.common.ApiException;
import com.ecommerce.inventory.common.ErrorCode;
import com.ecommerce.inventory.store.RefundStore;
import com.ecommerce.inventory.store.ReservationStore;
import com.ecommerce.inventory.store.StockStore;
import com.ecommerce.inventory.store.WarehouseStore;
import com.ecommerce.inventory.web.dto.AdminDtos;
import com.ecommerce.inventory.web.dto.WorkflowDtos;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.function.Function;

/**
 * Read-only recovery and operational queries. They never mutate state and expose no
 * customer PII (ADR-024).
 */
@Service
public class QueryService {

    private final CommandStore commands;
    private final ReservationStore reservations;
    private final RefundStore refunds;
    private final WarehouseStore warehouses;
    private final StockStore stock;

    public QueryService(CommandStore commands, ReservationStore reservations, RefundStore refunds,
                        WarehouseStore warehouses, StockStore stock) {
        this.commands = commands;
        this.reservations = reservations;
        this.refunds = refunds;
        this.warehouses = warehouses;
        this.stock = stock;
    }

    // ── Workflow recovery queries ─────────────────────────────────────────────

    public WorkflowDtos.CommandOutcomeView workflowCommand(String namespace, String commandId) {
        CommandStore.StoredCommand c = commands.find(namespace, commandId).orElseThrow(QueryService::notFound);
        boolean rejected = "REJECTED".equals(c.result());
        return new WorkflowDtos.CommandOutcomeView(c.commandId(), c.correlationId(), c.outcome(), c.recordedAt(), false,
                c.operation(), c.result(), c.httpStatus(), rejected ? c.errorCode() : null, c.resourceId());
    }

    public AdminDtos.AdminCommandOutcome adminCommand(String namespace, String commandId) {
        CommandStore.StoredCommand c = commands.find(namespace, commandId).orElseThrow(QueryService::notFound);
        boolean rejected = "REJECTED".equals(c.result());
        return new AdminDtos.AdminCommandOutcome(c.commandId(), c.correlationId(), c.operation(), c.result(),
                c.outcome(), c.httpStatus(), rejected ? c.errorCode() : null, c.resourceId(), c.recordedAt());
    }

    public WorkflowDtos.ReservationView reservation(String orderId) {
        ReservationStore.Root root = reservations.find(orderId).orElseThrow(QueryService::notFound);
        List<WorkflowDtos.ReservationLineView> lines = reservations.lines(orderId).stream()
                .map(l -> new WorkflowDtos.ReservationLineView(l.orderLineId(), l.skuId(), l.quantity(),
                        l.warehouseId(), l.state().name(), l.handoverReference(), l.handedOverAt()))
                .toList();
        return new WorkflowDtos.ReservationView(root.orderId(), root.allocationPlanId(), root.state().name(),
                root.acceptedAt(), root.expiresAt(), root.releaseReason(), root.revision(), lines);
    }

    public WorkflowDtos.RefundScopeView refundScope(String refundScopeId) {
        RefundStore.Scope scope = refunds.find(refundScopeId).orElseThrow(QueryService::notFound);
        return new WorkflowDtos.RefundScopeView(scope.refundScopeId(), scope.orderId(), scope.refundRequestId(),
                scope.state().name(), scope.revision(), refunds.lines(refundScopeId).stream()
                .map(l -> new WorkflowDtos.RefundScopeLineView(l.orderLineId(), l.skuId(), l.expected(), l.received(),
                        l.passed(), l.failed()))
                .toList());
    }

    // ── ADMIN operational queries ─────────────────────────────────────────────

    public AdminDtos.WarehouseView warehouse(String warehouseId) {
        return warehouses.find(warehouseId).map(WarehouseService::view).orElseThrow(QueryService::notFound);
    }

    public AdminDtos.Page<AdminDtos.WarehouseView> warehouses(Boolean active, String cursor, int limit) {
        String after = cursor == null ? null : Cursors.decode(cursor)[0];
        List<WarehouseStore.Warehouse> rows = warehouses.list(active, after, limit + 1);
        return page(rows, limit, WarehouseService::view, w -> Cursors.encode(w.warehouseId()));
    }

    public AdminDtos.StockPositionView position(String warehouseId, String skuId) {
        return stock.findPosition(warehouseId, skuId).map(QueryService::view).orElseThrow(QueryService::notFound);
    }

    public AdminDtos.Page<AdminDtos.StockPositionView> positions(String warehouseId, String skuId, String cursor,
                                                                 int limit) {
        StockStore.PositionKey after = null;
        if (cursor != null) {
            String[] parts = Cursors.decode(cursor, 2);
            after = new StockStore.PositionKey(parts[0], parts[1]);
        }
        List<StockStore.Position> rows = stock.listPositions(warehouseId, skuId, after, limit + 1);
        return page(rows, limit, QueryService::view, p -> Cursors.encode(p.key().warehouseId(), p.key().skuId()));
    }

    public AdminDtos.StockMovementView movement(String movementId) {
        return stock.findMovement(movementId).map(QueryService::view).orElseThrow(QueryService::notFound);
    }

    public AdminDtos.Page<AdminDtos.StockMovementView> movements(String warehouseId, String skuId, Instant from,
                                                                 Instant to, String cursor, int limit) {
        Instant afterAt = null;
        String afterId = null;
        if (cursor != null) {
            String[] parts = Cursors.decode(cursor, 2);
            afterAt = Cursors.instant(parts[0]);
            afterId = parts[1];
        }
        List<StockStore.Movement> rows = stock.listMovements(warehouseId, skuId, from, to, afterAt, afterId, limit + 1);
        return page(rows, limit, QueryService::view,
                m -> Cursors.encode(m.recordedAt().toString(), m.movementId()));
    }

    public AdminDtos.Page<AdminDtos.DispatchQueueItem> dispatchQueue(String warehouseId, String cursor, int limit) {
        Instant afterAt = null;
        String afterOrder = null;
        String afterLine = null;
        if (cursor != null) {
            String[] parts = Cursors.decode(cursor, 3);
            afterAt = Cursors.instant(parts[0]);
            afterOrder = parts[1];
            afterLine = parts[2];
        }
        List<ReservationStore.Line> rows = reservations.dispatchQueue(warehouseId, afterAt, afterOrder, afterLine,
                limit + 1);
        return page(rows, limit, l -> new AdminDtos.DispatchQueueItem(l.orderId(), l.orderLineId(), l.skuId(),
                        l.quantity(), l.warehouseId(), l.committedAt()),
                l -> Cursors.encode(l.committedAt().toString(), l.orderId(), l.orderLineId()));
    }

    public AdminDtos.Page<AdminDtos.InspectionQueueItem> inspectionQueue(String warehouseId, String cursor, int limit) {
        Instant afterAt = null;
        String afterReceipt = null;
        String afterLine = null;
        if (cursor != null) {
            String[] parts = Cursors.decode(cursor, 3);
            afterAt = Cursors.instant(parts[0]);
            afterReceipt = parts[1];
            afterLine = parts[2];
        }
        List<RefundStore.InspectionQueueItem> rows = refunds.inspectionQueue(warehouseId, afterAt, afterReceipt,
                afterLine, limit + 1);
        return page(rows, limit, r -> new AdminDtos.InspectionQueueItem(r.refundScopeId(), r.orderId(),
                        r.orderLineId(), r.returnReceiptId(), r.returnReceiptLineId(), r.skuId(), r.warehouseId(),
                        r.receivedQuantity(), r.uninspectedQuantity(), r.receivedAt()),
                r -> Cursors.encode(r.receivedAt().toString(), r.returnReceiptId(), r.returnReceiptLineId()));
    }

    public AdminDtos.Page<AdminDtos.QuarantineItem> quarantineItems(String warehouseId, String cursor, int limit) {
        Instant afterAt = null;
        String afterId = null;
        if (cursor != null) {
            String[] parts = Cursors.decode(cursor, 2);
            afterAt = Cursors.instant(parts[0]);
            afterId = parts[1];
        }
        List<RefundStore.Inspection> rows = refunds.quarantineItems(warehouseId, afterAt, afterId, limit + 1);
        return page(rows, limit, i -> new AdminDtos.QuarantineItem(i.inspectionId(), i.warehouseId(), i.skuId(),
                        i.failReason(), i.quantity(), i.disposable(), i.latestHoldReason(), i.inspectedAt()),
                i -> Cursors.encode(i.inspectedAt().toString(), i.inspectionId()));
    }

    public AdminDtos.AdminReservation adminReservation(String orderId) {
        ReservationStore.Root root = reservations.find(orderId).orElseThrow(QueryService::notFound);
        return new AdminDtos.AdminReservation(root.orderId(), root.state().name(), root.revision(),
                reservations.lines(orderId).stream().map(l -> new AdminDtos.AdminReservationLine(l.orderLineId(),
                        l.skuId(), l.quantity(), l.warehouseId(), l.state().name())).toList());
    }

    public AdminDtos.AdminRefundScope adminRefundScope(String refundScopeId) {
        return adminScope(refunds.find(refundScopeId).orElseThrow(QueryService::notFound));
    }

    public AdminDtos.AdminRefundScope adminRefundScopeByOrder(String orderId) {
        return adminScope(refunds.findByOrder(orderId).orElseThrow(QueryService::notFound));
    }

    private AdminDtos.AdminRefundScope adminScope(RefundStore.Scope scope) {
        return new AdminDtos.AdminRefundScope(scope.refundScopeId(), scope.refundRequestId(), scope.orderId(),
                scope.state().name(), scope.revision(), refunds.lines(scope.refundScopeId()).stream()
                .map(l -> new AdminDtos.AdminRefundScopeLine(l.orderLineId(), l.skuId(), l.expected(), l.received(),
                        l.passed(), l.failed()))
                .toList());
    }

    private static AdminDtos.StockPositionView view(StockStore.Position p) {
        return new AdminDtos.StockPositionView(p.key().warehouseId(), p.key().skuId(), p.buckets().onHand(),
                p.buckets().availableToSell(), p.buckets().reserved(), p.buckets().committed(),
                p.buckets().quarantine(), p.revision());
    }

    private static AdminDtos.StockMovementView view(StockStore.Movement m) {
        return new AdminDtos.StockMovementView(m.movementId(), m.warehouseId(), m.skuId(), m.sourceOperationType(),
                m.sourceOperationId(), m.reversesMovementId(), new AdminDtos.Deltas(m.delta().onHand(),
                m.delta().availableToSell(), m.delta().reserved(), m.delta().committed(), m.delta().quarantine()),
                m.recordedAt());
    }

    private static <R, T> AdminDtos.Page<T> page(List<R> rows, int limit, Function<R, T> mapper,
                                                 Function<R, String> cursor) {
        boolean more = rows.size() > limit;
        List<R> visible = more ? rows.subList(0, limit) : rows;
        String next = more ? cursor.apply(visible.get(visible.size() - 1)) : null;
        return new AdminDtos.Page<>(visible.stream().map(mapper).toList(), next);
    }

    private static ApiException notFound() {
        return new ApiException(ErrorCode.NOT_FOUND, "The resource does not exist or is not visible to this caller");
    }

    /** Opaque keyset cursors: base64url of NUL-separated key parts. */
    static final class Cursors {

        private Cursors() {
        }

        static String encode(String... parts) {
            return Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(String.join("\u0000", parts).getBytes(StandardCharsets.UTF_8));
        }

        static String[] decode(String cursor) {
            return decode(cursor, 1);
        }

        static String[] decode(String cursor, int parts) {
            try {
                String[] decoded = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8)
                        .split("\u0000", -1);
                if (decoded.length != parts) {
                    throw new IllegalArgumentException();
                }
                return decoded;
            } catch (IllegalArgumentException e) {
                throw ApiException.invalid("cursor", "is not a cursor issued by this endpoint");
            }
        }

        static Instant instant(String value) {
            try {
                return Instant.parse(value);
            } catch (RuntimeException e) {
                throw ApiException.invalid("cursor", "is not a cursor issued by this endpoint");
            }
        }
    }
}
