package com.ecommerce.inventory.web;

import com.ecommerce.inventory.command.CommandExecutor;
import com.ecommerce.inventory.service.DispatchService;
import com.ecommerce.inventory.service.QueryService;
import com.ecommerce.inventory.service.ReturnService;
import com.ecommerce.inventory.service.SellableStockService;
import com.ecommerce.inventory.service.WarehouseService;
import com.ecommerce.inventory.web.dto.AdminDtos;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Map;

/**
 * contracts/http/inventory/admin/v1 at {@code /api/v1/admin/inventory} (ADR-024): warehouse
 * master data, physical stock operations and read-only operational queues.
 */
@RestController
@RequestMapping("/api/v1/admin/inventory")
public class AdminController {

    private static final String KEY = "Idempotency-Key";
    private static final int DEFAULT_LIMIT = 50;
    private static final int MAX_LIMIT = 100;

    private final CommandExecutor executor;
    private final WarehouseService warehouses;
    private final SellableStockService sellable;
    private final DispatchService dispatches;
    private final ReturnService returns;
    private final QueryService queries;

    public AdminController(CommandExecutor executor, WarehouseService warehouses, SellableStockService sellable,
                           DispatchService dispatches, ReturnService returns, QueryService queries) {
        this.executor = executor;
        this.warehouses = warehouses;
        this.sellable = sellable;
        this.dispatches = dispatches;
        this.returns = returns;
        this.queries = queries;
    }

    // ── Warehouses ────────────────────────────────────────────────────────────

    @PostMapping("/warehouses")
    public ResponseEntity<JsonNode> createWarehouse(@RequestHeader(value = KEY, required = false) String key,
                                                    @Valid @RequestBody AdminDtos.CreateWarehouseRequest request) {
        return executor.execute("CREATE_WAREHOUSE", key, Map.of(), request,
                context -> warehouses.create(request, context)).toEntity();
    }

    @PatchMapping("/warehouses/{warehouse_id}")
    public ResponseEntity<JsonNode> updateWarehouse(@PathVariable("warehouse_id") String warehouseId,
                                                    @RequestHeader(value = KEY, required = false) String key,
                                                    @Valid @RequestBody AdminDtos.UpdateWarehouseRequest request) {
        RequestRules.opaqueId("warehouse_id", warehouseId);
        RequestRules.warehouseUpdate(request);
        return executor.execute("UPDATE_WAREHOUSE", key, Map.of("warehouse_id", warehouseId), request,
                context -> warehouses.update(warehouseId, request, context)).toEntity();
    }

    @GetMapping("/warehouses")
    public AdminDtos.Page<AdminDtos.WarehouseView> listWarehouses(
            @RequestParam(value = "is_active", required = false) Boolean active,
            @RequestParam(value = "cursor", required = false) String cursor,
            @RequestParam(value = "limit", required = false) Integer limit) {
        return queries.warehouses(active, cursor, RequestRules.limit(limit, DEFAULT_LIMIT, MAX_LIMIT));
    }

    @GetMapping("/warehouses/{warehouse_id}")
    public AdminDtos.WarehouseView warehouse(@PathVariable("warehouse_id") String warehouseId) {
        return queries.warehouse(warehouseId);
    }

    // ── Sellable stock ────────────────────────────────────────────────────────

    @PostMapping("/stock-receipts")
    public ResponseEntity<JsonNode> receipt(@RequestHeader(value = KEY, required = false) String key,
                                            @Valid @RequestBody AdminDtos.StockReceiptRequest request) {
        RequestRules.receipt(request);
        return executor.execute("RECORD_SELLABLE_STOCK_RECEIPT", key, Map.of(), request,
                context -> sellable.receipt(request, context)).toEntity();
    }

    @PostMapping("/stock-adjustments")
    public ResponseEntity<JsonNode> adjustment(@RequestHeader(value = KEY, required = false) String key,
                                               @Valid @RequestBody AdminDtos.StockAdjustmentRequest request) {
        RequestRules.adjustment(request);
        return executor.execute("RECORD_STOCK_ADJUSTMENT", key, Map.of(), request,
                context -> sellable.adjustment(request, context)).toEntity();
    }

