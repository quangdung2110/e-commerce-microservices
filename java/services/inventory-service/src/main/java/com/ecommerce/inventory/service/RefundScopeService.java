package com.ecommerce.inventory.service;

import com.ecommerce.inventory.command.Accepted;
import com.ecommerce.inventory.command.CommandContext;
import com.ecommerce.inventory.common.DomainRejection;
import com.ecommerce.inventory.common.ErrorCode;
import com.ecommerce.inventory.common.Ids;
import com.ecommerce.inventory.domain.RefundScopeStates;
import com.ecommerce.inventory.domain.RefundScopeStates.State;
import com.ecommerce.inventory.domain.ReservationStates.LineState;
import com.ecommerce.inventory.store.EvidenceStore;
import com.ecommerce.inventory.store.RefundStore;
import com.ecommerce.inventory.store.ReservationStore;
import com.ecommerce.inventory.web.dto.WorkflowDtos;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Order-owned Refund Scope registration and finalization (domain.md, ADR-018/021, Order
 * ADR-013). Lock order: reservation root and lines, then scope and its lines.
 */
@Service
public class RefundScopeService {

    private static final String TARGET = "REFUND_SCOPE";

    private final ReservationStore reservations;
    private final RefundStore refunds;
    private final EvidenceStore evidence;

    public RefundScopeService(ReservationStore reservations, RefundStore refunds, EvidenceStore evidence) {
        this.reservations = reservations;
        this.refunds = refunds;
        this.evidence = evidence;
    }

    /**
     * Registers the immutable selected full-quantity scope, once per order lifetime. A new
     * command carrying the same registration returns the existing scope; any other
     * registration for the order is {@code REFUND_SCOPE_ALREADY_EXISTS}.
     */
    public Accepted register(WorkflowDtos.RegisterRefundScopeRequest request, CommandContext context) {
        Optional<ReservationStore.Root> root = reservations.lock(request.orderId());
        Optional<RefundStore.Scope> existing = refunds.findByOrder(request.orderId())
                .or(() -> refunds.findByRefundRequest(request.refundRequestId()));
        if (existing.isPresent()) {
            RefundStore.Scope scope = existing.get();
            if (sameRegistration(scope, request)) {
                // Replay of the same business identity under a new command ID: the receipt names
                // the scope as it is now, not the revision it had when first registered.
                return result("REGISTERED", scope, scope.revision(), context);
            }
            throw new DomainRejection(ErrorCode.REFUND_SCOPE_ALREADY_EXISTS,
                    "The order already has its lifetime refund scope");
        }
        if (root.isEmpty()) {
            throw new DomainRejection(ErrorCode.REFUND_SCOPE_LINE_INVALID, "No reservation exists for this order");
        }
        Map<String, ReservationStore.Line> lines = reservations.lockLines(request.orderId()).stream()
                .collect(Collectors.toMap(ReservationStore.Line::orderLineId, Function.identity()));
        Set<String> seen = new HashSet<>();
        for (WorkflowDtos.RefundScopeLineInput input : request.lines()) {
            ReservationStore.Line line = lines.get(input.orderLineId());
            if (!seen.add(input.orderLineId()) || line == null || !line.skuId().equals(input.skuId())
                    || line.quantity() != input.expectedQuantity()) {
                throw new DomainRejection(ErrorCode.REFUND_SCOPE_LINE_INVALID,
                        "Line " + input.orderLineId() + " is unknown, duplicated, has another SKU or a partial quantity");
            }
        }
        for (WorkflowDtos.RefundScopeLineInput input : request.lines()) {
            if (lines.get(input.orderLineId()).state() != LineState.DISPATCHED) {
                throw new DomainRejection(ErrorCode.REFUND_SCOPE_LINE_NOT_DISPATCHED,
                        "Line " + input.orderLineId() + " has not physically left the warehouse");
            }
        }

        RefundStore.Scope scope = new RefundStore.Scope(Ids.newId(), request.orderId(), request.refundRequestId(),
                State.REGISTERED, null, 1, context.now());
        try {
            refunds.insertScope(scope, context.now());
        } catch (DuplicateKeyException e) {
            throw new DomainRejection(ErrorCode.REFUND_SCOPE_ALREADY_EXISTS,
                    "The order already has its lifetime refund scope");
        }
        int lineNo = 0;
        for (WorkflowDtos.RefundScopeLineInput input : request.lines()) {
            refunds.insertScopeLine(new RefundStore.ScopeLine(scope.refundScopeId(), input.orderLineId(), ++lineNo,
                    input.skuId(), input.expectedQuantity(), 0, 0, 0));
        }
        evidence.audit(context, TARGET, scope.refundScopeId(), null, null, null,
                Map.of("state", State.REGISTERED.name(), "order_id", request.orderId(),
                        "refund_request_id", request.refundRequestId(), "lines", request.lines()));
        return result("REGISTERED", scope, 1, context);
    }

