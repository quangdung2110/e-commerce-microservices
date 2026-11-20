package com.ecommerce.order.common;

/** Stable problem codes (docs/order/domain.md §7, ADR-020). */
public enum ErrorCode {
    INVALID_REQUEST(400, false, "Invalid request"),
    UNAUTHENTICATED(401, false, "Authentication required"),
    FORBIDDEN(403, false, "Forbidden"),
    NOT_FOUND(404, false, "Not found"),
    INVALID_ORDER_TRANSITION(409, false, "Invalid order transition"),
    IDEMPOTENCY_KEY_REUSED(409, false, "Idempotency key reused with a different payload"),
    REFUND_CUSTOMER_REQUEST_ALREADY_USED(409, false, "Buyer return request already used"),
    REFUND_REQUIRES_ALL_ITEMS_DELIVERED(409, false, "Every order line must be delivered"),
    DELIVERY_REQUIRES_DISPATCH(409, false, "A line must be dispatched before delivery can be confirmed"),
    DELIVERY_ALREADY_CONFIRMED(409, false, "The line delivery was already confirmed differently"),
    REFUND_CASE_ALREADY_EXISTS(409, false, "The order already has its lifetime refund case"),
    ADMIN_REFUND_REQUIRES_ALL_UNDISPATCHED(409, false, "Every committed line must still be undispatched"),
    REFUND_MANUAL_REVIEW_REQUIRED(409, false, "The refund needs manual review"),
    PAYMENT_RETRY_NOT_ELIGIBLE(409, false, "A new payment attempt is not allowed"),
    OPERATION_IN_PROGRESS(409, false, "Another operation of this type is still running"),
    PROMOTION_RECALCULATION_NEEDED(409, false, "Promotion recalculation needed"),
    INVENTORY_RESERVATION_REJECTED(409, false, "Inventory rejected the reservation"),
    PAYMENT_INITIATION_REJECTED(409, false, "Payment rejected the initiation"),
    CHECKOUT_PAYMENT_WINDOW_EXHAUSTED(409, false, "Resource holds expire before payment can start"),
    ORDER_CANCELLED_DURING_PREPARATION(409, false, "The order was cancelled before payment became ready"),
    STALE_CASE_REVISION(409, false, "Reconciliation case revision is stale"),
    RESOLUTION_NOT_PERMITTED(409, false, "Resolution is not permitted for this obligation"),
    INVALID_REFUND_SCOPE(422, false, "Invalid refund scope"),
    INVALID_DELIVERY_CONFIRMATION(422, false, "Invalid delivery confirmation"),
    INVALID_CHECKOUT_SNAPSHOT(422, false, "Checkout snapshot is inconsistent"),
    RECONCILIATION_EVIDENCE_INVALID(422, false, "Evidence does not match durable state"),
    SUBMISSION_IN_PROGRESS(503, true, "Submission is still being prepared"),
    DEPENDENCY_UNAVAILABLE(503, true, "A required dependency is unavailable"),
    ORDER_UNAVAILABLE(503, true, "The order store is unavailable");

    private final int status;
    private final boolean retryable;
    private final String title;

    ErrorCode(int status, boolean retryable, String title) {
        this.status = status;
        this.retryable = retryable;
        this.title = title;
    }

    public int status() {
        return status;
    }

    public boolean retryable() {
        return retryable;
    }

    public String title() {
        return title;
    }

    public String typeUri() {
        return "https://errors.ecommerce.local/order/" + name().toLowerCase().replace('_', '-');
    }
}
