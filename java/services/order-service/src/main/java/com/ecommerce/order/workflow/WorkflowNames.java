package com.ecommerce.order.workflow;

import java.util.UUID;

/** Fixed workflow identities and types (interfaces.md §7, ADR-020 §13). */
public final class WorkflowNames {

    public static final String CHECKOUT_TYPE = "OrderCheckoutWorkflow";
    public static final String REFUND_TYPE = "OrderRefundWorkflow";
    public static final String FINALIZATION_TYPE = "OrderRefundScopeFinalizationWorkflow";
    public static final String RECONCILIATION_TYPE = "OrderFinancialReconciliationWorkflow";

    /** Single signal method name shared by every Order workflow. */
    public static final String MESSAGE_SIGNAL = "onMessage";

    private WorkflowNames() {
    }

    public static String checkout(String checkoutToken) {
        return "order-checkout-" + checkoutToken;
    }

    public static String buyerRefund(long orderId) {
        return "order-refund-" + orderId;
    }

    public static String adminRefund(UUID attemptId) {
        return "order-admin-refund-" + attemptId;
    }

    public static String finalization(long orderId, int generation) {
        return "order-refund-scope-finalization-" + orderId + "-" + generation;
    }

    public static String reconciliation(long orderId) {
        return "order-financial-reconciliation-" + orderId;
    }
}