    @PostMapping("/stock-movements/{movement_id}/reversals")
    public ResponseEntity<JsonNode> reverseMovement(@PathVariable("movement_id") String movementId,
                                                    @RequestHeader(value = KEY, required = false) String key,
                                                    @Valid @RequestBody AdminDtos.MovementReversalRequest request) {
        RequestRules.opaqueId("movement_id", movementId);
        return executor.execute("REVERSE_SELLABLE_STOCK_MOVEMENT", key, Map.of("movement_id", movementId), request,
                context -> sellable.reverse(movementId, request, context)).toEntity();
    }

    @GetMapping("/stock-positions")
    public AdminDtos.Page<AdminDtos.StockPositionView> positions(
            @RequestParam(value = "warehouse_id", required = false) String warehouseId,
            @RequestParam(value = "sku_id", required = false) String skuId,
            @RequestParam(value = "cursor", required = false) String cursor,
            @RequestParam(value = "limit", required = false) Integer limit) {
        return queries.positions(warehouseId, skuId, cursor, RequestRules.limit(limit, DEFAULT_LIMIT, MAX_LIMIT));
    }

    @GetMapping("/stock-positions/{warehouse_id}/{sku_id}")
    public AdminDtos.StockPositionView position(@PathVariable("warehouse_id") String warehouseId,
                                                @PathVariable("sku_id") String skuId) {
        return queries.position(warehouseId, skuId);
    }

