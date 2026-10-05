package com.ecommerce.order.domain;

/**
 * Order-owned cancellation reasons (INV-ORD-034) and their fixed downstream mappings:
 * Inventory release reason (Inventory ADR-021), Payment cancel reason and the
 * ORDER_CANCELLED display reason (ADR-020, interfaces.md §3/§5.1).
 */
public enum CancelReason {
    USER_CANCEL("BUYER_CANCELLED", "BUYER_CANCELLED"),
    ADMIN_FORCE_CANCEL("ADMIN_CANCELLED", "ORDER_CANCELLED_PRE_CAPTURE"),
    PAYMENT_TIMEOUT("RESERVATION_EXPIRED", "PAYMENT_TIMEOUT"),
    PAYMENT_FAILED("PAYMENT_FAILED", "PAYMENT_TIMEOUT"),
    PRE_PAYMENT_SAGA_FAILED("PRE_PAYMENT_SAGA_FAILED", "ORDER_CANCELLED_PRE_CAPTURE");

    /** Detail recorded with PRE_PAYMENT_SAGA_FAILED when Inventory rejected availability. */
    public static final String DETAIL_INVENTORY_REJECTED = "INVENTORY_RESERVATION_REJECTED";

    private final String inventoryReleaseReason;
    private final String paymentCancelReason;

    CancelReason(String inventoryReleaseReason, String paymentCancelReason) {
        this.inventoryReleaseReason = inventoryReleaseReason;
        this.paymentCancelReason = paymentCancelReason;
    }

    public String inventoryReleaseReason() {
        return inventoryReleaseReason;
    }

    public String paymentCancelReason() {
        return paymentCancelReason;
    }

    public String notificationDisplayReason(String detail) {
        return switch (this) {
            case USER_CANCEL -> "buyer";
            case ADMIN_FORCE_CANCEL -> "admin";
            case PAYMENT_TIMEOUT, PAYMENT_FAILED -> "payment_timeout";
            case PRE_PAYMENT_SAGA_FAILED -> DETAIL_INVENTORY_REJECTED.equals(detail) ? "out_of_stock" : "checkout_failed";
        };
    }

    /** Deadline reason: FAILED last attempt means the buyer tried and failed (INV-ORD-034). */
    public static CancelReason forDeadline(String lastAttemptTerminalStatus) {
        return "FAILED".equals(lastAttemptTerminalStatus) ? PAYMENT_FAILED : PAYMENT_TIMEOUT;
    }

    /** Reason for a Payment terminal non-capture fact on the active attempt. */
    public static CancelReason forTerminalNonCapture(String terminalStatus, String reasonCode) {
        if ("EXPIRED".equals(terminalStatus)) {
            return PAYMENT_TIMEOUT;
        }
        return "PROVIDER_CANCELED".equals(reasonCode) ? PAYMENT_FAILED : PAYMENT_TIMEOUT;
    }
}
