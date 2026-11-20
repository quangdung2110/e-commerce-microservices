package com.ecommerce.inventory.service;

import com.ecommerce.inventory.command.Accepted;
import com.ecommerce.inventory.command.CommandContext;
import com.ecommerce.inventory.common.DomainRejection;
import com.ecommerce.inventory.common.ErrorCode;
import com.ecommerce.inventory.config.InventoryProperties;
import com.ecommerce.inventory.domain.BucketDelta;
import com.ecommerce.inventory.domain.Buckets;
import com.ecommerce.inventory.domain.ReservationStates;
import com.ecommerce.inventory.domain.ReservationStates.LineState;
import com.ecommerce.inventory.domain.ReservationStates.ReleaseReason;
import com.ecommerce.inventory.domain.ReservationStates.RootState;
import com.ecommerce.inventory.store.EvidenceStore;
import com.ecommerce.inventory.store.ReservationStore;
import com.ecommerce.inventory.store.Savepoints;
import com.ecommerce.inventory.store.StockStore;
import com.ecommerce.inventory.store.StockStore.PositionKey;
import com.ecommerce.inventory.store.WarehouseStore;
import com.ecommerce.inventory.web.dto.WorkflowDtos;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Order Temporal Saga reservation commands (interfaces.md §2, ADR-019/020/021). Every method
 * runs inside the command executor's transaction and either returns the durable outcome or
 * throws a {@link DomainRejection}. No method publishes a reservation-result event.
 */
@Service
public class ReservationService {

    private static final String TARGET = "ORDER_RESERVATION";
    private static final String RESERVE_SAVEPOINT = "reserve_stock";

    private final ReservationStore reservations;
    private final WarehouseStore warehouses;
    private final StockStore stock;
    private final EvidenceStore evidence;
    private final Savepoints savepoints;
    private final InventoryProperties properties;

    public ReservationService(ReservationStore reservations, WarehouseStore warehouses, StockStore stock,
                              EvidenceStore evidence, Savepoints savepoints,
                              InventoryProperties properties) {
        this.reservations = reservations;
        this.warehouses = warehouses;
        this.stock = stock;
        this.evidence = evidence;
        this.savepoints = savepoints;
        this.properties = properties;
    }

    /**
     * Exact-plan, all-or-nothing Reserve. {@code allocation_plan_id} is stored, never looked
     * up; a stale line rejects the whole order and no alternate warehouse is tried.
     *
     * <p>The stock rows are locked last (ADR-033): warehouse eligibility and position existence
     * are checked first, the root, lines, movements and audit are written, the executor records
     * the {@code RESERVED} result, and only then does each position take its guarded update (the
     * last one also marks the SKUs dirty). When a guard fails, every write since the savepoint,
     * the recorded result included, is undone and the shortfall is evaluated under the position
     * locks as before, so {@code REJECT_ALL} names exactly the lines that cannot be allocated.
     */
    public Accepted reserve(WorkflowDtos.ReserveOrderRequest request, CommandContext context) {
        String orderId = request.orderId();
        reservations.lockOrderKey(orderId);
        if (reservations.find(orderId).isPresent()) {
            throw new DomainRejection(ErrorCode.RESERVATION_ALREADY_EXISTS,
                    "A reservation already exists for this order under another command");
        }

        Map<String, Optional<WarehouseStore.Warehouse>> lockedWarehouses = new LinkedHashMap<>();
        for (String warehouseId : new TreeSet<>(request.lines().stream()
                .map(WorkflowDtos.ReservationLineInput::warehouseId).toList())) {
            lockedWarehouses.put(warehouseId, warehouses.lockForShare(warehouseId));
        }
        Map<PositionKey, Long> demand = new TreeMap<>();
        request.lines().forEach(line -> demand.merge(new PositionKey(line.warehouseId(), line.skuId()),
                line.quantity(), Long::sum));

        Set<PositionKey> existing = stock.existing(demand.keySet());
        Set<PositionKey> ineligible = new LinkedHashSet<>();
        demand.keySet().forEach(key -> {
            boolean allocatable = lockedWarehouses.get(key.warehouseId())
                    .map(WarehouseStore.Warehouse::allocatable).orElse(false);
            if (!allocatable || !existing.contains(key)) {
                ineligible.add(key);
            }
        });
        if (!ineligible.isEmpty()) {
            // Reject without writing; the shortfall of the other lines is reported too, as before.
            return rejectAll(request, unavailableUnderLock(demand, ineligible), context);
        }

        List<String> dirty = new ArrayList<>(new TreeSet<>(demand.keySet().stream().map(PositionKey::skuId).toList()));
        savepoints.set(RESERVE_SAVEPOINT);
        ReservationStore.Root root = writeReservation(request, demand, context);
        return reserved(request, root, context).thenLast(() -> {
            if (applyLast(demand, BucketDelta::reserve, dirty, context)) {
                return null;
            }
            savepoints.rollbackTo(RESERVE_SAVEPOINT);
            Set<PositionKey> unavailable = unavailableUnderLock(demand, ineligible);
            if (!unavailable.isEmpty()) {
                return rejectAll(request, unavailable, context);
            }
            // Stock was freed concurrently; the positions are now locked and sufficient.
            ReservationStore.Root again = writeReservation(request, demand, context);
            if (!applyLast(demand, BucketDelta::reserve, dirty, context)) {
                throw new IllegalStateException("guarded reserve failed under the position locks");
            }
            return reserved(request, again, context);
        });
    }

