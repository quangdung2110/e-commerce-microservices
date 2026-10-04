package com.ecommerce.inventory.worker;

import com.ecommerce.inventory.config.InventoryProperties;
import com.ecommerce.inventory.store.AvailabilityStore;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * Checkpointed scan of distinct SKUs at a warehouse after an active/outage change
 * (data.md §5). Each batch marks its SKUs dirty, aged from the job's creation (ADR-025),
 * and advances the cursor in one transaction; the projector recomputes them against all
 * warehouses.
 */
@Component
public class WarehouseFanoutWorker {

    private final AvailabilityStore store;
    private final TransactionTemplate transactions;
    private final Clock clock;
    private final InventoryProperties.Workers settings;

    public WarehouseFanoutWorker(AvailabilityStore store, TransactionTemplate transactions, Clock clock,
                                 InventoryProperties properties) {
        this.store = store;
        this.transactions = transactions;
        this.clock = clock;
        this.settings = properties.workers();
    }

    /** @return true when a batch was processed */
    public boolean runOnce() {
        Boolean worked = transactions.execute(status -> store.claimFanoutJob().map(job -> {
            List<String> skus = store.skusAtWarehouse(job.warehouseId(), job.cursorSkuId(), settings.fanoutBatchSize());
            for (String sku : skus) {
                store.markDirty(sku, job.createdAt(), job.correlationId(), job.causationId());
            }
            boolean finished = skus.size() < settings.fanoutBatchSize();
            String cursor = skus.isEmpty() ? job.cursorSkuId() : skus.get(skus.size() - 1);
            store.advanceFanout(job.fanoutJobId(), cursor,
                    finished ? Instant.now(clock).truncatedTo(ChronoUnit.MICROS) : null);
            return true;
        }).orElse(false));
        return Boolean.TRUE.equals(worked);
    }
}
