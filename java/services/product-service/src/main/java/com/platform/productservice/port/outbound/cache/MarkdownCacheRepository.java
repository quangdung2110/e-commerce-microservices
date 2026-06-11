package com.platform.productservice.port.outbound.cache;

import com.platform.productservice.infrastructure.redis.cache.model.MarkdownCacheEntry;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * Cache repository port for markdown pricing data.
 * Manages active markdown price entries per SKU.
 */
public interface MarkdownCacheRepository {

    /**
     * Finds the active markdown entry for a SKU.
     *
     * @param skuId the SKU ID
     * @return the markdown entry if present
     */
    Optional<MarkdownCacheEntry> findBySkuId(String skuId);

    /**
     * Saves a markdown cache entry with the specified TTL.
     *
     * @param skuId the SKU ID
     * @param entry the markdown entry
     * @param ttl   the time-to-live
     */
    void save(String skuId, MarkdownCacheEntry entry, Duration ttl);

    /**
     * Deletes the markdown entry for a SKU.
     *
     * @param skuId the SKU ID
     */
    void delete(String skuId);

    /**
     * Batch deletes markdown entries for multiple SKUs.
     *
     * @param skuIds list of SKU IDs
     */
    void deleteBySkuIds(List<String> skuIds);
}
