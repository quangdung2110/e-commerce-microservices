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
 * Publishes the current availability snapshot for every readiness registration task, even
 * when no stock mutation follows (ADR-016). A SKU without state is evaluated first.
 */
@Component
public class ReadinessPublisher {

    private static final Logger log = LoggerFactory.getLogger(ReadinessPublisher.class);

    private final AvailabilityStore store;
    private final AvailabilityProjection projection;
    private final TransactionTemplate transactions;
    private final Clock clock;
    private final InventoryProperties.Workers settings;

    public ReadinessPublisher(AvailabilityStore store, AvailabilityProjection projection,
                              TransactionTemplate transactions, Clock clock, InventoryProperties properties) {
        this.store = store;
        this.projection = projection;
        this.transactions = transactions;
        this.clock = clock;
        this.settings = properties.workers();
    }

    /** @return number of tasks completed */
    public int runOnce() {
        Instant now = now();
        List<AvailabilityStore.PublicationTask> tasks = transactions.execute(status ->
                store.claimPublicationTasks(settings.readinessBatchSize(), now, now.plus(settings.claimTtl())));
        int done = 0;
        for (AvailabilityStore.PublicationTask task : tasks == null ? List.<AvailabilityStore.PublicationTask>of()
                : tasks) {
            try {
                transactions.executeWithoutResult(status -> {
                    String trace = "inv-readiness-" + Ids.newId();
                    // evaluate() publishes only while readiness is active; a revoked SKU completes silently.
                    projection.evaluate(task.skuId(), true, trace, trace, now());
                    store.completePublicationTask(task, now());
                });
                done++;
            } catch (RuntimeException e) {
                log.warn("readiness publication failed for sku {}", task.skuId(), e);
            }
        }
        return done;
    }

    private Instant now() {
        return Instant.now(clock).truncatedTo(ChronoUnit.MICROS);
    }
}
