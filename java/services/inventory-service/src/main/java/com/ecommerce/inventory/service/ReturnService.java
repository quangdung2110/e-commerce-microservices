package com.ecommerce.inventory.service;

import com.ecommerce.inventory.command.Accepted;
import com.ecommerce.inventory.command.CommandContext;
import com.ecommerce.inventory.common.DomainRejection;
import com.ecommerce.inventory.common.ErrorCode;
import com.ecommerce.inventory.domain.BucketDelta;
import com.ecommerce.inventory.domain.RefundScopeStates;
import com.ecommerce.inventory.domain.RefundScopeStates.State;
import com.ecommerce.inventory.store.AvailabilityStore;
import com.ecommerce.inventory.store.EvidenceStore;
import com.ecommerce.inventory.store.RefundStore;
import com.ecommerce.inventory.store.StockStore;
import com.ecommerce.inventory.store.StockStore.PositionKey;
import com.ecommerce.inventory.store.WarehouseStore;
import com.ecommerce.inventory.web.dto.AdminDtos;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * ADMIN return receipt, final inspection, quarantine disposition and disposition reversal
 * (domain.md, ADR-018/024/027). Scope records are locked before stock positions.
 */
@Service
public class ReturnService {

    static final String RETURN_RECEIPT = "RETURN_RECEIPT";
    static final String INSPECTION_PASS = "INSPECTION_PASS";
    static final String INSPECTION_FAIL = "INSPECTION_FAIL";
    static final String QUARANTINE_HOLD = "QUARANTINE_HOLD";
    static final String QUARANTINE_DISPOSITION = "QUARANTINE_DISPOSITION";
    static final String DISPOSITION_REVERSAL = "DISPOSITION_REVERSAL";

    private final WarehouseStore warehouses;
    private final RefundStore refunds;
    private final StockStore stock;
    private final AvailabilityStore availability;
    private final EvidenceStore evidence;
    private final EventFactory events;

    public ReturnService(WarehouseStore warehouses, RefundStore refunds, StockStore stock,
                         AvailabilityStore availability, EvidenceStore evidence, EventFactory events) {
        this.warehouses = warehouses;
        this.refunds = refunds;
        this.stock = stock;
        this.availability = availability;
        this.evidence = evidence;
        this.events = events;
    }

    /** Receives an exact scope line residual into quarantine at the physical receiving warehouse. */
    public Accepted receipt(AdminDtos.ReturnReceiptRequest request, CommandContext context) {
        String key = EvidenceStore.operationKey("return-receipt", request.returnReceiptId(),
                request.returnReceiptLineId());
        evidence.requireUnclaimed(key);
        requireWarehouse(request.warehouseId());
        RefundStore.Scope scope = refunds.lock(request.refundScopeId()).orElseThrow(() -> new DomainRejection(
                ErrorCode.RETURN_RECEIPT_REJECTED, "No refund scope exists with this identifier"));
        if (scope.state() != State.REGISTERED && scope.state() != State.IN_PROGRESS) {
            throw new DomainRejection(ErrorCode.REFUND_SCOPE_STATE_CONFLICT,
                    "The refund scope is " + scope.state() + " and accepts no receipt");
        }
        List<RefundStore.ScopeLine> lines = refunds.lockLines(scope.refundScopeId());
        RefundStore.ScopeLine line = lines.stream().filter(l -> l.orderLineId().equals(request.orderLineId()))
                .findFirst().orElseThrow(() -> new DomainRejection(ErrorCode.RETURN_RECEIPT_REJECTED,
                        "The order line is not part of this refund scope"));
        if (!line.skuId().equals(request.actualSkuId())) {
            throw new DomainRejection(ErrorCode.RETURN_RECEIPT_REJECTED, "The received SKU differs from the scope line");
        }
        if (line.received() + request.quantity() > line.expected()) {
            throw new DomainRejection(ErrorCode.RETURN_RECEIPT_REJECTED,
                    "Only " + (line.expected() - line.received()) + " units remain expected for this line");
        }
        StockStore.Position position = stock.lockOrCreate(new PositionKey(request.warehouseId(), line.skuId()),
                context.now());
        String operationId = evidence.claim(key, RETURN_RECEIPT, context);
        String movementId = stock.apply(position, BucketDelta.returnReceipt(request.quantity()),
                StockStore.MovementSpec.of(RETURN_RECEIPT, operationId), context);
        refunds.insertReceiptLine(new RefundStore.ReceiptLine(request.returnReceiptId(), request.returnReceiptLineId(),
                        scope.refundScopeId(), line.orderLineId(), line.skuId(), request.warehouseId(),
                        request.quantity(), 0, request.receivedAt().toInstant()), request.packageReference(),
                request.evidenceReference(), movementId, context.now());
        RefundStore.ScopeLine updated = new RefundStore.ScopeLine(line.refundScopeId(), line.orderLineId(),
                line.lineNo(), line.skuId(), line.expected(), line.received() + request.quantity(), line.passed(),
                line.failed());
        RefundScopeService.counters(updated);
        refunds.updateScopeLine(updated);
        refunds.updateScope(scope.refundScopeId(), State.IN_PROGRESS, null, scope.revision() + 1, context.now());

        Map<String, Object> after = new LinkedHashMap<>();
        after.put("state", State.IN_PROGRESS.name());
        after.put("order_line_id", line.orderLineId());
        after.put("received_quantity", updated.received());
        after.put("movement_id", movementId);
        evidence.audit(context, "REFUND_SCOPE", scope.refundScopeId(), null, request.evidenceReference(),
                Map.of("state", scope.state().name(), "received_quantity", line.received()), after);
        return SellableStockService.mutation(context, operationId, movementId);
    }

