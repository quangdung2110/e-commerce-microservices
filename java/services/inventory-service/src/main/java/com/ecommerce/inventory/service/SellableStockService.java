package com.ecommerce.inventory.service;

import com.ecommerce.inventory.command.Accepted;
import com.ecommerce.inventory.command.CommandContext;
import com.ecommerce.inventory.common.DomainRejection;
import com.ecommerce.inventory.common.ErrorCode;
import com.ecommerce.inventory.domain.BucketDelta;
import com.ecommerce.inventory.store.AvailabilityStore;
import com.ecommerce.inventory.store.EvidenceStore;
import com.ecommerce.inventory.store.StockStore;
import com.ecommerce.inventory.store.StockStore.PositionKey;
import com.ecommerce.inventory.store.WarehouseStore;
import com.ecommerce.inventory.web.dto.AdminDtos;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Sellable receipt, adjustment and append-only sellable reversal (interfaces.md §4,
 * ADR-024, ADR-027). A negative change never touches reserved, committed or quarantine.
 */
@Service
public class SellableStockService {

    static final String SELLABLE_RECEIPT = "SELLABLE_RECEIPT";
    static final String STOCK_ADJUSTMENT = "STOCK_ADJUSTMENT";
    static final String SELLABLE_REVERSAL = "SELLABLE_REVERSAL";

    private static final Set<String> REVERSIBLE = Set.of(SELLABLE_RECEIPT, STOCK_ADJUSTMENT);

    private final WarehouseStore warehouses;
    private final StockStore stock;
    private final AvailabilityStore availability;
    private final EvidenceStore evidence;

    public SellableStockService(WarehouseStore warehouses, StockStore stock, AvailabilityStore availability,
                                EvidenceStore evidence) {
        this.warehouses = warehouses;
        this.stock = stock;
        this.availability = availability;
        this.evidence = evidence;
    }

    public Accepted receipt(AdminDtos.StockReceiptRequest request, CommandContext context) {
        String key = EvidenceStore.operationKey("receipt", request.warehouseId(), request.receiptId(),
                request.receiptLineId());
        evidence.requireUnclaimed(key);
        requireWarehouse(request.warehouseId());
        StockStore.Position position = stock.lockOrCreate(new PositionKey(request.warehouseId(), request.skuId()),
                context.now());
        String operationId = evidence.claim(key, SELLABLE_RECEIPT, context);
        String movementId = stock.apply(position, BucketDelta.sellableIn(request.quantity()),
                StockStore.MovementSpec.of(SELLABLE_RECEIPT, operationId), context);
        availability.markDirty(List.of(request.skuId()), context);
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("receipt", request);
        after.put("movement_id", movementId);
        evidence.audit(context, "STOCK_POSITION", target(position.key()), request.reason().name(),
                request.evidenceReference(), position.buckets(), after);
        return mutation(context, operationId, movementId);
    }

    public Accepted adjustment(AdminDtos.StockAdjustmentRequest request, CommandContext context) {
        String key = EvidenceStore.operationKey("adjustment", request.warehouseId(), request.adjustmentCaseId(),
                request.adjustmentLineId());
        evidence.requireUnclaimed(key);
        requireWarehouse(request.warehouseId());
        PositionKey positionKey = new PositionKey(request.warehouseId(), request.skuId());
        boolean increase = request.direction() == AdminDtos.AdjustmentDirection.INCREASE;
        StockStore.Position position;
        if (increase) {
            position = stock.lockOrCreate(positionKey, context.now());
        } else {
            position = stock.lock(positionKey).orElse(null);
            if (position == null || position.buckets().availableToSell() < request.quantity()) {
                throw new DomainRejection(ErrorCode.INSUFFICIENT_SELLABLE_STOCK,
                        "A negative adjustment can remove only available_to_sell units");
            }
        }
        String operationId = evidence.claim(key, STOCK_ADJUSTMENT, context);
        BucketDelta delta = increase ? BucketDelta.sellableIn(request.quantity())
                : BucketDelta.sellableOut(request.quantity());
        String movementId = stock.apply(position, delta, StockStore.MovementSpec.of(STOCK_ADJUSTMENT, operationId),
                context);
        availability.markDirty(List.of(request.skuId()), context);
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("adjustment", request);
        after.put("movement_id", movementId);
        evidence.audit(context, "STOCK_POSITION", target(positionKey), request.reason().name(),
                request.evidenceReference(), position.buckets(), after);
        return mutation(context, operationId, movementId);
    }