    /** {@code REGISTERED -> CANCELLED}, only with no receipt. */
    public Accepted cancel(String refundScopeId, CommandContext context) {
        RefundStore.Scope scope = lockScope(refundScopeId);
        List<RefundStore.ScopeLine> lines = refunds.lockLines(refundScopeId);
        if (scope.state() != State.REGISTERED || lines.stream().anyMatch(line -> line.received() > 0)) {
            throw new DomainRejection(ErrorCode.REFUND_SCOPE_STATE_CONFLICT,
                    "Only a REGISTERED scope with no receipt can be cancelled; it is " + scope.state());
        }
        return finish(scope, State.CANCELLED, null, context);
    }

    /** {@code INSPECTION_COMPLETED -> CLOSED} after Order's terminal refund outcome. */
    public Accepted close(String refundScopeId, WorkflowDtos.CloseRefundScopeRequest request, CommandContext context) {
        RefundStore.Scope scope = lockScope(refundScopeId);
        refunds.lockLines(refundScopeId);
        if (scope.state() != State.INSPECTION_COMPLETED) {
            throw new DomainRejection(ErrorCode.REFUND_SCOPE_STATE_CONFLICT,
                    "Only an INSPECTION_COMPLETED scope can be closed; it is " + scope.state());
        }
        return finish(scope, State.CLOSED, request.reason().name(), context);
    }

    /**
     * {@code IN_PROGRESS -> CLOSED_INCOMPLETE}: every received unit final-inspected and some
     * expected quantity missing. Emits no completion fact.
     */
    public Accepted closeIncomplete(String refundScopeId, WorkflowDtos.CloseIncompleteRefundScopeRequest request,
                                    CommandContext context) {
        RefundStore.Scope scope = lockScope(refundScopeId);
        List<RefundStore.ScopeLine> lines = refunds.lockLines(refundScopeId);
        boolean closable = RefundScopeStates.incompleteClosable(lines.stream().map(RefundScopeService::counters)
                .toList());
        if (scope.state() != State.IN_PROGRESS || !closable) {
            throw new DomainRejection(ErrorCode.REFUND_SCOPE_STATE_CONFLICT,
                    "close-incomplete needs IN_PROGRESS, every received unit final-inspected and missing units");
        }
        return finish(scope, State.CLOSED_INCOMPLETE, request.reason().name(), context);
    }

    static RefundScopeStates.LineCounters counters(RefundStore.ScopeLine line) {
        return new RefundScopeStates.LineCounters(line.expected(), line.received(), line.passed(), line.failed());
    }

    private RefundStore.Scope lockScope(String refundScopeId) {
        return refunds.lock(refundScopeId).orElseThrow(() -> new DomainRejection(
                ErrorCode.REFUND_SCOPE_STATE_CONFLICT, "No refund scope exists with this identifier"));
    }

    private Accepted finish(RefundStore.Scope scope, State state, String reason, CommandContext context) {
        long revision = scope.revision() + 1;
        refunds.updateScope(scope.refundScopeId(), state, reason, revision, context.now());
        evidence.audit(context, TARGET, scope.refundScopeId(), reason, null, Map.of("state", scope.state().name()),
                Map.of("state", state.name()));
        return result(state.name(), scope, revision, context);
    }

    private boolean sameRegistration(RefundStore.Scope scope, WorkflowDtos.RegisterRefundScopeRequest request) {
        if (!scope.orderId().equals(request.orderId()) || !scope.refundRequestId().equals(request.refundRequestId())) {
            return false;
        }
        Set<String> registered = refunds.lines(scope.refundScopeId()).stream()
                .map(line -> line.orderLineId() + "\u0000" + line.skuId() + "\u0000" + line.expected())
                .collect(Collectors.toSet());
        Set<String> requested = request.lines().stream()
                .map(line -> line.orderLineId() + "\u0000" + line.skuId() + "\u0000" + line.expectedQuantity())
                .collect(Collectors.toSet());
        return registered.equals(requested) && request.lines().size() == registered.size();
    }

    private static Accepted result(String outcome, RefundStore.Scope scope, long revision, CommandContext context) {
        return new Accepted(new WorkflowDtos.RefundScopeCommandResponse(context.commandId(), context.correlationId(),
                outcome, context.now(), false, scope.refundScopeId(), scope.orderId(), scope.refundRequestId(),
                revision), outcome, scope.refundScopeId());
    }
}
