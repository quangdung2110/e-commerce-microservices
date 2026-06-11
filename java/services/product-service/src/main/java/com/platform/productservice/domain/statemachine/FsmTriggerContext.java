package com.platform.productservice.domain.statemachine;

import com.platform.productservice.domain.statemachine.ProductEvent;

/**
 * Context object carrying FSM trigger metadata for audit and logging purposes.
 */
public final class FsmTriggerContext {

    private final ProductEvent fsmTrigger;
    private final String reason;
    private final String changedBy;

    private FsmTriggerContext(ProductEvent fsmTrigger, String reason, String changedBy) {
        this.fsmTrigger = fsmTrigger;
        this.reason = reason;
        this.changedBy = changedBy;
    }

    /**
     * Creates a context for an auto-hide trigger.
     */
    public static FsmTriggerContext forAutoHide() {
        return new FsmTriggerContext(
                ProductEvent.AUTO_HIDE_OUT_OF_STOCK,
                "All SKUs are out of stock",
                "SYSTEM"
        );
    }

    /**
     * Creates a context for an auto-reactivate trigger.
     */
    public static FsmTriggerContext forAutoReactivate() {
        return new FsmTriggerContext(
                ProductEvent.AUTO_REACTIVATE_IN_STOCK,
                "Stock returned while product was auto-hidden (not admin-hidden)",
                "SYSTEM"
        );
    }

    public ProductEvent getFsmTrigger() {
        return fsmTrigger;
    }

    public String getReason() {
        return reason;
    }

    public String getChangedBy() {
        return changedBy;
    }
}
