package com.ecommerce.order.domain;

/** Async operation resource vocabulary (public OpenAPI OperationType/OperationStatus). */
public final class OperationTypes {

    private OperationTypes() {
    }

    public enum Type {
        BUYER_CANCELLATION,
        ADMIN_CANCELLATION,
        BUYER_RETURN,
        ADMIN_PRE_DISPATCH_REFUND,
        PAYMENT_ATTEMPT
    }

    public enum Status {
        ACCEPTED,
        RUNNING,
        SUCCEEDED,
        REJECTED,
        FAILED,
        MANUAL_REVIEW_REQUIRED;

        public boolean terminal() {
            return this == SUCCEEDED || this == REJECTED || this == FAILED;
        }
    }
}