    @GetMapping("/stock-movements")
    public AdminDtos.Page<AdminDtos.StockMovementView> movements(
            @RequestParam(value = "warehouse_id", required = false) String warehouseId,
            @RequestParam(value = "sku_id", required = false) String skuId,
            @RequestParam(value = "recorded_from", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime from,
            @RequestParam(value = "recorded_to", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime to,
            @RequestParam(value = "source_operation_type", required = false) String sourceOperationType,
            @RequestParam(value = "cursor", required = false) String cursor,
            @RequestParam(value = "limit", required = false) Integer limit) {
        return queries.movements(warehouseId, skuId, sourceOperationType, instant(from), instant(to), cursor,
                RequestRules.limit(limit, DEFAULT_LIMIT, MAX_LIMIT));
    }

    @GetMapping("/stock-movements/{movement_id}")
    public AdminDtos.StockMovementView movement(@PathVariable("movement_id") String movementId) {
        return queries.movement(movementId);
    }

    // ── Dispatch ──────────────────────────────────────────────────────────────

    @PostMapping("/physical-dispatches")
    public ResponseEntity<JsonNode> dispatch(@RequestHeader(value = KEY, required = false) String key,
                                             @Valid @RequestBody AdminDtos.PhysicalDispatchRequest request) {
        return executor.execute("RECORD_PHYSICAL_DISPATCH", key, Map.of(), request,
                context -> dispatches.dispatch(request, context)).toEntity();
    }

    @GetMapping("/dispatch-queue")
    public AdminDtos.Page<AdminDtos.DispatchQueueItem> dispatchQueue(
            @RequestParam(value = "warehouse_id", required = false) String warehouseId,
            @RequestParam(value = "cursor", required = false) String cursor,
            @RequestParam(value = "limit", required = false) Integer limit) {
        return queries.dispatchQueue(warehouseId, cursor, RequestRules.limit(limit, DEFAULT_LIMIT, MAX_LIMIT));
    }

    @GetMapping("/reservations/{order_id}")
    public AdminDtos.AdminReservation reservation(@PathVariable("order_id") String orderId) {
        return queries.adminReservation(orderId);
    }

    // ── Returns and quarantine ────────────────────────────────────────────────

    @PostMapping("/return-receipts")
    public ResponseEntity<JsonNode> returnReceipt(@RequestHeader(value = KEY, required = false) String key,
                                                  @Valid @RequestBody AdminDtos.ReturnReceiptRequest request) {
        return executor.execute("RECORD_RETURN_RECEIPT", key, Map.of(), request,
                context -> returns.receipt(request, context)).toEntity();
    }

    @PostMapping("/inspections")
    public ResponseEntity<JsonNode> inspection(@RequestHeader(value = KEY, required = false) String key,
                                               @Valid @RequestBody AdminDtos.InspectionRequest request) {
        RequestRules.inspection(request);
        return executor.execute("RECORD_FINAL_INSPECTION", key, Map.of(), request,
                context -> returns.inspect(request, context)).toEntity();
    }

    @PostMapping("/quarantine-dispositions")
    public ResponseEntity<JsonNode> disposition(@RequestHeader(value = KEY, required = false) String key,
                                                @Valid @RequestBody AdminDtos.QuarantineDispositionRequest request) {
        RequestRules.disposition(request);
        return executor.execute("RECORD_QUARANTINE_DISPOSITION", key, Map.of(), request,
                context -> returns.dispose(request, context)).toEntity();
    }

    @PostMapping("/quarantine-dispositions/{disposition_id}/reversals")
    public ResponseEntity<JsonNode> reverseDisposition(@PathVariable("disposition_id") String dispositionId,
                                                       @RequestHeader(value = KEY, required = false) String key,
                                                       @Valid @RequestBody AdminDtos.DispositionReversalRequest request) {
        RequestRules.opaqueId("disposition_id", dispositionId);
        return executor.execute("REVERSE_QUARANTINE_DISPOSITION", key, Map.of("disposition_id", dispositionId),
                request, context -> returns.reverseDisposition(dispositionId, request, context)).toEntity();
    }

    @GetMapping("/inspection-queue")
    public AdminDtos.Page<AdminDtos.InspectionQueueItem> inspectionQueue(
            @RequestParam(value = "warehouse_id", required = false) String warehouseId,
            @RequestParam(value = "cursor", required = false) String cursor,
            @RequestParam(value = "limit", required = false) Integer limit) {
        return queries.inspectionQueue(warehouseId, cursor, RequestRules.limit(limit, DEFAULT_LIMIT, MAX_LIMIT));
    }

    @GetMapping("/quarantine-items")
    public AdminDtos.Page<AdminDtos.QuarantineItem> quarantineItems(
            @RequestParam(value = "warehouse_id", required = false) String warehouseId,
            @RequestParam(value = "cursor", required = false) String cursor,
            @RequestParam(value = "limit", required = false) Integer limit) {
        return queries.quarantineItems(warehouseId, cursor, RequestRules.limit(limit, DEFAULT_LIMIT, MAX_LIMIT));
    }

    @GetMapping("/refund-scopes")
    public AdminDtos.AdminRefundScope refundScopeByOrder(@RequestParam("order_id") String orderId) {
        return queries.adminRefundScopeByOrder(orderId);
    }

    @GetMapping("/refund-scope-summaries")
    public AdminDtos.Page<AdminDtos.AdminRefundScopeSummary> refundScopeSummaries(
            @RequestParam(value = "state", required = false) String state,
            @RequestParam(value = "cursor", required = false) String cursor,
            @RequestParam(value = "limit", required = false) Integer limit) {
        return queries.adminRefundScopeSummaries(state, cursor, RequestRules.limit(limit, DEFAULT_LIMIT, MAX_LIMIT));
    }

    @GetMapping("/refund-scopes/{refund_scope_id}")
    public AdminDtos.AdminRefundScope refundScope(@PathVariable("refund_scope_id") String refundScopeId) {
        return queries.adminRefundScope(refundScopeId);
    }

    @GetMapping("/commands/{command_id}")
    public AdminDtos.AdminCommandOutcome command(@PathVariable("command_id") String commandId) {
        return queries.adminCommand(RequestContext.ADMIN_NAMESPACE, commandId);
    }

    private static Instant instant(OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }
}
