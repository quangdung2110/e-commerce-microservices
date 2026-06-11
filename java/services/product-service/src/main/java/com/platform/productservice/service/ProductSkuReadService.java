package com.platform.productservice.service;

import com.platform.productservice.domain.entity.Product;
import com.platform.productservice.domain.entity.Sku;
import com.platform.productservice.domain.vo.ProductId;
import com.platform.productservice.domain.vo.SkuId;
import com.platform.productservice.infrastructure.persistence.outbound.mapper.ProductJpaMapper;
import com.platform.productservice.infrastructure.persistence.outbound.mapper.SkuJpaMapper;
import com.platform.productservice.infrastructure.redis.RedisKeyBuilder;
import com.platform.productservice.infrastructure.redis.cache.model.SkuCacheEntry;
import com.platform.productservice.port.outbound.cache.DistributedLockRepository;
import com.platform.productservice.port.outbound.cache.SkuCacheRepository;
import com.platform.productservice.port.outbound.database.ProductRepository;
import com.platform.productservice.port.outbound.database.SkuRepository;
import io.lettuce.core.RedisException;
import io.lettuce.core.api.sync.RedisCommands;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static com.platform.productservice.common.constants.ServiceConstants.*;

/**
 * Read service for SKU data using cache-aside pattern with Probabilistic Early Expiration (PER).
 * <p>
 * Flow for single SKU read:
 * 1. Check Redis cache for SKU entry
 * 2. PER: if TTL < 5%, refresh from DB and overwrite cache
 * 3. Cache miss: acquire distributed lock, query DB, warm cache, release lock
 * <p>
 * Flow for batch reads:
 * 1. Redis MGET all SKU IDs in single round-trip
 * 2. Identify cache misses
 * 3. Batch query DB for misses
 * 4. Warm cache with pipelining
 * 5. Merge and return results
 */
@Service
public class ProductSkuReadService {

    private static final Logger log = LoggerFactory.getLogger(ProductSkuReadService.class);

    private static final double PER_THRESHOLD = 0.05;
    private static final int SKU_CACHE_TTL_SECONDS = 3600;

    private final SkuCacheRepository skuCacheRepository;
    private final SkuRepository skuRepository;
    private final ProductRepository productRepository;
    private final SkuJpaMapper skuJpaMapper;
    private final ProductJpaMapper productJpaMapper;
    private final DistributedLockRepository distributedLockRepository;
    private final RedisKeyBuilder redisKeyBuilder;
    private final RedisConnectionFactory redisConnectionFactory;

    public ProductSkuReadService(
            SkuCacheRepository skuCacheRepository,
            SkuRepository skuRepository,
            ProductRepository productRepository,
            SkuJpaMapper skuJpaMapper,
            ProductJpaMapper productJpaMapper,
            DistributedLockRepository distributedLockRepository,
            RedisKeyBuilder redisKeyBuilder,
            RedisConnectionFactory redisConnectionFactory) {
        this.skuCacheRepository = skuCacheRepository;
        this.skuRepository = skuRepository;
        this.productRepository = productRepository;
        this.skuJpaMapper = skuJpaMapper;
        this.productJpaMapper = productJpaMapper;
        this.distributedLockRepository = distributedLockRepository;
        this.redisKeyBuilder = redisKeyBuilder;
        this.redisConnectionFactory = redisConnectionFactory;
    }

    /**
     * Retrieves a SKU cache entry by ID using cache-aside pattern with PER.
     *
     * @param skuId the SKU ID
     * @return the cache entry if found, empty otherwise
     */
    @Transactional(readOnly = true)
    public Optional<SkuCacheEntry> getById(String skuId) {
        if (skuId == null || skuId.isBlank()) {
            return Optional.empty();
        }

        Optional<SkuCacheEntry> cached = skuCacheRepository.findById(skuId);
        if (cached.isPresent()) {
            SkuCacheEntry entry = cached.get();

            if (shouldRefreshEarly(entry, skuId)) {
                log.debug("PER triggered for skuId={}", skuId);
                refreshFromDbAndCache(skuId);
            }

            return Optional.of(entry);
        }

        return populateFromDbWithLock(skuId);
    }

    /**
     * Retrieves multiple SKU cache entries by IDs using batch cache-aside pattern.
     *
     * @param skuIds list of SKU IDs
     * @return map of SKU ID to cache entry (only found entries)
     */
    @Transactional(readOnly = true)
    public Map<String, SkuCacheEntry> getByIds(List<String> skuIds) {
        if (skuIds == null || skuIds.isEmpty()) {
            return Map.of();
        }

        Map<String, SkuCacheEntry> result = new HashMap<>();

        List<SkuCacheEntry> cached = skuCacheRepository.findByIds(skuIds);
        List<String> missedSkuIds = new ArrayList<>();

        for (int i = 0; i < skuIds.size(); i++) {
            String skuId = skuIds.get(i);
            SkuCacheEntry entry = cached.get(i);
            if (entry != null) {
                result.put(skuId, entry);
            } else {
                missedSkuIds.add(skuId);
            }
        }

        if (!missedSkuIds.isEmpty()) {
            Map<String, SkuCacheEntry> dbEntries = populateBatchFromDb(missedSkuIds);
            result.putAll(dbEntries);
        }

        return result;
    }

