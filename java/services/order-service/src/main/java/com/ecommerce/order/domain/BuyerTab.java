package com.ecommerce.order.domain;

/**
 * One-tab precedence (domain.md §5.6) plus the display status/rank pair of ADR-020.
 * The SQL view {@code v_orders_buyer_tab} implements the same precedence.
 */
public enum BuyerTab {
    TO_PAY(10),
    TO_SHIP(20),
    TO_RECEIVE(30),
    RETURN_REFUND(40),
    COMPLETED(50),
    CANCELLED(60);

    private final int rank;

    BuyerTab(int rank) {
        this.rank = rank;
    }

    public int rank() {
        return rank;
    }

    public static BuyerTab derive(OrderStatus status, boolean anyLineDispatchedOrDelivered) {
        return switch (status) {
            case CREATED, PENDING_PAYMENT, CANCELLING -> TO_PAY;
            case PAID, PROCESSING -> anyLineDispatchedOrDelivered ? TO_RECEIVE : TO_SHIP;
            case COMPLETED -> COMPLETED;
            case CANCELLED -> CANCELLED;
            case RETURN_REQUESTED, REFUNDED, FINANCIAL_RECONCILIATION_REQUIRED -> RETURN_REFUND;
        };
    }

    public static String uiPrimaryStatus(OrderStatus status, BuyerTab tab) {
        return switch (status) {
            case CREATED, PENDING_PAYMENT -> "AWAITING_PAYMENT";
            case CANCELLING -> "CANCELLING";
            case PAID, PROCESSING -> tab == TO_RECEIVE ? "IN_DELIVERY" : "PREPARING";
            case COMPLETED -> "COMPLETED";
            case CANCELLED -> "CANCELLED";
            case RETURN_REQUESTED -> "RETURN_IN_PROGRESS";
            case REFUNDED -> "REFUNDED";
            case FINANCIAL_RECONCILIATION_REQUIRED -> "UNDER_REVIEW";
        };
    }
}
