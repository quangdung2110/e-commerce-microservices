package com.platform.productservice.port.outbound.cache;

import com.platform.productservice.infrastructure.redis.cache.model.PendingSyncItem;

import java.util.List;

/**
 * Cache repository port for inventory sync operations.
 * Manages the pending inventory sync ZSET queue.
 */
public interface InventorySyncRepository {

    /**
     * Adds an item to the pending sync queue.
     *
     * @param skuId           the SKU ID
     * @param eventTimestamp  the event timestamp (epoch millis, used as ZSET score)
     * @param quantity        the available quantity
     * @param skuStatus       the SKU status
     */
    void addPendingSync(String skuId, long eventTimestamp, int quantity, String skuStatus);

    /**
     * Drains items from the pending sync queue.
     *
     * @param limit maximum number of items to drain
     * @return list of drained pending sync items
     */
    List<PendingSyncItem> drainPendingSync(int limit);

    /**
     * Acknowledges (removes) a single pending sync item from the queue.
     *
     * @param member the ZSET member string
     */
    void acknowledgePendingSync(String member);

    /**
     * Batch acknowledges (removes) pending sync items from the queue.
     *
     * @param members list of ZSET member strings
     */
    void acknowledgePendingSync(List<String> members);

    /**
     * Gets the current count of pending sync items.
     *
     * @return the number of items in the pending queue
     */
    long getPendingSyncCount();

    /**
     * Requeues items back to the pending sync queue (for retry).
     *
     * @param items the items to requeue
     */
    void requeuePendingSync(List<PendingSyncItem> items);
}