    private boolean shouldRefreshEarly(SkuCacheEntry entry, String skuId) {
        try {
            String key = redisKeyBuilder.sku(skuId);
            RedisCommands<String, String> commands = redisConnectionFactory.getConnection().sync();
            Long ttl = commands.ttl(key);

            if (ttl == null || ttl < 0) {
                return true;
            }

            return ttl < (SKU_CACHE_TTL_SECONDS * PER_THRESHOLD);
        } catch (RedisException e) {
            log.warn("Failed to check TTL for PER check: skuId={}", skuId, e);
            return false;
        }
    }

    private void refreshFromDbAndCache(String skuId) {
        try {
            SkuCacheEntry refreshed = queryDbForSku(skuId);
            if (refreshed != null) {
                skuCacheRepository.save(skuId, refreshed, Duration.ofSeconds(SKU_CACHE_TTL_SECONDS));
                log.debug("PER refresh completed for skuId={}", skuId);
            }
        } catch (Exception e) {
            log.warn("PER refresh failed for skuId={}: {}", skuId, e.getMessage());
        }
    }

    private Optional<SkuCacheEntry> populateFromDbWithLock(String skuId) {
        String lockKey = "sku:cache:populate:" + skuId;

        try {
            SkuCacheEntry entry = distributedLockRepository.executeWithLock(
                    lockKey,
                    Duration.ofSeconds(LOCK_TIMEOUT_SECONDS),
                    () -> {
                        Optional<SkuCacheEntry> recheck = skuCacheRepository.findById(skuId);
                        if (recheck.isPresent()) {
                            return recheck.get();
                        }

                        SkuCacheEntry fresh = queryDbForSku(skuId);
                        if (fresh != null) {
                            skuCacheRepository.save(skuId, fresh, Duration.ofSeconds(SKU_CACHE_TTL_SECONDS));
                        }
                        return fresh;
                    }
            );

            return Optional.ofNullable(entry);
        } catch (Exception e) {
            log.error("Failed to populate cache for skuId={}: {}", skuId, e.getMessage(), e);

            SkuCacheEntry direct = queryDbForSku(skuId);
            return Optional.ofNullable(direct);
        }
    }

    private Map<String, SkuCacheEntry> populateBatchFromDb(List<String> skuIds) {
        Map<String, SkuCacheEntry> result = new HashMap<>();

        if (skuIds.isEmpty()) {
            return result;
        }

        List<SkuId> domainIds = skuIds.stream()
                .map(SkuId::of)
                .toList();

        List<Sku> skus = skuRepository.findByIds(domainIds);
        if (skus.isEmpty()) {
            return result;
        }

        List<ProductId> productIds = skus.stream()
                .filter(s -> s.getProductId() != null)
                .map(Sku::getProductId)
                .distinct()
                .toList();

        Map<ProductId, Product> productMap = new HashMap<>();
        if (!productIds.isEmpty()) {
            List<Product> products = productRepository.findByIdIn(productIds);
            for (Product product : products) {
                productMap.put(product.getId(), product);
            }
        }

        List<SkuCacheEntry> entriesToCache = new ArrayList<>();

        for (Sku sku : skus) {
            Product product = productMap.get(sku.getProductId());
            SkuCacheEntry entry = SkuCacheEntry.fromDomain(product, sku);

            if (!sku.isDeleted() && (product == null || !product.isDeleted())) {
                entriesToCache.add(entry);
            }

            result.put(sku.getId().getValue(), entry);
        }

        if (!entriesToCache.isEmpty()) {
            warmCacheBatch(entriesToCache);
        }

        return result;
    }

    private SkuCacheEntry queryDbForSku(String skuId) {
        try {
            SkuId id = SkuId.of(skuId);
            return skuRepository.findByIdAndNotDeleted(id)
                    .map(sku -> {
                        Product product = null;
                        if (sku.getProductId() != null) {
                            product = productRepository.findById(sku.getProductId()).orElse(null);
                        }
                        return SkuCacheEntry.fromDomain(product, sku);
                    })
                    .orElse(null);
        } catch (Exception e) {
            log.error("Failed to query DB for skuId={}: {}", skuId, e.getMessage(), e);
            return null;
        }
    }

    private void warmCacheBatch(List<SkuCacheEntry> entries) {
        if (entries.isEmpty()) {
            return;
        }

        try {
            skuCacheRepository.saveBatch(entries, Duration.ofSeconds(SKU_CACHE_TTL_SECONDS));
            log.debug("Warm cache batch completed via pipeline: count={}", entries.size());
        } catch (Exception e) {
            log.warn("Redis pipeline failed during warm cache batch, falling back to sequential writes: count={}", entries.size(), e);
            warmCacheBatchSequential(entries);
        }
    }

    private void warmCacheBatchSequential(List<SkuCacheEntry> entries) {
        for (SkuCacheEntry entry : entries) {
            try {
                skuCacheRepository.save(
                        entry.getSkuId(),
                        entry,
                        Duration.ofSeconds(SKU_CACHE_TTL_SECONDS)
                );
            } catch (Exception e) {
                log.warn("Failed to warm cache for skuId={}: {}", entry.getSkuId(), e.getMessage());
            }
        }
    }
}
