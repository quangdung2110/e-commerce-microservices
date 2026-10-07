package com.ecommerce.inventory.worker;

import com.ecommerce.inventory.common.Ids;
import com.ecommerce.inventory.config.InventoryProperties;
import com.ecommerce.inventory.service.AvailabilityProjection;
import com.ecommerce.inventory.store.AvailabilityStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * Claims durable dirty SKUs and recomputes each in its own short transaction, outside any
 * command transaction (ADR-016). Leases make a crashed worker's claims reclaimable.
 */
@Component
public class AvailabilityProjector {

    private static final Logger log = LoggerFactory.getLogger(AvailabilityProjector.class);

    private final AvailabilityStore store;
    private final AvailabilityProjection projection;
    private final TransactionTemplate transactions;
    private final Clock clock;
    private final InventoryProperties.Workers settings;

    public AvailabilityProjector(AvailabilityStore store, AvailabilityProjection projection,
                                 TransactionTemplate transactions, Clock clock, InventoryProperties properties) {
        this.store = store;
        this.projection = projection;
        this.transactions = transactions;
        this.clock = clock;
        this.settings = properties.workers();
    }

    /** @return number of SKUs recomputed */
    public int runOnce() {
        Instant now = now();
        List<AvailabilityStore.DirtyClaim> claims = transactions.execute(status ->
                store.claimDirty(settings.projectorBatchSize(), now, now.plus(settings.claimTtl())));
        int done = 0;
        for (AvailabilityStore.DirtyClaim claim : claims == null ? List.<AvailabilityStore.DirtyClaim>of() : claims) {
            try {
                transactions.executeWithoutResult(status -> {
                    projection.evaluate(claim.skuId(), false, traceId(claim.correlationId()),
                            traceId(claim.causationId()), now());
                    store.finishDirty(claim);
                });
                done++;
            } catch (RuntimeException e) {
                // The lease expires and another pass retries; the mark keeps its age.
                log.warn("availability recompute failed for sku {}", claim.skuId(), e);
            }
        }
        return done;
    }

    static String traceId(String value) {
        return value != null && value.length() >= 8 ? value : "inv-availability-" + Ids.newId();
    }

    private Instant now() {
        return Instant.now(clock).truncatedTo(ChronoUnit.MICROS);
    }
}
