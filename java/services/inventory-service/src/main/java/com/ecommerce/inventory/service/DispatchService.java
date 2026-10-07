package com.ecommerce.inventory.service;

import com.ecommerce.inventory.command.Accepted;
import com.ecommerce.inventory.command.CommandContext;
import com.ecommerce.inventory.common.DomainRejection;
import com.ecommerce.inventory.common.ErrorCode;
import com.ecommerce.inventory.domain.BucketDelta;
import com.ecommerce.inventory.domain.ReservationStates;
import com.ecommerce.inventory.domain.ReservationStates.LineState;
import com.ecommerce.inventory.store.EvidenceStore;
import com.ecommerce.inventory.store.ReservationStore;
import com.ecommerce.inventory.store.StockStore;
import com.ecommerce.inventory.store.StockStore.PositionKey;
import com.ecommerce.inventory.store.WarehouseStore;
import com.ecommerce.inventory.web.dto.AdminDtos;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * ADMIN physical dispatch: the only sale-flow transition that reduces {@code on_hand}. It
 * records an entire committed line at its allocated warehouse and SKU (ADR-024) and writes
 * the Order-facing {@code line-dispatched} fact atomically. {@code available_to_sell} is
 * unchanged, so no availability work is marked.
 */
@Service
public class DispatchService {

    static final String PHYSICAL_DISPATCH = "PHYSICAL_DISPATCH";

    private final WarehouseStore warehouses;
    private final ReservationStore reservations;
    private final StockStore stock;
    private final EvidenceStore evidence;
    private final EventFactory events;

    public DispatchService(WarehouseStore warehouses, ReservationStore reservations, StockStore stock,
                           EvidenceStore evidence, EventFactory events) {
        this.warehouses = warehouses;
        this.reservations = reservations;
        this.stock = stock;
        this.evidence = evidence;
        this.events = events;
    }

    public Accepted dispatch(AdminDtos.PhysicalDispatchRequest request, CommandContext context) {
        String key = EvidenceStore.operationKey("dispatch", request.warehouseId(), request.dispatchId(),
                request.dispatchLineId());
        evidence.requireUnclaimed(key);
        if (!warehouses.exists(request.warehouseId())) {
            throw new DomainRejection(ErrorCode.WAREHOUSE_NOT_FOUND, "No warehouse exists with this identifier");
        }
        ReservationStore.Root root = reservations.lock(request.orderId()).orElseThrow(() -> new DomainRejection(
                ErrorCode.DISPATCH_NOT_ALLOWED, "No reservation exists for this order"));
        List<ReservationStore.Line> lines = reservations.lockLines(request.orderId());
        ReservationStore.Line line = lines.stream().filter(l -> l.orderLineId().equals(request.orderLineId()))
                .findFirst().orElseThrow(() -> new DomainRejection(ErrorCode.DISPATCH_NOT_ALLOWED,
                        "No reservation line exists with this identifier"));
        if (line.state() != LineState.COMMITTED) {
            throw new DomainRejection(ErrorCode.DISPATCH_NOT_ALLOWED, "The line is " + line.state() + ", not COMMITTED");
        }
        if (!line.warehouseId().equals(request.warehouseId()) || !line.skuId().equals(request.skuId())
                || line.quantity() != request.quantity()) {
            throw new DomainRejection(ErrorCode.DISPATCH_NOT_ALLOWED,
                    "Dispatch must record the whole line at its allocated warehouse and SKU");
        }
        PositionKey positionKey = new PositionKey(line.warehouseId(), line.skuId());
        StockStore.Position position = stock.lock(positionKey).orElseThrow();
        String operationId = evidence.claim(key, PHYSICAL_DISPATCH, context);
        String movementId = stock.apply(position, BucketDelta.physicalDispatch(line.quantity()),
                StockStore.MovementSpec.of(PHYSICAL_DISPATCH, operationId), context);

        Instant handedOverAt = request.handedOverAt().toInstant();
        try {
            reservations.insertDispatch(new ReservationStore.Dispatch(request.warehouseId(), request.dispatchId(),
                    request.dispatchLineId(), request.orderId(), request.orderLineId(), request.skuId(),
                    request.quantity(), request.handoverReference(), handedOverAt, request.evidenceReference(),
                    movementId, context.actorId()), context.now());
        } catch (DuplicateKeyException e) {
            throw new DomainRejection(ErrorCode.OPERATION_IDENTITY_CONFLICT,
                    "This dispatch identity was already recorded");
        }
        reservations.updateLine(request.orderId(), request.orderLineId(), LineState.DISPATCHED, line.committedAt());
        List<LineState> states = lines.stream()
                .map(l -> l.orderLineId().equals(request.orderLineId()) ? LineState.DISPATCHED : l.state())
                .toList();
        ReservationStore.Root next = new ReservationStore.Root(root.orderId(), root.allocationPlanId(),
                ReservationStates.derive(states), root.acceptedAt(), root.expiresAt(), root.releaseReason(),
                root.committedReleaseReason(), root.paymentReference(), root.recoveryPaymentReference(),
                root.revision() + 1, root.correlationId());
        reservations.updateRoot(next, context.now());
        events.lineDispatched(request.orderId(), request.orderLineId(), request.skuId(), request.quantity(),
                request.warehouseId(), request.handoverReference(), handedOverAt, next.revision(),
                context.correlationId(), context.commandId(), context.now());

        Map<String, Object> after = new LinkedHashMap<>();
        after.put("line_state", LineState.DISPATCHED.name());
        after.put("root_state", next.state().name());
        after.put("movement_id", movementId);
        after.put("handover_reference", request.handoverReference());
        evidence.audit(context, "RESERVATION_LINE", request.orderId() + "|" + request.orderLineId(), null,
                request.evidenceReference(), Map.of("line_state", line.state().name()), after);
        return SellableStockService.mutation(context, operationId, movementId);
    }
}
