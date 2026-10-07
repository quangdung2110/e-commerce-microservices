package com.ecommerce.inventory.service;

import com.ecommerce.inventory.store.AvailabilityStore;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Optional;

/**
 * Derives the Inventory-owned per-SKU boolean (ADR-016) and writes its Search fact. Must run
 * inside a transaction; the per-SKU advisory lock serializes projector, fan-out and
 * readiness work for the same SKU.
 */
@Service
public class AvailabilityProjection {

    private final AvailabilityStore store;
    private final EventFactory events;

    public AvailabilityProjection(AvailabilityStore store, EventFactory events) {
        this.store = store;
        this.events = events;
    }

    /**
     * Recomputes one SKU. A new state or a changed boolean increments
     * {@code availability_revision}; a fact is written only when Search readiness is active,
     * and then for a change, or for every readiness publication ({@code forcePublish}).
     *
     * @return the state after evaluation
     */
    public AvailabilityStore.State evaluate(String skuId, boolean forcePublish, String correlationId,
                                            String causationId, Instant now) {
        store.lockSku(skuId);
        boolean inStock = store.computeInStock(skuId);
        Optional<AvailabilityStore.State> current = store.findState(skuId);
        AvailabilityStore.State next;
        boolean changed;
        if (current.isEmpty()) {
            next = new AvailabilityStore.State(skuId, inStock, 1, now);
            store.insertState(next);
            changed = true;
        } else if (current.get().inStock() != inStock) {
            next = new AvailabilityStore.State(skuId, inStock, current.get().revision() + 1, now);
            store.updateState(next);
            changed = true;
        } else {
            next = new AvailabilityStore.State(skuId, inStock, current.get().revision(), now);
            store.updateState(next);
            changed = false;
        }
        if ((changed || forcePublish) && store.findReadiness(skuId).map(AvailabilityStore.Readiness::active)
                .orElse(false)) {
            events.skuAvailabilityChanged(skuId, next.inStock(), next.revision(), next.evaluatedAt(),
                    correlationId, causationId, now);
        }
        return next;
    }
}
