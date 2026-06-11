package com.platform.productservice.infrastructure.redis.cache.adapter;

import com.platform.productservice.infrastructure.redis.RedisKeyBuilder;
import com.platform.productservice.infrastructure.redis.cache.model.MarkdownCacheEntry;
import com.platform.productservice.port.outbound.cache.MarkdownCacheRepository;
import io.lettuce.core.RedisException;
import io.lettuce.core.api.sync.RedisCommands;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Repository;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * Redis adapter implementing MarkdownCacheRepository.
 * Uses StringRedisSerializer for markdown cache operations.
 */
@Repository
public class MarkdownCacheRepositoryImpl implements MarkdownCacheRepository {

    private static final Logger log = LoggerFactory.getLogger(MarkdownCacheRepositoryImpl.class);

    private final RedisTemplate<String, String> redisTemplate;
    private final RedisKeyBuilder keyBuilder;
    private final RedisCommands<String, String> redisCommands;

    public MarkdownCacheRepositoryImpl(
            RedisTemplate<String, String> redisTemplate,
            RedisKeyBuilder keyBuilder,
            RedisCommands<String, String> redisCommands) {
        this.redisTemplate = redisTemplate;
        this.keyBuilder = keyBuilder;
        this.redisCommands = redisCommands;
    }

    @Override
    public Optional<MarkdownCacheEntry> findBySkuId(String skuId) {
        try {
            String key = keyBuilder.markdownActive(skuId);
            String json = redisCommands.get(key);
            if (json == null || json.isBlank()) {
                return Optional.empty();
            }
            return Optional.ofNullable(MarkdownCacheEntry.fromJson(json));
        } catch (RedisException e) {
            log.error("Redis error finding markdown cache entry: skuId={}", skuId, e);
            return Optional.empty();
        }
    }

    @Override
    public void save(String skuId, MarkdownCacheEntry entry, Duration ttl) {
        try {
            String key = keyBuilder.markdownActive(skuId);
            String json = entry.toJson();
            if (ttl == null || ttl.isZero() || ttl.isNegative()) {
                long remainingSeconds = entry.remainingTtlSeconds();
                if (remainingSeconds > 0) {
                    ttl = Duration.ofSeconds(remainingSeconds);
                } else {
                    log.warn("No TTL provided and entry has no valid expiry, using default TTL: skuId={}", skuId);
                    ttl = Duration.ofHours(1);
                }
            }
            redisTemplate.opsForValue().set(key, json, ttl);
        } catch (RedisException e) {
            log.error("Redis error saving markdown cache entry: skuId={}", skuId, e);
        }
    }

    @Override
    public void delete(String skuId) {
        try {
            String key = keyBuilder.markdownActive(skuId);
            redisCommands.del(key);
        } catch (RedisException e) {
            log.error("Redis error deleting markdown cache entry: skuId={}", skuId, e);
        }
    }

    @Override
    public void deleteBySkuIds(List<String> skuIds) {
        if (skuIds == null || skuIds.isEmpty()) {
            return;
        }
        try {
            String[] keys = skuIds.stream()
                    .map(keyBuilder::markdownActive)
                    .toArray(String[]::new);
            redisTemplate.executePipelined((connection) -> {
                for (String key : keys) {
                    connection.del(key.getBytes());
                }
                return null;
            });
            log.info("Batch deleted {} markdown cache entries", skuIds.size());
        } catch (RedisException e) {
            log.error("Redis error batch deleting markdown cache entries: count={}", skuIds.size(), e);
        }
    }
}
