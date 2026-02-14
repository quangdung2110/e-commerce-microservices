package com.example.common.util;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.UUID;

/**
 * Idempotency Key Manager using Redis
 * 
 * Prevents duplicate operations:
 * - User double-clicks "Place Order" → Only 1 order created
 * - Payment retry → Not charged twice
 * - Event reprocessing → Not handled twice
 * 
 * Usage:
 * 1. Client generates idempotencyKey (UUID)
 * 2. Send key in header: Idempotency-Key: {uuid}
 * 3. Server checks if key exists in Redis
 * 4. If exists → Return cached result
 * 5. If not → Process & store result in Redis (TTL: 24h)
 */
@Component
@RequiredArgsConstructor
public class IdempotencyKeyManager {
    
    private final RedisTemplate<String, Object> redisTemplate;
    private static final String KEY_PREFIX = "idempotency:";
    private static final Duration DEFAULT_TTL = Duration.ofHours(24);

    /**
     * Check if operation was already processed
     */
    public boolean isProcessed(String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isEmpty()) {
            return false;
        }
        return Boolean.TRUE.equals(redisTemplate.hasKey(KEY_PREFIX + idempotencyKey));
    }

    /**
     * Get cached result for idempotency key
     */
    public <T> T getResult(String idempotencyKey, Class<T> resultType) {
        if (idempotencyKey == null || idempotencyKey.isEmpty()) {
            return null;
        }
        Object result = redisTemplate.opsForValue().get(KEY_PREFIX + idempotencyKey);
        return resultType.cast(result);
    }

    /**
     * Store result for idempotency key
     */
    public void storeResult(String idempotencyKey, Object result) {
        storeResult(idempotencyKey, result, DEFAULT_TTL);
    }

    /**
     * Store result with custom TTL
     */
    public void storeResult(String idempotencyKey, Object result, Duration ttl) {
        if (idempotencyKey == null || idempotencyKey.isEmpty()) {
            return;
        }
        redisTemplate.opsForValue().set(KEY_PREFIX + idempotencyKey, result, ttl);
    }

    /**
     * Generate new idempotency key
     */
    public static String generate() {
        return UUID.randomUUID().toString();
    }

    /**
     * Clear idempotency key (for testing or cleanup)
     */
    public void clear(String idempotencyKey) {
        if (idempotencyKey != null && !idempotencyKey.isEmpty()) {
            redisTemplate.delete(KEY_PREFIX + idempotencyKey);
        }
    }
}
