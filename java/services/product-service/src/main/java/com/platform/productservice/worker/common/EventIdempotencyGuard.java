package com.platform.productservice.worker.common;

import com.platform.productservice.infrastructure.redis.RedisKeyBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Provides idempotency guarantees for event processing using Redis.
 * Uses SETNX with TTL to ensure each event is processed at most once.
 */
@Component
public class EventIdempotencyGuard {

    private static final Logger log = LoggerFactory.getLogger(EventIdempotencyGuard.class);
    private static final Duration IDEMPOTENCY_TTL = Duration.ofHours(24);

    private final RedisTemplate<String, String> redisTemplate;
    private final RedisKeyBuilder redisKeyBuilder;

    public EventIdempotencyGuard(RedisTemplate<String, String> redisTemplate, RedisKeyBuilder redisKeyBuilder) {
        this.redisTemplate = redisTemplate;
        this.redisKeyBuilder = redisKeyBuilder;
    }

    /**
     * Checks whether this event has been seen before.
     * If not seen, marks it as seen (atomic SETNX) and returns true.
     * If already seen, returns false.
     *
     * @param eventId the unique event ID
     * @return true if this is a new event, false if already processed
     */
    public boolean isNew(String eventId) {
        if (eventId == null || eventId.isBlank()) {
            log.warn("EventIdempotencyGuard: received null or blank eventId");
            return false;
        }

        String key = redisKeyBuilder.idempotencyKey("event", eventId);
        try {
            Boolean wasSet = redisTemplate.opsForValue().setIfAbsent(key, "1", IDEMPOTENCY_TTL);
            if (Boolean.TRUE.equals(wasSet)) {
                log.debug("EventIdempotencyGuard: new event detected, eventId={}", eventId);
                return true;
            } else {
                log.debug("EventIdempotencyGuard: duplicate event detected, eventId={}", eventId);
                return false;
            }
        } catch (Exception e) {
            log.error("EventIdempotencyGuard: Redis error checking eventId={}: {}", eventId, e.getMessage(), e);
            return false;
        }
    }

    /**
     * Checks whether this event has already been processed.
     *
     * @param eventId the unique event ID
     * @return true if already processed, false otherwise
     */
    public boolean isProcessed(String eventId) {
        if (eventId == null || eventId.isBlank()) {
            return false;
        }

        String key = redisKeyBuilder.idempotencyKey("event", eventId);
        try {
            Boolean exists = redisTemplate.hasKey(key);
            return Boolean.TRUE.equals(exists);
        } catch (Exception e) {
            log.error("EventIdempotencyGuard: Redis error checking processed state for eventId={}: {}",
                    eventId, e.getMessage(), e);
            return false;
        }
    }
}
