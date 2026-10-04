package com.ecommerce.inventory.web.dto;

import com.ecommerce.inventory.domain.RefundScopeStates;
import com.ecommerce.inventory.domain.ReservationStates;
import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;

/** Wire shapes of contracts/http/inventory/workflow/v1/openapi.yaml. */
public final class WorkflowDtos {

    private WorkflowDtos() {
    }

    // ── Allocation preview ────────────────────────────────────────────────────

    public record PreviewLine(
            @NotNull @Size(min = 1, max = 64) String lineReference,
            @NotNull @Size(min = 1, max = 64) String skuId,
            @NotNull @Min(1) Long quantity) {
    }

    public record AllocationPreviewRequest(@NotNull @Size(min = 1, max = 100) List<@NotNull @Valid PreviewLine> lines) {
    }

    public record PlannedLine(String lineReference, String skuId, long quantity, String warehouseId,
                              int fulfillmentPriority, String selectionReason) {
    }

    public record RejectedPreviewLine(String lineReference, String skuId, long quantity, String reason) {
    }

    public record AllocatablePreview(String outcome, String allocationPlanId, Instant generatedAt,
                                     List<PlannedLine> lines) {
    }

    public record RejectAllPreview(String outcome, Instant generatedAt, List<RejectedPreviewLine> rejectedLines) {
    }

    // ── Reservation commands ──────────────────────────────────────────────────

    public record ReservationLineInput(
            @NotNull @Size(min = 1, max = 128) String orderLineId,
            @NotNull @Size(min = 1, max = 128) String skuId,
            @NotNull @Min(1) Long quantity,
            @NotNull @Size(min = 1, max = 128) String warehouseId) {
    }

    public record ReserveOrderRequest(
            @NotNull @Size(min = 1, max = 128) String orderId,
            @NotNull @Size(min = 1, max = 128) String allocationPlanId,
            @NotNull @Size(min = 1, max = 100) List<@NotNull @Valid ReservationLineInput> lines) {
    }

    public record ReservedResponse(String commandId, String correlationId, String outcome, Instant recordedAt,
                                   boolean replayed, String orderId, Instant expiresAt, long reservationRevision,
                                   List<ReservationLineInput> lines) {
    }

    public record RejectAllReserveResponse(String commandId, String correlationId, String outcome, Instant recordedAt,
                                           boolean replayed, String orderId, List<String> rejectedLines) {
    }

    public record CommitPaymentRequest(@NotNull @Size(min = 1, max = 128) String paymentReference) {
    }

    public record ReleaseReservationRequest(@NotNull ReservationStates.ReleaseReason reason) {
    }

    public record RecoverTimedOutPaymentRequest(@NotNull @Size(min = 1, max = 128) String paymentReference) {
    }

    public record ReleaseCommittedOrderBeforeDispatchRequest(
            @NotNull @Pattern(regexp = "ADMIN_FORCE_REFUND_BEFORE_DISPATCH") String reason) {
    }

    public record ReservationCommandResponse(String commandId, String correlationId, String outcome,
                                             Instant recordedAt, boolean replayed, String orderId,
                                             long reservationRevision) {
    }

    public record ReleasedCommittedOrderResponse(String commandId, String correlationId, String outcome,
                                                 Instant recordedAt, boolean replayed, String orderId,
                                                 long reservationRevision, List<String> releasedOrderLineIds) {
    }

    // ── Refund Scope commands ─────────────────────────────────────────────────

    public record RefundScopeLineInput(
            @NotNull @Size(min = 1, max = 128) String orderLineId,
            @NotNull @Size(min = 1, max = 128) String skuId,
            @NotNull @Min(1) Long expectedQuantity) {
    }

    public record RegisterRefundScopeRequest(
            @NotNull @Size(min = 1, max = 128) String orderId,
            @NotNull @Size(min = 1, max = 128) String refundRequestId,
            @NotNull @Size(min = 1, max = 100) List<@NotNull @Valid RefundScopeLineInput> lines) {
    }

    public record CloseRefundScopeRequest(@NotNull RefundScopeStates.CloseReason reason) {
    }

    public record CloseIncompleteRefundScopeRequest(@NotNull RefundScopeStates.CloseIncompleteReason reason) {
    }

    public record RefundScopeCommandResponse(String commandId, String correlationId, String outcome,
                                             Instant recordedAt, boolean replayed, String refundScopeId,
                                             String orderId, String refundRequestId, long scopeRevision) {
    }

    // ── Queries ───────────────────────────────────────────────────────────────

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ReservationLineView(String orderLineId, String skuId, long quantity, String warehouseId,
                                      String state, String handoverReference, Instant handedOverAt) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ReservationView(String orderId, String allocationPlanId, String state, Instant acceptedAt,
                                  Instant expiresAt, String releaseReason, long revision,
                                  List<ReservationLineView> lines) {
    }

    public record RefundScopeLineView(String orderLineId, String skuId, long expectedQuantity, long receivedQuantity,
                                      long passedQuantity, long failedQuantity) {
    }

    public record RefundScopeView(String refundScopeId, String orderId, String refundRequestId, String state,
                                  long revision, List<RefundScopeLineView> lines) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record CommandOutcomeView(String commandId, String correlationId, String outcome, Instant recordedAt,
                                     Boolean replayed, String operation, String result, Integer httpStatus,
                                     String errorCode, String resourceId) {
    }
}
