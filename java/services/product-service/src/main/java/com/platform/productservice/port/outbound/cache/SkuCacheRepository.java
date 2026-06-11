package com.platform.productservice.port.outbound.cache;

import com.platform.productservice.infrastructure.redis.cache.model.SkuCacheEntry;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Cache repository port for SKU data.
 * Provides read/write access to SKU cache entries in Redis.
 */
public interface SkuCacheRepository {

    /**
     * Finds a SKU cache entry by its ID.
     *
     * @param skuId the SKU ID
     * @return the cache entry if present
     */
    Optional<SkuCacheEntry> findById(String skuId);

    /**
     * Saves a SKU cache entry with the specified TTL.
     *
     * @param skuId the SKU ID
     * @param entry the cache entry
     * @param ttl   the time-to-live
     */
    void save(String skuId, SkuCacheEntry entry, Duration ttl);

    /**
     * Deletes a SKU cache entry.
     *
     * @param skuId the SKU ID
     */
    void delete(String skuId);

    /**
     * Deletes SKU cache entries matching a pattern.
     *
     * @param pattern Redis key pattern (e.g., "product:sku:*")
     */
    void deleteByPattern(String pattern);

    /**
     * Batch reads SKU cache entries by their IDs.
     * Uses MGET/JSON.MGET for a single round-trip.
     *
     * @param skuIds list of SKU IDs
     * @return list of cache entries (may contain nulls for cache misses)
     */
    List<SkuCacheEntry> findByIds(List<String> skuIds);

    /**
     * Batch reads SKU cache entries as a map.
     * Uses MGET/JSON.MGET for a single round-trip.
     *
     * @param skuIds list of SKU IDs
     * @return map of SKU ID to cache entry
     */
    Map<String, SkuCacheEntry> findByIdsAsMap(List<String> skuIds);

    /**
     * Batch writes SKU cache entries using Redis Pipeline.
     * Uses a single round-trip for all SET + EXPIRE commands to minimize network I/O.
     *
     * @param entries list of cache entries to write
     * @param ttl     the time-to-live applied to all entries
     */
    void saveBatch(List<SkuCacheEntry> entries, Duration ttl);
}
