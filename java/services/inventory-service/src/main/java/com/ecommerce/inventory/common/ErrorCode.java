package com.ecommerce.inventory.common;

/** Stable error catalog (docs/inventory/interfaces.md §5, ADR-024, ADR-027). */
public enum ErrorCode {
    INVALID_REQUEST(400, "Invalid request", false),
    UNAUTHENTICATED(401, "Unauthenticated", false),
    FORBIDDEN(403, "Forbidden", false),
    NOT_FOUND(404, "Not found", false),
    IDEMPOTENCY_KEY_REUSED(409, "Idempotency key reused", false),
    RESERVATION_ALREADY_EXISTS(409, "Reservation already exists", false),
    RESERVATION_NOT_RESERVED(409, "Reservation is not reserved", false),
    RESERVATION_NOT_RECOVERABLE(409, "Reservation is not recoverable", false),
    PRE_DISPATCH_RELEASE_NOT_ALLOWED(409, "Pre-dispatch release not allowed", false),
    REFUND_SCOPE_ALREADY_EXISTS(409, "Refund scope already exists", false),
    REFUND_SCOPE_LINE_INVALID(409, "Refund scope line invalid", false),
    REFUND_SCOPE_LINE_NOT_DISPATCHED(409, "Refund scope line not dispatched", false),
    REFUND_SCOPE_STATE_CONFLICT(409, "Refund scope state conflict", false),
    OPERATION_IDENTITY_CONFLICT(409, "Operation identity conflict", false),
    INSUFFICIENT_SELLABLE_STOCK(409, "Insufficient sellable stock", false),
    DISPATCH_NOT_ALLOWED(409, "Dispatch not allowed", false),
    RETURN_RECEIPT_REJECTED(409, "Return receipt rejected", false),
    INSPECTION_REJECTED(409, "Inspection rejected", false),
    DISPOSITION_REJECTED(409, "Disposition rejected", false),
    MOVEMENT_NOT_REVERSIBLE(409, "Movement not reversible", false),
    WAREHOUSE_CONFLICT(409, "Warehouse conflict", false),
    WAREHOUSE_NOT_FOUND(409, "Warehouse not found", false),
    READINESS_REVISION_CONFLICT(409, "Readiness revision conflict", false),
    SNAPSHOT_SESSION_EXPIRED(410, "Snapshot session expired", false),
    REPLAY_WINDOW_EXPIRED(410, "Replay window expired", false),
    INVENTORY_UNAVAILABLE(503, "Inventory unavailable", true),
    INVENTORY_BUSY(503, "Inventory busy", true),
    INVENTORY_AVAILABILITY_UNAVAILABLE(503, "Inventory availability unavailable", true);

    private final int status;
    private final String title;
    private final boolean retryable;

    ErrorCode(int status, String title, boolean retryable) {
        this.status = status;
        this.title = title;
        this.retryable = retryable;
    }

    public int status() {
        return status;
    }

    public String title() {
        return title;
    }

    public boolean retryable() {
        return retryable;
    }

    public String typeUri() {
        return "https://contracts.ecommerce.local/problems/" + name().toLowerCase().replace('_', '-');
    }
}
