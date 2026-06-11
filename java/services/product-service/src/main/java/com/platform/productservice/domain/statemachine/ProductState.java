package com.platform.productservice.domain.statemachine;

import org.springframework.statemachine.StateMachineState;

import java.util.Arrays;

/**
 * Product lifecycle states used by the state machine.
 */
public enum ProductState implements StateMachineState<String, String> {

    DRAFT,
    ACTIVE,
    HIDDEN;

    @Override
    public String getStateId() {
        return name();
    }

    public static ProductState fromString(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("State value must not be blank");
        }
        return Arrays.stream(values())
                .filter(s -> s.name().equalsIgnoreCase(value.trim()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "Unknown product state: " + value + ". Valid values: DRAFT, ACTIVE, HIDDEN"));
    }
}
