package com.platform.productservice.infrastructure.redis;

import io.lettuce.core.RedisException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Provides idempotency guarantees for event processing.
 * Uses SETNX with TTL to ensure events are processed at most once.
 */
@Component
public class IdempotencyGuard {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyGuard.class);

    private final RedisTemplate<String, String> redisTemplate;
    private final RedisKeyBuilder keyBuilder;

    public IdempotencyGuard(RedisTemplate<String, String> redisTemplate, RedisKeyBuilder keyBuilder) {
        this.redisTemplate = redisTemplate;
        this.keyBuilder = keyBuilder;
    }

    /**
     * Attempts to acquire an idempotency key.
     * Returns true if this is the first time the event is being processed.
     *
     * @param type the event type (e.g., "sku", "inventory", "markdown")
     * @param id   the event ID
     * @param ttl  the TTL for the idempotency key
     * @return true if acquired (first time), false if already processed
     */
    public boolean tryAcquire(String type, String id, Duration ttl) {
        try {
            String key = keyBuilder.idempotencyKey(type, id);
            Boolean result = redisTemplate.opsForValue().setIfAbsent(key, "1", ttl);
            boolean acquired = Boolean.TRUE.equals(result);
            if (!acquired) {
                log.debug("Idempotency key already exists: type={}, id={}", type, id);
            }
            return acquired;
        } catch (RedisException e) {
            log.error("Redis error checking idempotency: type={}, id={}", type, id, e);
            return false;
        }
    }

    /**
     * Releases an idempotency key.
     *
     * @param type the event type
     * @param id   the event ID
     */
    public void release(String type, String id) {
        try {
            String key = keyBuilder.idempotencyKey(type, id);
            redisTemplate.delete(key);
            log.debug("Released idempotency key: type={}, id={}", type, id);
        } catch (RedisException e) {
            log.error("Redis error releasing idempotency key: type={}, id={}", type, id, e);
        }
    }

    /**
     * Checks if an event has already been processed.
     *
     * @param type the event type
     * @param id   the event ID
     * @return true if already processed
     */
    public boolean isProcessed(String type, String id) {
        try {
            String key = keyBuilder.idempotencyKey(type, id);
            return Boolean.TRUE.equals(redisTemplate.hasKey(key));
        } catch (RedisException e) {
            log.error("Redis error checking idempotency: type={}, id={}", type, id, e);
            return false;
        }
    }
}
