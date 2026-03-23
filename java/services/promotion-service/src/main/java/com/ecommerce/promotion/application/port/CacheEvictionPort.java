package com.ecommerce.promotion.application.port;

/**
 * Port interface cho Redis Pub/Sub Cache Eviction.
 * Theo thiết kế redis_caching_design.md - Section 2.1:
 *   Admin thay đổi → Pub/Sub event "PROMO_EVICT {promotion_id}"
 *   → Tất cả Java Instances nhận → Caffeine refreshAfterWrite kích hoạt.
 * Theo thiết kế promotion_workflow.md - Luồng 1 (Admin Workflow) Bước 3.
 *
 * Infrastructure layer sẽ implement bằng Redis Pub/Sub (channel: promo:cache:evict).
 */
public interface CacheEvictionPort {

    /**
     * Bắn Pub/Sub event thông báo cho tất cả instances refreshAfterWrite.
     * Channel: channel:promo:cache:evict (Cấu hình tại application.yml).
     *
     * @param promotionId ID của promotion cần evict cache
     */
    void evictPromotionCache(Long promotionId);

    /**
     * Bắn Pub/Sub event thông báo refresh markdown rules cache.
     */
    void evictMarkdownCache();
}
