package com.ecommerce.inventory.web;

import com.ecommerce.inventory.command.CommandExecutor;
import com.ecommerce.inventory.service.AllocationPreviewService;
import com.ecommerce.inventory.service.QueryService;
import com.ecommerce.inventory.service.RefundScopeService;
import com.ecommerce.inventory.service.ReservationService;
import com.ecommerce.inventory.web.dto.WorkflowDtos;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** contracts/http/inventory/workflow/v1: Checkout preview and Order Temporal Saga commands. */
@RestController
@RequestMapping("/internal/inventory/v1")
public class WorkflowController {

    private static final String KEY = "Idempotency-Key";

    private final CommandExecutor executor;
    private final AllocationPreviewService previews;
    private final ReservationService reservations;
    private final RefundScopeService refundScopes;
    private final QueryService queries;

    public WorkflowController(CommandExecutor executor, AllocationPreviewService previews,
                              ReservationService reservations, RefundScopeService refundScopes, QueryService queries) {
        this.executor = executor;
        this.previews = previews;
        this.reservations = reservations;
        this.refundScopes = refundScopes;
        this.queries = queries;
    }

    @PostMapping("/allocation-previews")
    public Object preview(@Valid @RequestBody WorkflowDtos.AllocationPreviewRequest request) {
        RequestRules.requireCorrelation();
        RequestRules.requireCausation();
        RequestRules.unique("lines[].line_reference", request.lines(), WorkflowDtos.PreviewLine::lineReference);
        return previews.preview(request);
    }

    @PostMapping("/reservations")
    public ResponseEntity<JsonNode> reserve(@RequestHeader(value = KEY, required = false) String key,
                                            @Valid @RequestBody WorkflowDtos.ReserveOrderRequest request) {
        RequestRules.unique("lines[].order_line_id", request.lines(), WorkflowDtos.ReservationLineInput::orderLineId);
        return executor.execute("RESERVE_ORDER", key, Map.of(), request,
                context -> reservations.reserve(request, context)).toEntity();
    }

    @PostMapping("/reservations/{order_id}/commit")
    public ResponseEntity<JsonNode> commit(@PathVariable("order_id") String orderId,
                                           @RequestHeader(value = KEY, required = false) String key,
                                           @Valid @RequestBody WorkflowDtos.CommitPaymentRequest request) {
        RequestRules.opaqueId("order_id", orderId);
        return executor.execute("COMMIT_PAYMENT", key, Map.of("order_id", orderId), request,
                context -> reservations.commit(orderId, request, context)).toEntity();
    }

    @PostMapping("/reservations/{order_id}/release")
    public ResponseEntity<JsonNode> release(@PathVariable("order_id") String orderId,
                                            @RequestHeader(value = KEY, required = false) String key,
                                            @Valid @RequestBody WorkflowDtos.ReleaseReservationRequest request) {
        RequestRules.opaqueId("order_id", orderId);
        return executor.execute("RELEASE_RESERVATION", key, Map.of("order_id", orderId), request,
                context -> reservations.release(orderId, request, context)).toEntity();
    }

    @PostMapping("/reservations/{order_id}/recover-timed-out-payment")
    public ResponseEntity<JsonNode> recover(@PathVariable("order_id") String orderId,
                                            @RequestHeader(value = KEY, required = false) String key,
                                            @Valid @RequestBody WorkflowDtos.RecoverTimedOutPaymentRequest request) {
        RequestRules.opaqueId("order_id", orderId);
        return executor.execute("RECOVER_TIMED_OUT_PAYMENT", key, Map.of("order_id", orderId), request,
                context -> reservations.recoverTimedOutPayment(orderId, request, context)).toEntity();
    }

    @PostMapping("/reservations/{order_id}/release-committed-before-dispatch")
    public ResponseEntity<JsonNode> releaseCommitted(
            @PathVariable("order_id") String orderId,
            @RequestHeader(value = KEY, required = false) String key,
            @Valid @RequestBody WorkflowDtos.ReleaseCommittedOrderBeforeDispatchRequest request) {
        RequestRules.opaqueId("order_id", orderId);
        return executor.execute("RELEASE_COMMITTED_BEFORE_DISPATCH", key, Map.of("order_id", orderId), request,
                context -> reservations.releaseCommittedBeforeDispatch(orderId, context)).toEntity();
    }

    @PostMapping("/refund-scopes")
    public ResponseEntity<JsonNode> registerScope(@RequestHeader(value = KEY, required = false) String key,
                                                  @Valid @RequestBody WorkflowDtos.RegisterRefundScopeRequest request) {
        return executor.execute("REGISTER_REFUND_SCOPE", key, Map.of(), request,
                context -> refundScopes.register(request, context)).toEntity();
    }

    @PostMapping("/refund-scopes/{refund_scope_id}/cancel")
    public ResponseEntity<JsonNode> cancelScope(@PathVariable("refund_scope_id") String refundScopeId,
                                                @RequestHeader(value = KEY, required = false) String key) {
        RequestRules.opaqueId("refund_scope_id", refundScopeId);
        return executor.execute("CANCEL_REFUND_SCOPE", key, Map.of("refund_scope_id", refundScopeId), null,
                context -> refundScopes.cancel(refundScopeId, context)).toEntity();
    }

    @PostMapping("/refund-scopes/{refund_scope_id}/close")
    public ResponseEntity<JsonNode> closeScope(@PathVariable("refund_scope_id") String refundScopeId,
                                               @RequestHeader(value = KEY, required = false) String key,
                                               @Valid @RequestBody WorkflowDtos.CloseRefundScopeRequest request) {
        RequestRules.opaqueId("refund_scope_id", refundScopeId);
        return executor.execute("CLOSE_REFUND_SCOPE", key, Map.of("refund_scope_id", refundScopeId), request,
                context -> refundScopes.close(refundScopeId, request, context)).toEntity();
    }

    @PostMapping("/refund-scopes/{refund_scope_id}/close-incomplete")
    public ResponseEntity<JsonNode> closeIncomplete(
            @PathVariable("refund_scope_id") String refundScopeId,
            @RequestHeader(value = KEY, required = false) String key,
            @Valid @RequestBody WorkflowDtos.CloseIncompleteRefundScopeRequest request) {
        RequestRules.opaqueId("refund_scope_id", refundScopeId);
        return executor.execute("CLOSE_INCOMPLETE_REFUND_SCOPE", key, Map.of("refund_scope_id", refundScopeId),
                request, context -> refundScopes.closeIncomplete(refundScopeId, request, context)).toEntity();
    }

    @GetMapping("/commands/{command_id}")
    public WorkflowDtos.CommandOutcomeView command(@PathVariable("command_id") String commandId) {
        RequestRules.opaqueId("command_id", commandId);
        return queries.workflowCommand(RequestContext.current().namespace(), commandId);
    }

    @GetMapping("/reservations/{order_id}")
    public WorkflowDtos.ReservationView reservation(@PathVariable("order_id") String orderId) {
        RequestRules.opaqueId("order_id", orderId);
        return queries.reservation(orderId);
    }

    @GetMapping("/refund-scopes/{refund_scope_id}")
    public WorkflowDtos.RefundScopeView refundScope(@PathVariable("refund_scope_id") String refundScopeId) {
        RequestRules.opaqueId("refund_scope_id", refundScopeId);
        return queries.refundScope(refundScopeId);
    }
}