    private static Accepted reserved(WorkflowDtos.ReserveOrderRequest request, ReservationStore.Root root,
                                     CommandContext context) {
        return new Accepted(new WorkflowDtos.ReservedResponse(context.commandId(), context.correlationId(),
                "RESERVED", context.now(), false, request.orderId(), root.expiresAt(), root.revision(),
                request.lines()), "RESERVED", request.orderId());
    }

    /**
     * The pre-ADR-033 evaluation for the rejection path: locks every demanded position in key
     * order and returns the ineligible keys plus each position that is absent or short.
     */
    private Set<PositionKey> unavailableUnderLock(Map<PositionKey, Long> demand, Set<PositionKey> ineligible) {
        Map<PositionKey, StockStore.Position> positions = stock.lockExisting(demand.keySet());
        Set<PositionKey> unavailable = new LinkedHashSet<>();
        demand.forEach((key, quantity) -> {
            StockStore.Position position = positions.get(key);
            if (ineligible.contains(key) || position == null || position.buckets().availableToSell() < quantity) {
                unavailable.add(key);
            }
        });
        return unavailable;
    }

    /** Root, lines, movements and audit of a Reserve; touches no stock position row. */
    private ReservationStore.Root writeReservation(WorkflowDtos.ReserveOrderRequest request,
                                                   Map<PositionKey, Long> demand, CommandContext context) {
        String orderId = request.orderId();
        ReservationStore.Root root = new ReservationStore.Root(orderId, request.allocationPlanId(),
                RootState.RESERVED, context.now(), context.now().plus(properties.reservation().paymentWindow()),
                null, null, null, null, 1, context.correlationId());
        try {
            reservations.insertRoot(root, context.now());
        } catch (DuplicateKeyException e) {
            throw new DomainRejection(ErrorCode.RESERVATION_ALREADY_EXISTS,
                    "A reservation already exists for this order under another command");
        }
        int lineNo = 0;
        for (WorkflowDtos.ReservationLineInput line : request.lines()) {
            reservations.insertLine(new ReservationStore.Line(orderId, line.orderLineId(), ++lineNo, line.skuId(),
                    line.quantity(), line.warehouseId(), LineState.RESERVED, null, null, null));
        }
        demand.forEach((key, quantity) -> stock.appendMovement(key, BucketDelta.reserve(quantity),
                StockStore.MovementSpec.of("RESERVE", orderId), context));
        evidence.audit(context, TARGET, orderId, null, null, null,
                Map.of("state", RootState.RESERVED.name(), "allocation_plan_id", request.allocationPlanId(),
                        "lines", request.lines()));
        return root;
    }

