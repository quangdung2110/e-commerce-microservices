package com.example.common.util;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Distributed Cache Manager using Redis
 * 
 * Use Cases:
 * 1. Cache cart data (reduce DB queries)
 * 2. Cache promotion rules (faster validation)
 * 3. Cache product info (less calls to product service)
 * 4. Session storage for anonymous users
 */
@Component
@RequiredArgsConstructor
public class CacheManager {
    
    private final RedisTemplate<String, Object> redisTemplate;

    /**
     * Get value from cache
     */
    public <T> T get(String key, Class<T> type) {
        Object value = redisTemplate.opsForValue().get(key);
        return type.cast(value);
    }

    /**
     * Store value in cache with default TTL (1 hour)
     */
    public void put(String key, Object value) {
        put(key, value, Duration.ofHours(1));
    }

    /**
     * Store value in cache with custom TTL
     */
    public void put(String key, Object value, Duration ttl) {
        redisTemplate.opsForValue().set(key, value, ttl);
    }

    /**
     * Check if key exists in cache
     */
    public boolean exists(String key) {
        return Boolean.TRUE.equals(redisTemplate.hasKey(key));
    }

    /**
     * Delete key from cache
     */
    public void evict(String key) {
        redisTemplate.delete(key);
    }

    /**
     * Delete multiple keys matching pattern
     */
    public void evictPattern(String pattern) {
        var keys = redisTemplate.keys(pattern);
        if (keys != null && !keys.isEmpty()) {
            redisTemplate.delete(keys);
        }
    }

    /**
     * Increment counter (for rate limiting)
     */
    public Long increment(String key) {
        return redisTemplate.opsForValue().increment(key);
    }

    /**
     * Increment counter with TTL
     */
    public Long incrementWithExpiry(String key, Duration ttl) {
        Long value = redisTemplate.opsForValue().increment(key);
        if (value != null && value == 1) {
            redisTemplate.expire(key, ttl);
        }
        return value;
    }
}
