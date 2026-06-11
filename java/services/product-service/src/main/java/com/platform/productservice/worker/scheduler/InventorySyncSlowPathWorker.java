package com.platform.productservice.worker.scheduler;

import com.platform.productservice.infrastructure.redis.cache.model.PendingSyncItem;
import com.platform.productservice.port.outbound.cache.InventorySyncRepository;
import com.platform.productservice.service.InventorySyncOrchestrator;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Phase 2: Slow Path Scheduled Worker for inventory sync.
 *
 * <p>This worker drains items from the Redis pending sync ZSET and syncs
 * them to PostgreSQL in batches. It runs on a fixed 500ms interval.</p>
 *
 * <p>Flow per run:</p>
 * <ol>
 *   <li>Drain ZSET: pop up to 100 items</li>
 *   <li>Group by productId</li>
 *   <li>For each product: call orchestrator (handles DB + FSM)</li>
 *   <li>On OptimisticLockingFailureException: requeue</li>
 *   <li>On success: items already acknowledged by orchestrator</li>
 * </ol>
 *
 * <p>Key design decisions:</p>
 * <ul>
 *   <li>Only acknowledges items AFTER orchestrator commits successfully</li>
 *   <li>OptimisticLockingFailureException triggers requeue (not immediate retry)</li>
 *   <li>Metrics track processed count, batch count, error count</li>
 *   <li>Refreshes pending queue size gauge on each run</li>
 * </ul>
 */
@Component
public class InventorySyncSlowPathWorker {

    private static final Logger log = LoggerFactory.getLogger(InventorySyncSlowPathWorker.class);

    private static final int DRAIN_BATCH_SIZE = 100;

    private final InventorySyncRepository inventorySyncRepository;
    private final InventorySyncOrchestrator inventorySyncOrchestrator;
    private final InventorySyncRequeueHandler requeueHandler;
    private final InventorySyncMetrics metrics;

    @Value("${app.inventory-sync.interval-ms:500}")
    private long intervalMs;

    public InventorySyncSlowPathWorker(
            InventorySyncRepository inventorySyncRepository,
            InventorySyncOrchestrator inventorySyncOrchestrator,
            InventorySyncRequeueHandler requeueHandler,
            InventorySyncMetrics metrics) {
        this.inventorySyncRepository = inventorySyncRepository;
        this.inventorySyncOrchestrator = inventorySyncOrchestrator;
        this.requeueHandler = requeueHandler;
        this.metrics = metrics;
    }

    @Scheduled(fixedDelayString = "${app.inventory-sync.interval-ms:500}")
    public void run() {
        Timer.Sample timerSample = metrics.startPhase2Timer();

        try {
            List<PendingSyncItem> items = inventorySyncRepository.drainPendingSync(DRAIN_BATCH_SIZE);

            if (items == null || items.isEmpty()) {
                metrics.refreshPendingQueueSize();
                return;
            }

            log.info("InventorySyncSlowPathWorker: drained {} items from pending queue", items.size());

            Map<String, List<PendingSyncItem>> itemsByProductId = items.stream()
                    .collect(Collectors.groupingBy(PendingSyncItem::getProductId));

            int processedCount = 0;
            int errorCount = 0;
            int requeuedCount = 0;

            for (Map.Entry<String, List<PendingSyncItem>> entry : itemsByProductId.entrySet()) {
                String productId = entry.getKey();
                List<PendingSyncItem> productItems = entry.getValue();

                try {
                    List<String> acknowledged = inventorySyncOrchestrator.syncBatch(
                            Map.of(productId, productItems));

                    if (!acknowledged.isEmpty()) {
                        processedCount += productItems.size();
                        log.debug("InventorySyncSlowPathWorker: processed {} items for product {}",
                                productItems.size(), productId);
                    }

                } catch (ObjectOptimisticLockingFailureException e) {
                    log.warn("InventorySyncSlowPathWorker: optimistic lock conflict for product {}, requeuing {} items",
                            productId, productItems.size());
                    requeueHandler.requeueWithDelay(productItems, intervalMs);
                    requeuedCount += productItems.size();
                    errorCount++;

                } catch (Exception e) {
                    log.error("InventorySyncSlowPathWorker: failed to process product {}: {}",
                            productId, e.getMessage(), e);
                    requeueHandler.requeueWithDelay(productItems, intervalMs);
                    requeuedCount += productItems.size();
                    errorCount++;
                }
            }

            if (processedCount > 0) {
                metrics.recordProcessed(processedCount);
            }
            if (errorCount > 0) {
                metrics.recordError(errorCount);
            }
            if (requeuedCount > 0) {
                metrics.recordRequeued(requeuedCount);
            }

            log.info("InventorySyncSlowPathWorker: batch complete — processed={}, requeued={}, errors={}, products={}",
                    processedCount, requeuedCount, errorCount, itemsByProductId.size());

        } catch (Exception e) {
            log.error("InventorySyncSlowPathWorker: unexpected error in scheduled run: {}", e.getMessage(), e);
            metrics.recordError();
        } finally {
            metrics.stopPhase2Timer(timerSample);
            metrics.refreshPendingQueueSize();
        }
    }
}
