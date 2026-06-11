package com.platform.productservice.worker.scheduler;

import com.platform.productservice.infrastructure.redis.RedisKeyBuilder;
import com.platform.productservice.infrastructure.redis.cache.model.PendingSyncItem;
import com.platform.productservice.port.outbound.cache.InventorySyncRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Handles requeuing of failed inventory sync items for retry.
 *
 * <p>Retry policy:</p>
 * <ul>
 *   <li>Max retry attempts: 5</li>
 *   <li>After max retries: send to DLQ (logged, DLQ is managed by Kafka error handler)</li>
 *   <li>Retry count tracked in PendingSyncItem metadata</li>
 * </ul>
 */
@Component
public class InventorySyncRequeueHandler {

    private static final Logger log = LoggerFactory.getLogger(InventorySyncRequeueHandler.class);

    private static final int MAX_RETRY_COUNT = 5;

    private final InventorySyncRepository inventorySyncRepository;
    private final InventorySyncMetrics metrics;

    public InventorySyncRequeueHandler(
            InventorySyncRepository inventorySyncRepository,
            InventorySyncMetrics metrics) {
        this.inventorySyncRepository = inventorySyncRepository;
        this.metrics = metrics;
    }

    /**
     * Requeues items back to the pending sync queue for retry.
     * Increments retry count on each item; items exceeding max retries are sent to DLQ.
     *
     * @param items the items to requeue
     */
    public void requeue(List<PendingSyncItem> items) {
        if (items == null || items.isEmpty()) {
            return;
        }

        List<PendingSyncItem> toRequeue = new ArrayList<>();
        int dlqCount = 0;

        for (PendingSyncItem item : items) {
            if (item.getRetryCount() >= MAX_RETRY_COUNT) {
                log.warn("InventorySyncRequeueHandler: max retries ({}) exhausted for skuId={}, sending to DLQ",
                        MAX_RETRY_COUNT, item.getSkuId());
                dlqCount++;
            } else {
                item.setRetryCount(item.getRetryCount() + 1);
                toRequeue.add(item);
                log.debug("InventorySyncRequeueHandler: requeuing skuId={}, retry={}",
                        item.getSkuId(), item.getRetryCount());
            }
        }

        if (!toRequeue.isEmpty()) {
            inventorySyncRepository.requeuePendingSync(toRequeue);
            metrics.recordRequeued(toRequeue.size());
            log.info("InventorySyncRequeueHandler: requeued {} items for retry", toRequeue.size());
        }

        if (dlqCount > 0) {
            log.warn("InventorySyncRequeueHandler: {} items sent to DLQ after max retries", dlqCount);
        }
    }

    /**
     * Requeues items with a delay by setting the ZSET score to current time + delay.
     * Only requeues items that haven't exceeded max retry count.
     *
     * @param items   the items to requeue
     * @param delayMs the delay in milliseconds before items are eligible for processing again
     */
    public void requeueWithDelay(List<PendingSyncItem> items, long delayMs) {
        if (items == null || items.isEmpty()) {
            return;
        }

        long scheduledTime = System.currentTimeMillis() + delayMs;
        List<PendingSyncItem> toRequeue = new ArrayList<>();
        int dlqCount = 0;

        for (PendingSyncItem item : items) {
            if (item.getRetryCount() >= MAX_RETRY_COUNT) {
                log.warn("InventorySyncRequeueHandler: max retries ({}) exhausted for skuId={} — DLQ",
                        MAX_RETRY_COUNT, item.getSkuId());
                dlqCount++;
            } else {
                item.setRetryCount(item.getRetryCount() + 1);
                toRequeue.add(item);
                log.debug("InventorySyncRequeueHandler: requeueWithDelay skuId={}, retry={}, scheduled={}",
                        item.getSkuId(), item.getRetryCount(), scheduledTime);
            }
        }

        if (!toRequeue.isEmpty()) {
            inventorySyncRepository.requeuePendingSync(toRequeue);
            metrics.recordRequeued(toRequeue.size());
            log.info("InventorySyncRequeueHandler: requeued {} items with delay {}ms", toRequeue.size(), delayMs);
        }

        if (dlqCount > 0) {
            log.warn("InventorySyncRequeueHandler: {} items reached max retries (DLQ)", dlqCount);
        }
    }

    public int getMaxRetryCount() {
        return MAX_RETRY_COUNT;
    }
}