    /**
     * Final, exactly-once inspection of receipt-line quantity at its receiving warehouse.
     * When every expected unit has a result the scope becomes {@code INSPECTION_COMPLETED}
     * and the single completion fact is written atomically.
     */
    public Accepted inspect(AdminDtos.InspectionRequest request, CommandContext context) {
        String key = EvidenceStore.operationKey("inspection", request.inspectionId());
        evidence.requireUnclaimed(key);
        requireWarehouse(request.warehouseId());
        RefundStore.ReceiptLine located = refunds.findReceiptLine(request.returnReceiptId(),
                request.returnReceiptLineId()).orElseThrow(() -> new DomainRejection(ErrorCode.INSPECTION_REJECTED,
                "No return receipt line exists with this identifier"));
        RefundStore.Scope scope = refunds.lock(located.refundScopeId()).orElseThrow();
        List<RefundStore.ScopeLine> lines = refunds.lockLines(scope.refundScopeId());
        RefundStore.ReceiptLine receipt = refunds.lockReceiptLine(request.returnReceiptId(),
                request.returnReceiptLineId()).orElseThrow();
        if (!receipt.warehouseId().equals(request.warehouseId())) {
            throw new DomainRejection(ErrorCode.INSPECTION_REJECTED,
                    "Inspection must happen at the receiving warehouse");
        }
        long residual = receipt.quantity() - receipt.inspectedQuantity();
        if (request.quantity() > residual) {
            throw new DomainRejection(ErrorCode.INSPECTION_REJECTED,
                    "Only " + residual + " units of this receipt line remain uninspected");
        }
        boolean pass = request.result() == AdminDtos.InspectionResult.PASS;
        String operationId = evidence.claim(key, pass ? INSPECTION_PASS : INSPECTION_FAIL, context);
        String movementId = null;
        if (pass) {
            StockStore.Position position = stock.lock(new PositionKey(receipt.warehouseId(), receipt.skuId()))
                    .orElseThrow();
            movementId = stock.apply(position, BucketDelta.inspectionPass(request.quantity()),
                    StockStore.MovementSpec.of(INSPECTION_PASS, operationId), context);
            availability.markDirty(List.of(receipt.skuId()), context);
        }
        refunds.insertInspection(new RefundStore.Inspection(request.inspectionId(), receipt.returnReceiptId(),
                        receipt.returnReceiptLineId(), scope.refundScopeId(), receipt.orderLineId(),
                        receipt.warehouseId(), receipt.skuId(), request.quantity(), request.result().name(),
                        pass ? null : request.failReason().name(), 0, null, context.now()),
                request.checklistReference(), request.evidenceReference(), movementId);
        refunds.addInspected(receipt.returnReceiptId(), receipt.returnReceiptLineId(), request.quantity());

        List<RefundStore.ScopeLine> updatedLines = new ArrayList<>();
        for (RefundStore.ScopeLine line : lines) {
            if (line.orderLineId().equals(receipt.orderLineId())) {
                line = new RefundStore.ScopeLine(line.refundScopeId(), line.orderLineId(), line.lineNo(),
                        line.skuId(), line.expected(), line.received(),
                        line.passed() + (pass ? request.quantity() : 0),
                        line.failed() + (pass ? 0 : request.quantity()));
                RefundScopeService.counters(line);
                refunds.updateScopeLine(line);
            }
            updatedLines.add(line);
        }
        long revision = scope.revision() + 1;
        boolean completed = RefundScopeStates.allInspected(updatedLines.stream()
                .map(RefundScopeService::counters).toList());
        State state = completed ? State.INSPECTION_COMPLETED : scope.state();
        refunds.updateScope(scope.refundScopeId(), state, null, revision, context.now());
        if (completed) {
            events.inspectionCompleted(scope, revision, updatedLines, context.correlationId(), context.commandId(),
                    context.now());
        }

        Map<String, Object> after = new LinkedHashMap<>();
        after.put("inspection_id", request.inspectionId());
        after.put("result", request.result().name());
        after.put("quantity", request.quantity());
        after.put("scope_state", state.name());
        if (movementId != null) {
            after.put("movement_id", movementId);
        }
        evidence.audit(context, "REFUND_SCOPE", scope.refundScopeId(),
                pass ? "PASS" : request.failReason().name(), request.evidenceReference(),
                Map.of("scope_state", scope.state().name()), after);
        return SellableStockService.mutation(context, operationId, movementId);
    }