    private static Accepted rejectAll(WorkflowDtos.ReserveOrderRequest request, Set<PositionKey> unavailable,
                                      CommandContext context) {
        List<String> rejected = request.lines().stream()
                .filter(line -> unavailable.contains(new PositionKey(line.warehouseId(), line.skuId())))
                .map(WorkflowDtos.ReservationLineInput::orderLineId)
                .toList();
        return new Accepted(new WorkflowDtos.RejectAllReserveResponse(context.commandId(),
                context.correlationId(), "REJECT_ALL", context.now(), false, request.orderId(), rejected),
                "REJECT_ALL", request.orderId());
    }

    /** Whole-root {@code RESERVED -> COMMITTED}; never gated by {@code expires_at} (ADR-021). */
    public Accepted commit(String orderId, WorkflowDtos.CommitPaymentRequest request, CommandContext context) {
        ReservationStore.Root root = reservations.lock(orderId).orElseThrow(() -> new DomainRejection(
                ErrorCode.RESERVATION_NOT_RESERVED, "No reservation exists for this order"));
        if (root.state() != RootState.RESERVED) {
            throw new DomainRejection(ErrorCode.RESERVATION_NOT_RESERVED,
                    "The reservation is " + root.state() + ", not RESERVED");
        }
        List<ReservationStore.Line> lines = reservations.lockLines(orderId);
        lines.forEach(line -> reservations.updateLine(orderId, line.orderLineId(), LineState.COMMITTED, context.now()));
        ReservationStore.Root next = withState(root, RootState.COMMITTED, root.releaseReason(),
                root.committedReleaseReason(), request.paymentReference(), root.recoveryPaymentReference());
        reservations.updateRoot(next, context.now());
        evidence.audit(context, TARGET, orderId, null, null, Map.of("state", root.state().name()),
                Map.of("state", next.state().name(), "payment_reference", request.paymentReference()));
        appendMovements(lines, BucketDelta::commitPayment, "COMMIT_PAYMENT", orderId, context);
        // Commit leaves available_to_sell unchanged, so it marks nothing dirty.
        return reservationResult("COMMITTED", next, context)
                .thenLast(covered(lines, BucketDelta::commitPayment, orderId, List.of(), context));
    }

    /** Whole-root release of a {@code RESERVED} root with one immutable reason. */
    public Accepted release(String orderId, WorkflowDtos.ReleaseReservationRequest request, CommandContext context) {
        ReservationStore.Root root = reservations.lock(orderId).orElseThrow(() -> new DomainRejection(
                ErrorCode.RESERVATION_NOT_RESERVED, "No reservation exists for this order"));
        if (root.state() != RootState.RESERVED) {
            throw new DomainRejection(ErrorCode.RESERVATION_NOT_RESERVED,
                    "The reservation is " + root.state() + ", not RESERVED");
        }
        List<ReservationStore.Line> lines = reservations.lockLines(orderId);
        lines.forEach(line -> reservations.updateLine(orderId, line.orderLineId(), LineState.RELEASED,
                line.committedAt()));
        ReservationStore.Root next = withState(root, RootState.RELEASED, request.reason().name(),
                root.committedReleaseReason(), root.paymentReference(), root.recoveryPaymentReference());
        reservations.updateRoot(next, context.now());
        evidence.audit(context, TARGET, orderId, request.reason().name(), null, Map.of("state", root.state().name()),
                Map.of("state", next.state().name(), "release_reason", request.reason().name()));
        appendMovements(lines, BucketDelta::releaseReservation, "RELEASE_RESERVATION", orderId, context);
        return reservationResult("RELEASED", next, context)
                .thenLast(covered(lines, BucketDelta::releaseReservation, orderId, skus(lines), context));
    }

