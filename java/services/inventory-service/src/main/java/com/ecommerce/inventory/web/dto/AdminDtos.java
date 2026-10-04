package com.ecommerce.inventory.web.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;

/** Wire shapes of contracts/http/inventory/admin/v1/openapi.yaml (ADR-024, ADR-027). */
public final class AdminDtos {

    private AdminDtos() {
    }

    public enum OperationalState { NORMAL, OUTAGE }

    public enum ReceiptReason { INITIAL_STOCK_LOAD, SUPPLIER_RECEIPT }

    public enum AdjustmentDirection { INCREASE, DECREASE }

    public enum AdjustmentReason { COUNT_GAIN, COUNT_LOSS, DAMAGE_REMOVAL, EXPIRY_REMOVAL, DATA_CORRECTION }

    public enum SellableReversalReason { DUPLICATE_ENTRY, WRONG_QUANTITY, WRONG_SKU, WRONG_WAREHOUSE, SOURCE_DOCUMENT_VOIDED }

    public enum InspectionResult { PASS, FAIL }

    public enum FailReason {
        DAMAGED, USED_OR_ALTERED, MISSING_COMPONENTS, SAFETY_OR_HYGIENE, COUNTERFEIT_OR_UNVERIFIABLE,
        NOT_RESELLABLE_BY_POLICY
    }

    public enum Disposition { HOLD, DISPOSE, RETURN_TO_SUPPLIER }

    public enum DispositionReversalReason { WRONG_RECORD, DISPOSAL_ABORTED, SUPPLIER_RETURNED }

    // ── Warehouses ────────────────────────────────────────────────────────────

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Address(
            @NotNull @Size(min = 1, max = 200) String line1,
            @Size(min = 1, max = 200) String line2,
            @NotNull @Size(min = 1, max = 120) String locality,
            @Size(min = 1, max = 120) String region,
            @Size(min = 1, max = 32) String postalCode,
            @NotNull @Pattern(regexp = "^[A-Z]{2}$") String countryCode) {
    }

    public record CreateWarehouseRequest(
            @NotNull @Size(min = 1, max = 120) String name,
            @NotNull @Valid Address address,
            @NotNull Boolean isActive,
            @NotNull OperationalState operationalState,
            @NotNull @Min(0) Integer fulfillmentPriority,
            @NotNull @Size(min = 1, max = 160) String reason,
            @NotNull @Size(min = 1, max = 512) String evidenceReference) {
    }

    public record UpdateWarehouseRequest(
            @NotNull @Min(1) Long expectedRevision,
            @Size(min = 1, max = 120) String name,
            @Valid Address address,
            Boolean isActive,
            OperationalState operationalState,
            @Min(0) Integer fulfillmentPriority,
            @NotNull @Size(min = 1, max = 160) String reason,
            @NotNull @Size(min = 1, max = 512) String evidenceReference) {

        public boolean changesSomething() {
            return name != null || address != null || isActive != null || operationalState != null
                    || fulfillmentPriority != null;
        }
    }

    public record WarehouseView(String warehouseId, String name, JsonNode address, boolean isActive,
                                String operationalState, int fulfillmentPriority, long revision) {
    }

    public record WarehouseMutationResponse(String commandId, String correlationId, String outcome,
                                            WarehouseView warehouse, Instant recordedAt, boolean replayed) {
    }

    // ── Sellable stock ────────────────────────────────────────────────────────

    public record StockReceiptRequest(
            @NotNull @Size(min = 1, max = 128) String warehouseId,
            @NotNull @Size(min = 1, max = 128) String skuId,
            @NotNull @Min(1) Long quantity,
            @NotNull @Size(min = 1, max = 128) String receiptId,
            @NotNull @Size(min = 1, max = 128) String receiptLineId,
            @NotNull ReceiptReason reason,
            @Size(min = 1, max = 128) String baselineBatchId,
            @Size(min = 1, max = 128) String goodsReceiptReference,
            @NotNull @Size(min = 1, max = 512) String evidenceReference) {
    }

    public record StockAdjustmentRequest(
            @NotNull @Size(min = 1, max = 128) String warehouseId,
            @NotNull @Size(min = 1, max = 128) String skuId,
            @NotNull AdjustmentDirection direction,
            @NotNull @Min(1) Long quantity,
            @NotNull @Size(min = 1, max = 128) String adjustmentCaseId,
            @NotNull @Size(min = 1, max = 128) String adjustmentLineId,
            @NotNull AdjustmentReason reason,
            @Size(min = 1, max = 1000) String reasonNote,
            @NotNull @Size(min = 1, max = 512) String evidenceReference) {
    }

    public record MovementReversalRequest(
            @NotNull @Min(1) Integer reversalSequence,
            @NotNull @Min(1) Long quantity,
            @NotNull SellableReversalReason reason,
            @NotNull @Size(min = 1, max = 1000) String reasonNote,
            @NotNull @Size(min = 1, max = 128) String correctionCaseReference,
            @NotNull @Size(min = 1, max = 512) String evidenceReference) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record StockMutationResponse(String commandId, String correlationId, String outcome, String operationId,
                                        String movementId, Instant recordedAt, Boolean replayed) {
    }

    // ── Dispatch and returns ──────────────────────────────────────────────────