    /**
     * {@code HOLD} annotates failed quantity without moving stock; {@code DISPOSE} and
     * {@code RETURN_TO_SUPPLIER} consume failed, undisposed quantity after physical departure.
     */
    public Accepted dispose(AdminDtos.QuarantineDispositionRequest request, CommandContext context) {
        String key = EvidenceStore.operationKey("disposition", request.dispositionId(), request.dispositionLineId());
        evidence.requireUnclaimed(key);
        requireWarehouse(request.warehouseId());
        RefundStore.Inspection inspection = refunds.lockInspection(request.inspectionId()).orElseThrow(() ->
                new DomainRejection(ErrorCode.DISPOSITION_REJECTED, "No inspection exists with this identifier"));
        if (!"FAIL".equals(inspection.result())) {
            throw new DomainRejection(ErrorCode.DISPOSITION_REJECTED, "Only failed-inspection stock can be disposed");
        }
        if (!inspection.warehouseId().equals(request.warehouseId()) || !inspection.skuId().equals(request.skuId())) {
            throw new DomainRejection(ErrorCode.DISPOSITION_REJECTED,
                    "The disposition must name the inspection's warehouse and SKU");
        }
        if (request.quantity() > inspection.disposable()) {
            throw new DomainRejection(ErrorCode.DISPOSITION_REJECTED,
                    "Only " + inspection.disposable() + " failed units remain undisposed");
        }
        boolean hold = request.disposition() == AdminDtos.Disposition.HOLD;
        String operationId = evidence.claim(key, hold ? QUARANTINE_HOLD : QUARANTINE_DISPOSITION, context);
        String movementId = null;
        long disposed = inspection.disposedQuantity();
        String holdReason = inspection.latestHoldReason();
        if (hold) {
            holdReason = request.reason();
        } else {
            StockStore.Position position = stock.lock(new PositionKey(inspection.warehouseId(), inspection.skuId()))
                    .orElseThrow();
            movementId = stock.apply(position, BucketDelta.quarantineOut(request.quantity()),
                    StockStore.MovementSpec.of(QUARANTINE_DISPOSITION, operationId), context);
            disposed += request.quantity();
        }
        refunds.updateDisposal(inspection.inspectionId(), disposed, holdReason);
        refunds.insertDisposition(new RefundStore.Disposition(request.dispositionId(), request.dispositionLineId(),
                        inspection.inspectionId(), inspection.warehouseId(), inspection.skuId(), request.quantity(),
                        request.disposition().name(), request.reason(), movementId),
                request.physicalDepartureReference(),
                request.departedAt() == null ? null : request.departedAt().toInstant(), request.evidenceReference(),
                context.actorId(), context.now());

        Map<String, Object> after = new LinkedHashMap<>();
        after.put("disposition", request.disposition().name());
        after.put("quantity", request.quantity());
        after.put("disposed_quantity", disposed);
        if (movementId != null) {
            after.put("movement_id", movementId);
        }
        evidence.audit(context, "QUARANTINE_ITEM", inspection.inspectionId(), request.reason(),
                request.evidenceReference(), Map.of("disposed_quantity", inspection.disposedQuantity()), after);
        return SellableStockService.mutation(context, operationId, movementId);
    }