    /**
     * Fenced late-payment recovery (ADR-019): only the same root released for
     * {@code RESERVATION_EXPIRED}, with no dispatch and the full original plan available at
     * the original warehouses, moves {@code RELEASED -> COMMITTED} as a whole.
     */
    public Accepted recoverTimedOutPayment(String orderId, WorkflowDtos.RecoverTimedOutPaymentRequest request,
                                           CommandContext context) {
        ReservationStore.Root root = reservations.lock(orderId).orElseThrow(() -> new DomainRejection(
                ErrorCode.RESERVATION_NOT_RECOVERABLE, "No reservation exists for this order"));
        if (root.state() != RootState.RELEASED
                || !ReleaseReason.RESERVATION_EXPIRED.name().equals(root.releaseReason())
                || root.committedReleaseReason() != null) {
            throw new DomainRejection(ErrorCode.RESERVATION_NOT_RECOVERABLE,
                    "Only a root released for RESERVATION_EXPIRED can be recovered");
        }
        List<ReservationStore.Line> lines = reservations.lockLines(orderId);
        if (reservations.anyDispatch(orderId)) {
            throw new DomainRejection(ErrorCode.RESERVATION_NOT_RECOVERABLE, "A line of this order was dispatched");
        }
        lines.forEach(line -> reservations.updateLine(orderId, line.orderLineId(), LineState.COMMITTED, context.now()));
        ReservationStore.Root next = withState(root, RootState.COMMITTED, root.releaseReason(), null,
                root.paymentReference(), request.paymentReference());
        reservations.updateRoot(next, context.now());
        evidence.audit(context, TARGET, orderId, ReleaseReason.RESERVATION_EXPIRED.name(), null,
                Map.of("state", root.state().name()),
                Map.of("state", next.state().name(), "payment_reference", request.paymentReference()));
        appendMovements(lines, BucketDelta::recoverToCommitted, "RECOVER_TIMED_OUT_PAYMENT", orderId, context);
        Map<PositionKey, Long> demand = demand(lines);
        List<String> dirty = skus(lines);
        return reservationResult("COMMITTED", next, context).thenLast(() -> {
            // A failed guard is the durable rejection; the executor undoes every write of this
            // command, the recorded result included.
            if (!applyLast(demand, BucketDelta::recoverToCommitted, dirty, context)) {
                throw new DomainRejection(ErrorCode.RESERVATION_NOT_RECOVERABLE,
                        "The original allocation is no longer fully available at its original warehouses");
            }
            return null;
        });
    }

    /**
     * Atomic {@code ReleaseCommittedOrderBeforeDispatch} (ADR-018/021): all lines committed
     * and none dispatched, or no movement at all.
     */
    public Accepted releaseCommittedBeforeDispatch(String orderId, CommandContext context) {
        ReservationStore.Root root = reservations.lock(orderId).orElseThrow(() -> new DomainRejection(
                ErrorCode.PRE_DISPATCH_RELEASE_NOT_ALLOWED, "No reservation exists for this order"));
        List<ReservationStore.Line> lines = reservations.lockLines(orderId);
        if (root.state() != RootState.COMMITTED
                || lines.stream().anyMatch(line -> line.state() != LineState.COMMITTED)
                || reservations.anyDispatch(orderId)) {
            throw new DomainRejection(ErrorCode.PRE_DISPATCH_RELEASE_NOT_ALLOWED,
                    "Every line must be COMMITTED and undispatched; the reservation is " + root.state());
        }
        lines.forEach(line -> reservations.updateLine(orderId, line.orderLineId(), LineState.RELEASED,
                line.committedAt()));
        ReservationStore.Root next = withState(root, RootState.RELEASED, root.releaseReason(),
                ReservationStates.COMMITTED_RELEASE_REASON, root.paymentReference(), root.recoveryPaymentReference());
        reservations.updateRoot(next, context.now());
        evidence.audit(context, TARGET, orderId, ReservationStates.COMMITTED_RELEASE_REASON, null,
                Map.of("state", root.state().name()), Map.of("state", next.state().name()));
        appendMovements(lines, BucketDelta::releaseCommitted, "RELEASE_COMMITTED_BEFORE_DISPATCH", orderId, context);
        List<String> released = lines.stream().map(ReservationStore.Line::orderLineId).toList();
        return new Accepted(new WorkflowDtos.ReleasedCommittedOrderResponse(context.commandId(),
                context.correlationId(), "RELEASED", context.now(), false, orderId, next.revision(), released),
                "RELEASED", orderId)
                .thenLast(covered(lines, BucketDelta::releaseCommitted, orderId, skus(lines), context));
    }