    /**
     * Compensates a successful receipt or adjustment. Sequence is contiguous per original,
     * the cumulative quantity never exceeds the original, and a reversal is never reversed.
     */
    public Accepted reverse(String movementId, AdminDtos.MovementReversalRequest request, CommandContext context) {
        String key = EvidenceStore.operationKey("sellable-reversal", movementId,
                String.valueOf(request.reversalSequence()));
        evidence.requireUnclaimed(key);
        StockStore.Movement original = stock.lockMovement(movementId)
                .orElseThrow(() -> new DomainRejection(ErrorCode.NOT_FOUND, "No movement exists with this identifier"));
        if (!REVERSIBLE.contains(original.sourceOperationType())) {
            throw new DomainRejection(ErrorCode.MOVEMENT_NOT_REVERSIBLE,
                    "Only a sellable receipt or adjustment can be reversed");
        }
        StockStore.ReversalTotals totals = stock.reversalsOf(movementId);
        long originalQuantity = Math.abs(original.delta().availableToSell());
        if (request.reversalSequence() != totals.count() + 1) {
            throw new DomainRejection(ErrorCode.MOVEMENT_NOT_REVERSIBLE,
                    "The next reversal_sequence for this movement is " + (totals.count() + 1));
        }
        if (totals.quantity() + request.quantity() > originalQuantity) {
            throw new DomainRejection(ErrorCode.MOVEMENT_NOT_REVERSIBLE,
                    "Cumulative reversed quantity would exceed the original " + originalQuantity);
        }
        PositionKey positionKey = new PositionKey(original.warehouseId(), original.skuId());
        StockStore.Position position = stock.lock(positionKey).orElseThrow();
        boolean originalIncreased = original.delta().availableToSell() > 0;
        if (originalIncreased && position.buckets().availableToSell() < request.quantity()) {
            throw new DomainRejection(ErrorCode.INSUFFICIENT_SELLABLE_STOCK,
                    "Reversing an increase needs quantity <= available_to_sell");
        }
        String operationId = evidence.claim(key, SELLABLE_REVERSAL, context);
        BucketDelta delta = originalIncreased ? BucketDelta.sellableOut(request.quantity())
                : BucketDelta.sellableIn(request.quantity());
        String reversalMovementId = stock.apply(position, delta, new StockStore.MovementSpec(SELLABLE_REVERSAL,
                operationId, movementId, request.reversalSequence()), context);
        availability.markDirty(List.of(original.skuId()), context);
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("reversal", request);
        after.put("reverses_movement_id", movementId);
        after.put("movement_id", reversalMovementId);
        evidence.audit(context, "STOCK_POSITION", target(positionKey), request.reason().name(),
                request.evidenceReference(), position.buckets(), after);
        return mutation(context, operationId, reversalMovementId);
    }

    private void requireWarehouse(String warehouseId) {
        if (!warehouses.exists(warehouseId)) {
            throw new DomainRejection(ErrorCode.WAREHOUSE_NOT_FOUND, "No warehouse exists with this identifier");
        }
    }

    static String target(PositionKey key) {
        return key.warehouseId() + "|" + key.skuId();
    }

    static Accepted mutation(CommandContext context, String operationId, String movementId) {
        return new Accepted(new AdminDtos.StockMutationResponse(context.commandId(), context.correlationId(),
                "ACCEPTED", operationId, movementId, context.now(), false), "ACCEPTED", operationId);
    }
}
