package com.platform.productservice.domain.statemachine;

import java.util.Arrays;

/**
 * Product lifecycle events that trigger state transitions.
 */
public enum ProductEvent {

    SUBMIT,
    HIDE,
    SHOW,
    AUTO_HIDE_OUT_OF_STOCK,
    AUTO_REACTIVATE_IN_STOCK;

    public String getEventId() {
        return name();
    }

    /**
     * Returns true if this is an admin-initiated event.
     */
    public boolean isAdminEvent() {
        return this == SUBMIT || this == HIDE || this == SHOW;
    }

    /**
     * Returns true if this is a system-initiated event (inventory-triggered).
     */
    public boolean isSystemEvent() {
        return this == AUTO_HIDE_OUT_OF_STOCK || this == AUTO_REACTIVATE_IN_STOCK;
    }

    public static ProductEvent fromString(String value) {
        if (value == null || isBlank(value)) {
            throw new IllegalArgumentException("Event value must not be blank");
        }
        return Arrays.stream(values())
                .filter(e -> e.name().equalsIgnoreCase(value.trim()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "Unknown product event: " + value + ". Valid values: " +
                        Arrays.toString(values())));
    }

    private static boolean isBlank(String str) {
        return str == null || str.trim().isEmpty();
    }
}
