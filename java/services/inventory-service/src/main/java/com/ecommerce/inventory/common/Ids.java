package com.ecommerce.inventory.common;

import com.github.f4b6a3.uuid.UuidCreator;

import java.util.UUID;

/** Time-ordered identifiers for events, movements and Inventory-generated aggregates. */
public final class Ids {

    private Ids() {
    }

    public static UUID uuid() {
        return UuidCreator.getTimeOrderedEpoch();
    }

    public static String newId() {
        return uuid().toString();
    }
}
