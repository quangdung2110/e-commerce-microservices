package com.ecommerce.promotion.application.port;

/**
 * Port interface cho Rate Limiting.
 * Theo thiết kế redis_caching_design.md - Section 2.5:
 *   Key: promo:ratelimit:claim:{user_id}
 *   Type: String (Integer Counter), TTL: 10 giây
 *   Nếu > 5 (5 req/10s) → chặn 429 Too Many Requests.
 *
 * Infrastructure layer implement bằng Redis INCR + EXPIRE.
 */
public interface RateLimitPort {

    /**
     * Kiểm tra và tăng counter cho user.
     *
     * @param userId ID user
     * @return true nếu user chưa vượt rate limit, false nếu đã vượt
     */
    boolean allowRequest(Long userId);
}
