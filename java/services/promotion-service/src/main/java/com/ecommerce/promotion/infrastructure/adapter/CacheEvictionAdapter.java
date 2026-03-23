package com.ecommerce.promotion.infrastructure.adapter;

import com.ecommerce.promotion.application.port.CacheEvictionPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Infrastructure Adapter — Redis Pub/Sub Cache Eviction.
 * Implements CacheEvictionPort theo thiết kế redis_caching_design.md - Section 2.1.
 *
 * Khi Admin cập nhật Promotion → PUBLISH message lên channel.
 * Tất cả Java instances subscribe Channel → Caffeine refreshAfterWrite reload.
 */
@Component
public class CacheEvictionAdapter implements CacheEvictionPort {

    private static final Logger log = LoggerFactory.getLogger(CacheEvictionAdapter.class);

    /**
     * Redis Pub/Sub Channel — tất cả instances subscribe channel này.
     * Theo thiết kế redis_caching_design.md - Section 2.1.
     */
    public static final String CACHE_EVICT_CHANNEL = "channel:promo:cache:evict";

    private final StringRedisTemplate redisTemplate;

    public CacheEvictionAdapter(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    /**
     * PUBLISH "PROMO_EVICT:{promotionId}" lên channel.
     * Tất cả instances nhận → Caffeine invalidate → refreshAfterWrite auto reload.
     */
    @Override
    public void evictPromotionCache(Long promotionId) {
        String message = "PROMO_EVICT:" + promotionId;
        redisTemplate.convertAndSend(CACHE_EVICT_CHANNEL, message);
        log.info("Published cache eviction: channel={}, message={}", CACHE_EVICT_CHANNEL, message);
    }

    /**
     * PUBLISH "MARKDOWN_EVICT" lên channel → tất cả instances reload markdown rules cache.
     */
    @Override
    public void evictMarkdownCache() {
        String message = "MARKDOWN_EVICT";
        redisTemplate.convertAndSend(CACHE_EVICT_CHANNEL, message);
        log.info("Published markdown cache eviction: channel={}", CACHE_EVICT_CHANNEL);
    }
}
