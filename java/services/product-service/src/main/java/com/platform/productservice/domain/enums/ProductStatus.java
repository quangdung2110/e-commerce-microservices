package com.platform.productservice.domain.enums;

/**
 * Product lifecycle status.
 */
public enum ProductStatus {
    DRAFT,
    ACTIVE,
    HIDDEN;

    /**
     * Returns true if the status allows direct mutations outside of FSM transitions.
     * ACTIVE products should only be modified through formal FSM transitions.
     */
    public boolean isMutable() {
        return this == DRAFT || this == HIDDEN;
    }
}
