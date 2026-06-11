package com.platform.productservice.infrastructure.redis.cache.adapter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.productservice.infrastructure.redis.RedisKeyBuilder;
import com.platform.productservice.infrastructure.redis.cache.model.SkuCacheEntry;
import com.platform.productservice.port.outbound.cache.SkuCacheRepository;
import io.lettuce.core.RedisException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Repository;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Redis adapter implementing SkuCacheRepository.
 * Uses Redis GET/MGET for SKU cache operations (plain JSON storage).
 */
@Repository
public class SkuCacheRepositoryImpl implements SkuCacheRepository {

    private static final Logger log = LoggerFactory.getLogger(SkuCacheRepositoryImpl.class);

    private final RedisTemplate<String, String> redisTemplate;
    private final RedisKeyBuilder keyBuilder;
    private final ObjectMapper objectMapper;

    public SkuCacheRepositoryImpl(
            RedisTemplate<String, String> redisTemplate,
            RedisKeyBuilder keyBuilder,
            ObjectMapper objectMapper) {
        this.redisTemplate = redisTemplate;
        this.keyBuilder = keyBuilder;
        this.objectMapper = objectMapper;
    }

    @Override
    public Optional<SkuCacheEntry> findById(String skuId) {
        try {
            String key = keyBuilder.sku(skuId);
            String json = redisTemplate.opsForValue().get(key);
            if (json == null || json.isBlank()) {
                return Optional.empty();
            }
            return Optional.ofNullable(SkuCacheEntry.fromJson(json));
        } catch (RedisException e) {
            log.error("Redis error finding SKU cache entry: skuId={}", skuId, e);
            return Optional.empty();
        }
    }

    @Override
    public void save(String skuId, SkuCacheEntry entry, Duration ttl) {
        try {
            String key = keyBuilder.sku(skuId);
            String json = entry.toJson();
            redisTemplate.opsForValue().set(key, json, ttl);
        } catch (RedisException e) {
            log.error("Redis error saving SKU cache entry: skuId={}", skuId, e);
        }
    }

    @Override
    public void delete(String skuId) {
        try {
            String key = keyBuilder.sku(skuId);
            redisTemplate.delete(key);
        } catch (RedisException e) {
            log.error("Redis error deleting SKU cache entry: skuId={}", skuId, e);
        }
    }

    @Override
    public void deleteByPattern(String pattern) {
        try {
            var keys = redisTemplate.keys(pattern);
            if (keys != null && !keys.isEmpty()) {
                redisTemplate.delete(keys);
                log.info("Deleted {} SKU cache entries matching pattern: {}", keys.size(), pattern);
            }
        } catch (RedisException e) {
            log.error("Redis error deleting SKU cache entries by pattern: pattern={}", pattern, e);
        }
    }

    @Override
    public List<SkuCacheEntry> findByIds(List<String> skuIds) {
        if (skuIds == null || skuIds.isEmpty()) {
            return List.of();
        }
        try {
            String[] keys = skuIds.stream()
                    .map(keyBuilder::sku)
                    .toArray(String[]::new);
            byte[][] byteKeys = new byte[keys.length][];
            for (int i = 0; i < keys.length; i++) {
                byteKeys[i] = keys[i].getBytes();
            }
            List<String> jsons = redisTemplate.execute((RedisCallback<List<String>>) connection -> {
                @SuppressWarnings("unchecked")
                List<byte[]> byteResults = (List<byte[]>) (List<?>) connection.mGet(byteKeys);
                List<String> jsonList = new ArrayList<>(byteResults.size());
                for (Object result : byteResults) {
                    jsonList.add(result != null ? new String((byte[]) result) : null);
                }
                return jsonList;
            });
            List<SkuCacheEntry> results = new ArrayList<>(skuIds.size());
            for (String json : jsons) {
                results.add(SkuCacheEntry.fromJson(json));
            }
            return results;
        } catch (RedisException e) {
            log.error("Redis error batch finding SKU cache entries: count={}", skuIds.size(), e);
            return List.of();
        }
    }

    @Override
    public Map<String, SkuCacheEntry> findByIdsAsMap(List<String> skuIds) {
        if (skuIds == null || skuIds.isEmpty()) {
            return Map.of();
        }
        try {
            String[] keys = skuIds.stream()
                    .map(keyBuilder::sku)
                    .toArray(String[]::new);
            byte[][] byteKeys = new byte[keys.length][];
            for (int i = 0; i < keys.length; i++) {
                byteKeys[i] = keys[i].getBytes();
            }
            List<String> jsons = redisTemplate.execute((RedisCallback<List<String>>) connection -> {
                @SuppressWarnings("unchecked")
                List<byte[]> byteResults = (List<byte[]>) (List<?>) connection.mGet(byteKeys);
                List<String> jsonList = new ArrayList<>(byteResults.size());
                for (Object result : byteResults) {
                    jsonList.add(result != null ? new String((byte[]) result) : null);
                }
                return jsonList;
            });
            Map<String, SkuCacheEntry> result = new HashMap<>();
            for (int i = 0; i < skuIds.size(); i++) {
                String json = jsons.get(i);
                SkuCacheEntry entry = SkuCacheEntry.fromJson(json);
                if (entry != null) {
                    result.put(skuIds.get(i), entry);
                }
            }
            return result;
        } catch (RedisException e) {
            log.error("Redis error batch finding SKU cache entries as map: count={}", skuIds.size(), e);
            return Map.of();
        }
    }

    @Override
    public void saveBatch(List<SkuCacheEntry> entries, Duration ttl) {
        if (entries == null || entries.isEmpty()) {
            return;
        }
        try {
            redisTemplate.executePipelined((RedisCallback<Object>) connection -> {
                for (SkuCacheEntry entry : entries) {
                    String key = keyBuilder.sku(entry.getSkuId());
                    byte[] keyBytes = key.getBytes();
                    byte[] valueBytes = entry.toJson().getBytes();
                    connection.set(keyBytes, valueBytes);
                    connection.expire(keyBytes, ttl.getSeconds());
                }
                return null;
            });
            log.debug("Redis pipeline saveBatch completed: entries={}", entries.size());
        } catch (Exception e) {
            log.error("Redis pipeline error during saveBatch: count={}", entries.size(), e);
            throw new RedisException("Redis pipeline saveBatch failed", e);
        }
    }
}