    public record PhysicalDispatchRequest(
            @NotNull @Size(min = 1, max = 128) String dispatchId,
            @NotNull @Size(min = 1, max = 128) String dispatchLineId,
            @NotNull @Size(min = 1, max = 128) String orderId,
            @NotNull @Size(min = 1, max = 128) String orderLineId,
            @NotNull @Size(min = 1, max = 128) String warehouseId,
            @NotNull @Size(min = 1, max = 128) String skuId,
            @NotNull @Min(1) Long quantity,
            @NotNull OffsetDateTime handedOverAt,
            @NotNull @Size(min = 1, max = 128) String handoverReference,
            @NotNull @Size(min = 1, max = 512) String evidenceReference) {
    }

    public record ReturnReceiptRequest(
            @NotNull @Size(min = 1, max = 128) String refundScopeId,
            @NotNull @Size(min = 1, max = 128) String orderLineId,
            @NotNull @Size(min = 1, max = 128) String actualSkuId,
            @NotNull @Size(min = 1, max = 128) String warehouseId,
            @NotNull @Min(1) Long quantity,
            @NotNull @Size(min = 1, max = 128) String returnReceiptId,
            @NotNull @Size(min = 1, max = 128) String returnReceiptLineId,
            @NotNull @Size(min = 1, max = 128) String packageReference,
            @NotNull OffsetDateTime receivedAt,
            @NotNull @Size(min = 1, max = 512) String evidenceReference) {
    }

    public record InspectionRequest(
            @NotNull @Size(min = 1, max = 128) String inspectionId,
            @NotNull @Size(min = 1, max = 128) String returnReceiptId,
            @NotNull @Size(min = 1, max = 128) String returnReceiptLineId,
            @NotNull @Size(min = 1, max = 128) String warehouseId,
            @NotNull @Min(1) Long quantity,
            @NotNull InspectionResult result,
            FailReason failReason,
            @NotNull @Size(min = 1, max = 128) String checklistReference,
            @NotNull @Size(min = 1, max = 512) String evidenceReference) {
    }

    public record QuarantineDispositionRequest(
            @NotNull @Size(min = 1, max = 128) String dispositionId,
            @NotNull @Size(min = 1, max = 128) String dispositionLineId,
            @NotNull @Size(min = 1, max = 128) String inspectionId,
            @NotNull @Size(min = 1, max = 128) String warehouseId,
            @NotNull @Size(min = 1, max = 128) String skuId,
            @NotNull @Min(1) Long quantity,
            @NotNull Disposition disposition,
            @NotNull @Size(min = 1, max = 40) String reason,
            @Size(min = 1, max = 128) String physicalDepartureReference,
            OffsetDateTime departedAt,
            @NotNull @Size(min = 1, max = 512) String evidenceReference) {
    }

    public record DispositionReversalRequest(
            @NotNull @Size(min = 1, max = 128) String dispositionLineId,
            @NotNull @Min(1) Integer reversalSequence,
            @NotNull @Min(1) Long quantity,
            @NotNull DispositionReversalReason reason,
            @NotNull @Size(min = 1, max = 1000) String reasonNote,
            @NotNull OffsetDateTime physicallyReturnedAt,
            @NotNull @Size(min = 1, max = 128) String physicalReturnReference,
            @NotNull @Size(min = 1, max = 512) String evidenceReference) {
    }

    // ── Queries ───────────────────────────────────────────────────────────────

    public record Page<T>(List<T> items, String nextCursor) {
    }

    public record StockPositionView(String warehouseId, String skuId, long onHand, long availableToSell,
                                    long reserved, long committed, long quarantine, long revision) {
    }

    public record Deltas(long onHand, long availableToSell, long reserved, long committed, long quarantine) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record StockMovementView(String movementId, String warehouseId, String skuId, String sourceOperationType,
                                    String sourceOperationId, String reversesMovementId, Deltas deltas,
                                    Instant recordedAt) {
    }

    public record DispatchQueueItem(String orderId, String orderLineId, String skuId, long quantity,
                                    String warehouseId, Instant committedAt) {
    }

    public record InspectionQueueItem(String refundScopeId, String orderId, String orderLineId, String returnReceiptId,
                                      String returnReceiptLineId, String skuId, String warehouseId,
                                      long receivedQuantity, long uninspectedQuantity, Instant receivedAt) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record QuarantineItem(String inspectionId, String warehouseId, String skuId, String failReason,
                                 long failedQuantity, long disposableQuantity, String latestHoldReason,
                                 Instant inspectedAt) {
    }

    public record AdminReservationLine(String orderLineId, String skuId, long quantity, String warehouseId,
                                       String state) {
    }

    public record AdminReservation(String orderId, String state, long revision, List<AdminReservationLine> lines) {
    }

    public record AdminRefundScopeLine(String orderLineId, String skuId, long expectedQuantity, long receivedQuantity,
                                       long passedQuantity, long failedQuantity) {
    }

    public record AdminRefundScope(String refundScopeId, String refundRequestId, String orderId, String state,
                                   long revision, List<AdminRefundScopeLine> lines) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record AdminCommandOutcome(String commandId, String correlationId, String operation, String result,
                                      String outcome, Integer httpStatus, String errorCode, String resourceId,
                                      Instant recordedAt) {
    }
}
