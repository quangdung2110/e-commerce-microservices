package com.platform.productservice.domain.statemachine;

import java.util.Arrays;

/**
 * Product lifecycle states used by the state machine.
 */
public enum ProductState {

    DRAFT,
    ACTIVE,
    HIDDEN;

    public String getStateId() {
        return name();
    }

    public static ProductState fromString(String value) {
        if (value == null || isBlank(value)) {
            throw new IllegalArgumentException("State value must not be blank");
        }
        return Arrays.stream(values())
                .filter(s -> s.name().equalsIgnoreCase(value.trim()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "Unknown product state: " + value + ". Valid values: DRAFT, ACTIVE, HIDDEN"));
    }

    private static boolean isBlank(String str) {
        return str == null || str.trim().isEmpty();
    }
}