    /** Restores physically returned units of an outbound disposition line to quarantine only. */
    public Accepted reverseDisposition(String dispositionId, AdminDtos.DispositionReversalRequest request,
                                       CommandContext context) {
        String key = EvidenceStore.operationKey("disposition-reversal", dispositionId, request.dispositionLineId(),
                String.valueOf(request.reversalSequence()));
        evidence.requireUnclaimed(key);
        RefundStore.Disposition disposition = refunds.lockDisposition(dispositionId, request.dispositionLineId())
                .orElseThrow(() -> new DomainRejection(ErrorCode.NOT_FOUND,
                        "No disposition line exists with this identifier"));
        if (AdminDtos.Disposition.HOLD.name().equals(disposition.disposition())) {
            throw new DomainRejection(ErrorCode.DISPOSITION_REJECTED,
                    "Only DISPOSE or RETURN_TO_SUPPLIER can be reversed");
        }
        RefundStore.DispositionReversalTotals totals = refunds.reversalsOf(dispositionId, request.dispositionLineId());
        if (request.reversalSequence() != totals.count() + 1) {
            throw new DomainRejection(ErrorCode.DISPOSITION_REJECTED,
                    "The next reversal_sequence for this disposition line is " + (totals.count() + 1));
        }
        if (totals.quantity() + request.quantity() > disposition.quantity()) {
            throw new DomainRejection(ErrorCode.DISPOSITION_REJECTED,
                    "Cumulative reversed quantity would exceed the disposed " + disposition.quantity());
        }
        RefundStore.Inspection inspection = refunds.lockInspection(disposition.inspectionId()).orElseThrow();
        StockStore.Position position = stock.lock(new PositionKey(disposition.warehouseId(), disposition.skuId()))
                .orElseThrow();
        String operationId = evidence.claim(key, DISPOSITION_REVERSAL, context);
        String movementId = stock.apply(position, BucketDelta.quarantineBack(request.quantity()),
                new StockStore.MovementSpec(DISPOSITION_REVERSAL, operationId, disposition.movementId(),
                        request.reversalSequence()), context);
        refunds.updateDisposal(inspection.inspectionId(), inspection.disposedQuantity() - request.quantity(),
                inspection.latestHoldReason());
        refunds.insertDispositionReversal(dispositionId, request.dispositionLineId(), request.reversalSequence(),
                request.quantity(), request.reason().name(), request.reasonNote(),
                request.physicallyReturnedAt().toInstant(), request.physicalReturnReference(),
                request.evidenceReference(), movementId, context.actorId(), context.now());

        Map<String, Object> after = new LinkedHashMap<>();
        after.put("disposition_id", dispositionId);
        after.put("disposition_line_id", request.dispositionLineId());
        after.put("reversal_sequence", request.reversalSequence());
        after.put("quantity", request.quantity());
        after.put("movement_id", movementId);
        evidence.audit(context, "QUARANTINE_ITEM", inspection.inspectionId(), request.reason().name(),
                request.evidenceReference(), Map.of("disposed_quantity", inspection.disposedQuantity()), after);
        return SellableStockService.mutation(context, operationId, movementId);
    }

    private void requireWarehouse(String warehouseId) {
        if (!warehouses.exists(warehouseId)) {
            throw new DomainRejection(ErrorCode.WAREHOUSE_NOT_FOUND, "No warehouse exists with this identifier");
        }
    }
}
