package com.ecommerce.promotion.infrastructure.adapter;

import com.ecommerce.promotion.application.port.RateLimitPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Infrastructure Adapter — Redis-based Rate Limiting.
 * Implements RateLimitPort theo thiết kế redis_caching_design.md - Section 2.5.
 *
 * Key: promo:ratelimit:claim:{user_id}
 * Type: String (Integer Counter)
 * TTL: 10 giây
 * Nếu > 5 (5 req/10s) → chặn 429 Too Many Requests.
 */
@Component
public class RateLimitAdapter implements RateLimitPort {

    private static final Logger log = LoggerFactory.getLogger(RateLimitAdapter.class);

    private static final int MAX_REQUESTS = 5;
    private static final Duration WINDOW = Duration.ofSeconds(10);

    private final StringRedisTemplate redisTemplate;

    public RateLimitAdapter(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    /**
     * INCR + EXPIRE pattern:
     * 1. INCR key → Nếu key mới (value=1), SET TTL = 10s
     * 2. Nếu value > 5 → rate limited
     *
     * Theo thiết kế redis_caching_design.md - Section 2.5.
     */
    @Override
    public boolean allowRequest(Long userId) {
        String key = "promo:ratelimit:claim:" + userId;

        Long count = redisTemplate.opsForValue().increment(key);
        if (count == null) {
            return true; // Redis unavailable → fail open
        }

        if (count == 1) {
            // Key mới → set TTL
            redisTemplate.expire(key, WINDOW);
        }

        boolean allowed = count <= MAX_REQUESTS;
        if (!allowed) {
            log.warn("Rate limited: userId={}, count={}", userId, count);
        }
        return allowed;
    }
}
