package com.ecommerce.order.domain;

import java.util.Collection;

/** Authoritative per-line fulfillment FSM (domain.md §5.2). */
public enum FulfillmentStatus {
    PROCESSING,
    DISPATCHED,
    DELIVERED;

    /** Derives the delivery-unit projection from its child lines; never a mutation source. */
    public static String deriveItemStatus(Collection<FulfillmentStatus> lines) {
        long delivered = lines.stream().filter(s -> s == DELIVERED).count();
        long moved = lines.stream().filter(s -> s != PROCESSING).count();
        int total = lines.size();
        if (total > 0 && delivered == total) {
            return "DELIVERED";
        }
        if (delivered > 0) {
            return "PARTIALLY_DELIVERED";
        }
        if (total > 0 && moved == total) {
            return "DISPATCHED";
        }
        if (moved > 0) {
            return "PARTIALLY_DISPATCHED";
        }
        return "PROCESSING";
    }
}
