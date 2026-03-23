package com.ecommerce.promotion.application.exception;

/**
 * Ném khi user bị chặn do spam API quá nhiều.
 * Theo thiết kế redis_caching_design.md - Section 2.5: Rate Limiting 5req/10s.
 * Maps to HTTP 429 Too Many Requests.
 */
public class RateLimitExceededException extends CoreBusinessException {

    public RateLimitExceededException(Long userId) {
        super(
                String.format("Rate limit exceeded for user: %d", userId),
                "RATE_LIMIT_EXCEEDED");
    }
}