    /** The movements of a transition; they touch no position row, so they belong in the body. */
    private void appendMovements(List<ReservationStore.Line> lines, Function<Long, BucketDelta> transition,
                                 String type, String orderId, CommandContext context) {
        demand(lines).forEach((key, quantity) -> stock.appendMovement(key, transition.apply(quantity),
                StockStore.MovementSpec.of(type, orderId), context));
    }

    /**
     * Last step for a transition the reservation's own buckets already cover (Commit, Release,
     * pre-dispatch release). A failed guard means a position is missing or its buckets no longer
     * hold this order's quantity: an invariant breach, never a domain outcome.
     */
    private Supplier<Accepted> covered(List<ReservationStore.Line> lines, Function<Long, BucketDelta> transition,
                                       String orderId, List<String> dirty, CommandContext context) {
        Map<PositionKey, Long> demand = demand(lines);
        return () -> {
            if (!applyLast(demand, transition, dirty, context)) {
                throw new Buckets.InvariantViolation("reservation " + orderId + " is not covered by its stock positions");
            }
            return null;
        };
    }

    /**
     * Guarded updates in {@link PositionKey} order, the last statements of the transaction
     * (ADR-033); the last position's statement also marks {@code dirty} (sorted SKU IDs), so
     * dirty rows are locked after every position row. Stops at the first position that cannot
     * take its delta and marks nothing; the caller then rolls back (savepoint, rejection or
     * exception), so no partial stock change survives.
     */
    private boolean applyLast(Map<PositionKey, Long> demand, Function<Long, BucketDelta> transition,
                              List<String> dirty, CommandContext context) {
        List<Map.Entry<PositionKey, Long>> entries = new ArrayList<>(demand.entrySet());
        for (int i = 0; i < entries.size(); i++) {
            PositionKey key = entries.get(i).getKey();
            BucketDelta delta = transition.apply(entries.get(i).getValue());
            boolean last = i == entries.size() - 1;
            boolean applied = last && !dirty.isEmpty()
                    ? stock.applyGuardedMarkingDirty(key, delta, dirty, context)
                    : stock.applyGuarded(key, delta, context.now());
            if (!applied) {
                return false;
            }
        }
        return true;
    }

    private static Map<PositionKey, Long> demand(List<ReservationStore.Line> lines) {
        Map<PositionKey, Long> demand = new TreeMap<>();
        lines.forEach(line -> demand.merge(new PositionKey(line.warehouseId(), line.skuId()), line.quantity(),
                Long::sum));
        return demand;
    }

    private static List<String> skus(List<ReservationStore.Line> lines) {
        return new ArrayList<>(new TreeSet<>(lines.stream().map(ReservationStore.Line::skuId).toList()));
    }

    private static ReservationStore.Root withState(ReservationStore.Root root, RootState state, String releaseReason,
                                                   String committedReleaseReason, String paymentReference,
                                                   String recoveryPaymentReference) {
        return new ReservationStore.Root(root.orderId(), root.allocationPlanId(), state, root.acceptedAt(),
                root.expiresAt(), releaseReason, committedReleaseReason, paymentReference, recoveryPaymentReference,
                root.revision() + 1, root.correlationId());
    }

    private static Accepted reservationResult(String outcome, ReservationStore.Root root, CommandContext context) {
        return new Accepted(new WorkflowDtos.ReservationCommandResponse(context.commandId(), context.correlationId(),
                outcome, context.now(), false, root.orderId(), root.revision()), outcome, root.orderId());
    }
}
